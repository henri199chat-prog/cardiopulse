package com.cardiopulse.app.core.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class PpgSignalProcessorTest {

    /**
     * Generates a physiologically realistic fingertip PPG red-channel signal:
     * - DC optical baseline (~210)
     * - Systolic arterial absorption dip (inverted in processor to upward peak)
     * - Secondary dicrotic notch / diastolic wave at ~0.38 cycle phase
     * - Low-frequency respiratory baseline wander (~0.25 Hz)
     * - Mild Gaussian camera sensor noise
     * - Slight realistic frame timestamp jitter (~±2 ms around 30 FPS)
     */
    private fun feedSyntheticPpg(
        processor: PpgSignalProcessor,
        targetBpm: Double,
        durationSeconds: Double = 20.0,
        noiseStd: Double = 0.08,
        motionRms: Double = 0.05,
        seed: Int = 42
    ) {
        val rng = Random(seed)
        val nominalFs = 30.0
        val totalFrames = (durationSeconds * nominalFs).toInt()
        val f0 = targetBpm / 60.0
        val baseNanos = 1_000_000_000L

        for (i in 0 until totalFrames) {
            val jitterSec = (rng.nextDouble() - 0.5) * 0.004
            val t = (i / nominalFs + jitterSec).coerceAtLeast(0.0)
            val phase = (t * f0) % 1.0

            // Realistic PPG pulse template: sharp systolic wave + smaller dicrotic wave
            val systolic = exp(-((phase - 0.25) * (phase - 0.25)) / (2.0 * 0.055 * 0.055))
            val dicrotic = 0.35 * exp(-((phase - 0.56) * (phase - 0.56)) / (2.0 * 0.09 * 0.09))
            val arterialVolume = systolic + dicrotic

            val respiratoryDrift = 0.9 * sin(2.0 * PI * 0.25 * t)
            val noise = (rng.nextDouble() - 0.5) * 2.0 * noiseStd

            // Beer-Lambert absorption: higher arterial blood volume lowers reflected red intensity
            val meanRed = 210.0 - 2.6 * arterialVolume + respiratoryDrift + noise

            processor.addSample(
                sample = FrameColorSample(
                    timestampNanos = baseNanos + (t * 1_000_000_000.0).toLong(),
                    meanRed = meanRed,
                    meanGreen = 24.0,
                    meanBlue = 18.0,
                    redStdDev = 4.5,
                    saturatedPixelRatio = 0.0
                ),
                motionRms = motionRms
            )
        }
    }

    @Test
    fun accuratelyMeasuresRestingHeartRatesAcrossPhysiologicalRange() {
        val testRates = listOf(52.0, 72.0, 96.0, 132.0)

        for ((index, expectedBpm) in testRates.withIndex()) {
            val processor = PpgSignalProcessor()
            feedSyntheticPpg(processor, targetBpm = expectedBpm, seed = 100 + index)

            val outcome = processor.finalizeMeasurement()
            assertTrue("Expected Verified outcome for $expectedBpm BPM, got $outcome", outcome is PpgOutcome.Verified)
            val verified = outcome as PpgOutcome.Verified

            assertTrue(
                "Expected BPM within ±1.5 of $expectedBpm, got ${verified.bpm} (time=${verified.timeDomainBpm}, fft=${verified.spectralBpm})",
                abs(verified.bpm - expectedBpm) <= 1.5
            )
            assertTrue("Expected high SQI >= 75 for clean PPG, got ${verified.sqiScore}", verified.sqiScore >= 75)
            assertEquals(ConfidenceLevel.HIGH, verified.confidence)
            assertEquals(RhythmAssessment.REGULAR, verified.rhythmAssessment)
        }
    }

    @Test
    fun rejectsExcessiveHandMotionInsteadOfGuessing() {
        val processor = PpgSignalProcessor()
        feedSyntheticPpg(processor, targetBpm = 75.0, motionRms = 0.85)

        val outcome = processor.finalizeMeasurement()
        assertTrue("Expected Rejected due to motion, got $outcome", outcome is PpgOutcome.Rejected)
        assertEquals(PpgFailureReason.EXCESSIVE_MOTION, (outcome as PpgOutcome.Rejected).reason)
    }

    @Test
    fun rejectsWhiteNoiseSignalWithoutFabricatingBpm() {
        val processor = PpgSignalProcessor()
        val rng = Random(7)
        val baseNanos = 5_000_000_000L

        for (i in 0 until 600) {
            val t = i / 30.0
            processor.addSample(
                sample = FrameColorSample(
                    timestampNanos = baseNanos + (t * 1_000_000_000.0).toLong(),
                    meanRed = 200.0 + (rng.nextDouble() - 0.5) * 4.0,
                    meanGreen = 25.0,
                    meanBlue = 20.0,
                    redStdDev = 5.0,
                    saturatedPixelRatio = 0.0
                ),
                motionRms = 0.04
            )
        }

        val outcome = processor.finalizeMeasurement()
        assertTrue("Pure random noise must be rejected, got $outcome", outcome is PpgOutcome.Rejected)
    }

    @Test
    fun rejectsFlatStaticSurfaceSignal() {
        val processor = PpgSignalProcessor()
        val baseNanos = 1_000_000_000L
        for (i in 0 until 600) {
            processor.addSample(
                sample = FrameColorSample(
                    timestampNanos = baseNanos + (i * 33_333_333L),
                    meanRed = 205.0,
                    meanGreen = 20.0,
                    meanBlue = 15.0,
                    redStdDev = 2.0,
                    saturatedPixelRatio = 0.0
                ),
                motionRms = 0.01
            )
        }

        val outcome = processor.finalizeMeasurement()
        assertTrue("Flat signal must be rejected, got $outcome", outcome is PpgOutcome.Rejected)
    }

    @Test
    fun rejectsShortInsufficientCapture() {
        val processor = PpgSignalProcessor()
        feedSyntheticPpg(processor, targetBpm = 68.0, durationSeconds = 5.0)

        val outcome = processor.finalizeMeasurement(minDurationSeconds = 12.0)
        assertTrue(outcome is PpgOutcome.Rejected)
        assertEquals(PpgFailureReason.INSUFFICIENT_DURATION, (outcome as PpgOutcome.Rejected).reason)
    }

    @Test
    fun computesPositiveHrvMetricsOnModulatedSinusArrhythmia() {
        val processor = PpgSignalProcessor()
        val baseNanos = 2_000_000_000L
        val fs = 30.0
        var phase = 0.0

        // Sinus arrhythmia oscillating smoothly between ~64 BPM and ~76 BPM (0.2 Hz breathing)
        for (i in 0 until 600) {
            val t = i / fs
            val instFreqHz = (70.0 + 6.0 * sin(2.0 * PI * 0.2 * t)) / 60.0
            phase += instFreqHz / fs
            val pulse = sin(2.0 * PI * phase)
            val meanRed = 212.0 - 2.2 * pulse

            processor.addSample(
                sample = FrameColorSample(
                    timestampNanos = baseNanos + (t * 1_000_000_000.0).toLong(),
                    meanRed = meanRed,
                    meanGreen = 22.0,
                    meanBlue = 18.0,
                    redStdDev = 4.0,
                    saturatedPixelRatio = 0.0
                ),
                motionRms = 0.04
            )
        }

        val outcome = processor.finalizeMeasurement()
        assertTrue("Expected Verified outcome, got $outcome", outcome is PpgOutcome.Verified)
        val verified = outcome as PpgOutcome.Verified
        assertTrue("Expected BPM near 70, got ${verified.bpm}", verified.bpm in 67..73)
        assertTrue("Expected realistic non-zero RMSSD HRV, got ${verified.hrvRmssdMs}", verified.hrvRmssdMs > 12.0)
        assertTrue("Expected realistic SDNN HRV, got ${verified.hrvSdnnMs}", verified.hrvSdnnMs > 15.0)
    }
}
