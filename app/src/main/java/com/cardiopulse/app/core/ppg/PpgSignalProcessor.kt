package com.cardiopulse.app.core.ppg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-integrity biomedical Photoplethysmography (PPG) signal processor.
 *
 * Combines:
 * 1. Uniform 30 Hz resampling from monotonic hardware timestamps
 * 2. Moving-baseline detrending & zero-phase 4th-order Butterworth bandpass (0.65–3.5 Hz)
 * 3. Spectral analysis (Hann-windowed high-resolution DFT + parabolic interpolation)
 * 4. Time-domain systolic peak detection with parabolic sub-sample refinement,
 *    dicrotic-notch suppression, and MAD outlier rejection for IBI & HRV (RMSSD/SDNN/pNN50)
 * 5. Dual-method cross-validation & Signal Quality Index (SQI) gating:
 *    refuses to emit a result when motion, low perfusion, or method disagreement occur.
 */
class PpgSignalProcessor(
    private val targetSampleRateHz: Double = 30.0,
    private val minHrHz: Double = 0.65, // 39 BPM
    private val maxHrHz: Double = 3.50, // 210 BPM
    private val maxMotionRmsThreshold: Double = 0.50
) {
    // Use 0.55 Hz high-pass and 3.8 Hz low-pass so the full 0.65–3.50 Hz cardiac band
    // lies in the flat passband without attenuating resting bradycardia (45–60 BPM).
    private val highPass = BiquadFilter.butterworthHighPass(0.55, targetSampleRateHz)
    private val lowPass = BiquadFilter.butterworthLowPass(3.80, targetSampleRateHz)

    private val timestampsSec = ArrayList<Double>(1024)
    private val rawRedSignal = ArrayList<Double>(1024)
    private val motionSamples = ArrayList<Double>(1024)
    private var startTimestampNanos: Long? = null

    fun reset() {
        timestampsSec.clear()
        rawRedSignal.clear()
        motionSamples.clear()
        startTimestampNanos = null
    }

    /**
     * Appends a validated fingertip frame sample along with instantaneous motion RMS (m/s^2).
     */
    fun addSample(sample: FrameColorSample, motionRms: Double) {
        val t0 = startTimestampNanos ?: sample.timestampNanos.also { startTimestampNanos = it }
        val elapsedSec = (sample.timestampNanos - t0) / 1_000_000_000.0
        if (timestampsSec.isNotEmpty() && elapsedSec <= timestampsSec.last()) {
            return
        }
        timestampsSec.add(elapsedSec)
        // Store meanRed (higher capillary blood volume during systole absorbs more light,
        // so we invert during bandpass processing so systolic pulse appears as positive peak).
        rawRedSignal.add(sample.meanRed)
        motionSamples.add(motionRms)
    }

    fun getElapsedSeconds(): Double {
        if (timestampsSec.size < 2) return 0.0
        return timestampsSec.last() - timestampsSec.first()
    }

    fun getSampleCount(): Int = rawRedSignal.size

    /**
     * Computes live preview waveform (normalized -1..+1), live BPM estimate,
     * perfusion index, and real-time SQI over the latest sliding window (last 8 seconds).
     */
    fun analyzeLiveWindow(windowSeconds: Double = 8.0): LiveAnalysisSnapshot {
        if (timestampsSec.size < 45 || getElapsedSeconds() < 2.0) {
            return LiveAnalysisSnapshot(
                instantBpm = null,
                sqiScore = 0,
                perfusionIndexPercent = 0.0,
                waveform = emptyList(),
                recentMotionRms = recentMotionRms(1.5)
            )
        }

        val endTime = timestampsSec.last()
        val startIdx = timestampsSec.indexOfFirst { it >= endTime - windowSeconds }.coerceAtLeast(0)
        val tSlice = timestampsSec.subList(startIdx, timestampsSec.size).toDoubleArray()
        val rSlice = rawRedSignal.subList(startIdx, rawRedSignal.size).toDoubleArray()
        val mSlice = motionSamples.subList(startIdx, motionSamples.size).toDoubleArray()

        val processed = preprocessSignal(tSlice, rSlice)
        val displayWindowSamples = (targetSampleRateHz * 4.0).toInt()
        val displaySlice = if (processed.filtered.size > displayWindowSamples) {
            processed.filtered.copyOfRange(
                processed.filtered.size - displayWindowSamples,
                processed.filtered.size
            )
        } else {
            processed.filtered
        }
        val normalizedWaveform = normalizeForDisplay(displaySlice)

        // Require at least 4.5 seconds before displaying a live numeric BPM
        if (tSlice.last() - tSlice.first() < 4.5) {
            return LiveAnalysisSnapshot(
                instantBpm = null,
                sqiScore = (processed.perfusionIndexPercent * 30.0).roundToInt().coerceIn(0, 45),
                perfusionIndexPercent = processed.perfusionIndexPercent,
                waveform = normalizedWaveform,
                recentMotionRms = mSlice.averageOrZero()
            )
        }

        val spectral = computeSpectralAnalysis(processed.filtered, targetSampleRateHz)
        val peakResult = detectSystolicPeaks(processed.filtered, targetSampleRateHz, spectral.dominantHz)
        val motionRms = recentMotionRms(2.0)
        val quality = computeQualityMetrics(
            spectral = spectral,
            peakResult = peakResult,
            perfusionIndexPercent = processed.perfusionIndexPercent,
            motionRms = motionRms
        )

        val liveBpm = if (quality.sqiScore >= 45 && peakResult.validIbiMs.size >= 3) {
            val blended = if (quality.timeFreqDiffBpm <= 6.0) {
                0.6 * peakResult.bpm + 0.4 * spectral.bpm
            } else {
                spectral.bpm
            }
            blended.roundToInt().coerceIn(39, 210)
        } else {
            null
        }

        return LiveAnalysisSnapshot(
            instantBpm = liveBpm,
            sqiScore = quality.sqiScore,
            perfusionIndexPercent = processed.perfusionIndexPercent,
            waveform = normalizedWaveform,
            recentMotionRms = motionRms
        )
    }

    /**
     * Performs full-session cross-validated analysis and enforces strict quality gates
     * so inaccurate or motion-corrupted captures are explicitly rejected instead of guessed.
     */
    fun finalizeMeasurement(minDurationSeconds: Double = 12.0): PpgOutcome {
        val totalDuration = getElapsedSeconds()
        val avgMotion = motionSamples.toDoubleArray().averageOrZero()

        if (timestampsSec.size < 150 || totalDuration < minDurationSeconds) {
            return PpgOutcome.Rejected(
                reason = PpgFailureReason.INSUFFICIENT_DURATION,
                sqiScore = 0,
                motionRms = avgMotion,
                detailMessage = "Duration ${"%.1f".format(totalDuration)}s < ${minDurationSeconds}s"
            )
        }

        // Trim initial 1.5s settling period (auto-exposure / finger settling) if duration permits
        val trimSeconds = if (totalDuration >= 16.0) 1.5 else 0.5
        val t0 = timestampsSec.first() + trimSeconds
        val startIdx = timestampsSec.indexOfFirst { it >= t0 }.coerceAtLeast(0)

        val tArr = timestampsSec.subList(startIdx, timestampsSec.size).toDoubleArray()
        val rArr = rawRedSignal.subList(startIdx, rawRedSignal.size).toDoubleArray()
        val mArr = motionSamples.subList(startIdx, motionSamples.size).toDoubleArray()

        // Motion rejection gate: compute 85th percentile motion RMS so bursts are caught
        val sortedMotion = mArr.sortedArray()
        val p85Motion = if (sortedMotion.isNotEmpty()) {
            sortedMotion[(sortedMotion.size * 0.85).toInt().coerceAtMost(sortedMotion.lastIndex)]
        } else 0.0

        if (p85Motion > maxMotionRmsThreshold) {
            return PpgOutcome.Rejected(
                reason = PpgFailureReason.EXCESSIVE_MOTION,
                sqiScore = 20,
                motionRms = p85Motion,
                detailMessage = "Motion RMS p85=${"%.2f".format(p85Motion)} m/s^2"
            )
        }

        val processed = preprocessSignal(tArr, rArr)
        val spectral = computeSpectralAnalysis(processed.filtered, targetSampleRateHz)
        val peakResult = detectSystolicPeaks(processed.filtered, targetSampleRateHz, spectral.dominantHz)
        val quality = computeQualityMetrics(
            spectral = spectral,
            peakResult = peakResult,
            perfusionIndexPercent = processed.perfusionIndexPercent,
            motionRms = p85Motion
        )

        // Gate 1: Minimum verified beats
        if (peakResult.validIbiMs.size < 8) {
            return PpgOutcome.Rejected(
                reason = PpgFailureReason.LOW_SIGNAL_QUALITY,
                sqiScore = quality.sqiScore,
                motionRms = p85Motion,
                detailMessage = "Too few clean beats (${peakResult.validIbiMs.size})"
            )
        }

        // Gate 2: Dual-method agreement (Time-domain IBI vs Frequency-domain Spectral)
        if (quality.timeFreqDiffBpm > 4.5) {
            return PpgOutcome.Rejected(
                reason = PpgFailureReason.METHOD_DISAGREEMENT,
                sqiScore = quality.sqiScore,
                motionRms = p85Motion,
                detailMessage = "Peak BPM=${"%.1f".format(peakResult.bpm)} vs FFT BPM=${"%.1f".format(spectral.bpm)}"
            )
        }

        // Gate 3: Minimum Signal Quality Index (SQI)
        if (quality.sqiScore < 62 || spectral.spectralPurity < 0.32) {
            return PpgOutcome.Rejected(
                reason = PpgFailureReason.LOW_SIGNAL_QUALITY,
                sqiScore = quality.sqiScore,
                motionRms = p85Motion,
                detailMessage = "SQI=${quality.sqiScore}, purity=${"%.2f".format(spectral.spectralPurity)}"
            )
        }

        // Fuse time-domain and spectral BPM (weight time-domain slightly higher since
        // parabolic-refined trimmed IBI tracks physiological average accurately)
        val fusedBpm = (0.65 * peakResult.bpm + 0.35 * spectral.bpm).roundToInt().coerceIn(39, 210)
        val confidence = if (quality.sqiScore >= 80 && quality.timeFreqDiffBpm <= 2.2) {
            ConfidenceLevel.HIGH
        } else {
            ConfidenceLevel.MEDIUM
        }

        // Rhythm regularity check: coefficient of variation of clean IBIs and high successive diffs
        val ibiMean = peakResult.validIbiMs.average()
        val ibiCv = if (ibiMean > 0.0) peakResult.sdnnMs / ibiMean else 0.0
        val rhythm = if (ibiCv > 0.16 && peakResult.rmssdMs > 95.0 && peakResult.validIbiMs.size >= 10) {
            RhythmAssessment.IRREGULAR_DETECTED
        } else {
            RhythmAssessment.REGULAR
        }

        return PpgOutcome.Verified(
            bpm = fusedBpm,
            timeDomainBpm = peakResult.bpm,
            spectralBpm = spectral.bpm,
            hrvRmssdMs = peakResult.rmssdMs,
            hrvSdnnMs = peakResult.sdnnMs,
            pnn50Percent = peakResult.pnn50Percent,
            sqiScore = quality.sqiScore,
            perfusionIndexPercent = processed.perfusionIndexPercent,
            confidence = confidence,
            rhythmAssessment = rhythm,
            durationSeconds = totalDuration.roundToInt(),
            validBeatCount = peakResult.validIbiMs.size + 1,
            ibiValuesMs = peakResult.validIbiMs
        )
    }

    internal data class PreprocessedSignal(
        val filtered: DoubleArray,
        val dcLevel: Double,
        val perfusionIndexPercent: Double
    )

    internal fun preprocessSignal(
        timeSec: DoubleArray,
        rawRed: DoubleArray
    ): PreprocessedSignal {
        val uniform = BiquadFilter.resampleUniform(timeSec, rawRed, targetSampleRateHz)
        val dcLevel = uniform.averageOrZero().coerceAtLeast(1.0)

        // Invert optical intensity so systolic arterial blood volume increase = positive peak
        val inverted = DoubleArray(uniform.size) { i -> -(uniform[i] - dcLevel) }

        // Detrend slow baseline drift (1.8s window keeps >= 40 BPM unattenuated)
        val detrendWindow = (targetSampleRateHz * 1.8).roundToInt().coerceAtLeast(5)
        val detrended = BiquadFilter.removeMovingBaseline(inverted, detrendWindow)

        // Zero-phase bandpass 0.65 – 3.5 Hz
        val hp = highPass.filterZeroPhase(detrended)
        val bp = lowPass.filterZeroPhase(hp)

        // Estimate robust peak-to-peak AC amplitude using 5th..95th percentile
        val sorted = bp.sortedArray()
        val p05 = sorted[(sorted.size * 0.05).toInt()]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        val acPeakToPeak = (p95 - p05).coerceAtLeast(0.0)
        val perfusionIndex = (acPeakToPeak / dcLevel) * 100.0

        return PreprocessedSignal(
            filtered = bp,
            dcLevel = dcLevel,
            perfusionIndexPercent = perfusionIndex
        )
    }

    internal data class SpectralResult(
        val dominantHz: Double,
        val bpm: Double,
        val spectralPurity: Double
    )

    /**
     * Hann-windowed high-resolution DFT over the physiological cardiac band [0.65, 3.50] Hz
     * with parabolic peak refinement for sub-0.1 BPM accuracy.
     */
    internal fun computeSpectralAnalysis(signal: DoubleArray, fs: Double): SpectralResult {
        val n = signal.size
        if (n < 30) return SpectralResult(1.0, 60.0, 0.0)

        // Apply Hann window to minimize spectral leakage
        val windowed = DoubleArray(n)
        val denom = (n - 1).coerceAtLeast(1).toDouble()
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / denom))
            windowed[i] = signal[i] * w
        }

        val df = 0.015 // 0.9 BPM bin spacing before parabolic interpolation
        val numBins = (((maxHrHz - minHrHz) / df).toInt() + 1).coerceAtLeast(10)
        val freqs = DoubleArray(numBins) { i -> minHrHz + i * df }
        val power = DoubleArray(numBins)

        var totalPower = 0.0
        var maxPower = -1.0
        var maxIdx = 0

        for (k in 0 until numBins) {
            val f = freqs[k]
            val omega = 2.0 * PI * f / fs
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val angle = omega * i
                re += windowed[i] * cos(angle)
                im -= windowed[i] * sin(angle)
            }
            val p = (re * re + im * im) / (n.toDouble() * n.toDouble())
            power[k] = p
            totalPower += p
            if (p > maxPower) {
                maxPower = p
                maxIdx = k
            }
        }

        // Sub-harmonic disambiguation: in PPG waveforms with a strong dicrotic wave,
        // the 2nd harmonic (2*f0) can rival or exceed f0 in raw DFT magnitude.
        // If f_peak / 2 lies inside the cardiac band with meaningful spectral energy AND
        // normalized time-domain autocorrelation is stronger at lag (1 / f_half) than (1 / f_peak),
        // then f_half is the true cardiac fundamental!
        var fundamentalIdx = maxIdx
        val peakFreqCandidate = freqs[maxIdx]
        val halfFreq = peakFreqCandidate / 2.0
        if (halfFreq >= minHrHz) {
            val halfIdxApprox = ((halfFreq - minHrHz) / df).roundToInt().coerceIn(0, numBins - 1)
            val searchStart = (halfIdxApprox - 4).coerceAtLeast(0)
            val searchEnd = (halfIdxApprox + 4).coerceAtMost(numBins - 1)
            var bestHalfIdx = halfIdxApprox
            var bestHalfPower = -1.0
            for (idx in searchStart..searchEnd) {
                if (power[idx] > bestHalfPower) {
                    bestHalfPower = power[idx]
                    bestHalfIdx = idx
                }
            }
            if (bestHalfPower >= 0.25 * maxPower) {
                val acPeak = normalizedAutocorrelation(signal, (fs / peakFreqCandidate).roundToInt())
                val acHalf = normalizedAutocorrelation(signal, (fs / freqs[bestHalfIdx]).roundToInt())
                if (acHalf > acPeak + 0.05) {
                    fundamentalIdx = bestHalfIdx
                }
            }
        }

        // Parabolic peak interpolation around fundamentalIdx
        val refinedFreq = if (fundamentalIdx in 1 until (numBins - 1)) {
            val alpha = power[fundamentalIdx - 1]
            val beta = power[fundamentalIdx]
            val gamma = power[fundamentalIdx + 1]
            val d = alpha - 2.0 * beta + gamma
            val delta = if (abs(d) > 1e-12) 0.5 * (alpha - gamma) / d else 0.0
            freqs[fundamentalIdx] + delta.coerceIn(-0.5, 0.5) * df
        } else {
            freqs[fundamentalIdx]
        }

        // Compute spectral purity: power near fundamental (+/-0.14 Hz) + 2nd & 3rd harmonics
        val harmonic2Hz = refinedFreq * 2.0
        val harmonic3Hz = refinedFreq * 3.0
        var signalPower = 0.0
        for (k in 0 until numBins) {
            val f = freqs[k]
            val nearFundamental = abs(f - refinedFreq) <= 0.14
            val nearHarmonic2 = harmonic2Hz <= maxHrHz && abs(f - harmonic2Hz) <= 0.14
            val nearHarmonic3 = harmonic3Hz <= maxHrHz && abs(f - harmonic3Hz) <= 0.14
            if (nearFundamental || nearHarmonic2 || nearHarmonic3) {
                signalPower += power[k]
            }
        }

        val purity = if (totalPower > 1e-12) (signalPower / totalPower).coerceIn(0.0, 1.0) else 0.0
        return SpectralResult(
            dominantHz = refinedFreq,
            bpm = refinedFreq * 60.0,
            spectralPurity = purity
        )
    }

    private fun normalizedAutocorrelation(signal: DoubleArray, lag: Int): Double {
        val n = signal.size
        if (lag <= 0 || lag >= n - 4) return -1.0
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        val count = n - lag
        for (i in 0 until count) {
            val a = signal[i]
            val b = signal[i + lag]
            num += a * b
            denA += a * a
            denB += b * b
        }
        val denom = sqrt(denA * denB)
        return if (denom > 1e-12) num / denom else -1.0
    }

    internal data class PeakAnalysisResult(
        val bpm: Double,
        val validIbiMs: List<Double>,
        val rmssdMs: Double,
        val sdnnMs: Double,
        val pnn50Percent: Double,
        val ibiConsistencyScore: Double
    )

    /**
     * Time-domain systolic peak detector with parabolic refinement, spectral-guided
     * dicrotic-notch suppression, and MAD outlier filtering for accurate BPM & HRV.
     */
    internal fun detectSystolicPeaks(
        signal: DoubleArray,
        fs: Double,
        spectralFundamentalHz: Double
    ): PeakAnalysisResult {
        val n = signal.size
        if (n < 30) {
            return PeakAnalysisResult(0.0, emptyList(), 0.0, 0.0, 0.0, 0.0)
        }

        val medianVal = median(signal)
        val absDevs = DoubleArray(n) { i -> abs(signal[i] - medianVal) }
        val madSigma = (1.4826 * median(absDevs)).coerceAtLeast(1e-6)
        val ampThreshold = medianVal + 0.30 * madSigma

        // Dynamic minimum peak distance guided by spectral period so dicrotic notches
        // (~0.35–0.45 of a cardiac cycle after systole) are never miscounted as beats.
        val expectedPeriodSamples = (fs / spectralFundamentalHz.coerceIn(minHrHz, maxHrHz))
        val minDistanceSamples = max(
            (fs * 0.28).roundToInt(),
            (expectedPeriodSamples * 0.58).roundToInt()
        )

        val candidateIndices = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (signal[i] > signal[i - 1] && signal[i] >= signal[i + 1] && signal[i] >= ampThreshold) {
                if (candidateIndices.isEmpty()) {
                    candidateIndices.add(i)
                } else {
                    val prev = candidateIndices.last()
                    if (i - prev < minDistanceSamples) {
                        // Keep the stronger (systolic) peak
                        if (signal[i] > signal[prev]) {
                            candidateIndices[candidateIndices.lastIndex] = i
                        }
                    } else {
                        candidateIndices.add(i)
                    }
                }
            }
        }

        if (candidateIndices.size < 3) {
            return PeakAnalysisResult(0.0, emptyList(), 0.0, 0.0, 0.0, 0.0)
        }

        // Sub-sample parabolic refinement of each peak timestamp (in ms)
        val refinedPeakTimesMs = candidateIndices.map { idx ->
            val y0 = signal[idx - 1]
            val y1 = signal[idx]
            val y2 = signal[idx + 1]
            val denom = y0 - 2.0 * y1 + y2
            val delta = if (abs(denom) > 1e-12) {
                (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
            } else 0.0
            ((idx + delta) / fs) * 1000.0
        }

        // Raw IBIs within physiological range [285 ms .. 1550 ms] (~39..210 BPM)
        val rawIbis = ArrayList<Double>()
        for (i in 1 until refinedPeakTimesMs.size) {
            val ibi = refinedPeakTimesMs[i] - refinedPeakTimesMs[i - 1]
            if (ibi in 285.0..1550.0) {
                rawIbis.add(ibi)
            }
        }

        if (rawIbis.size < 2) {
            return PeakAnalysisResult(0.0, emptyList(), 0.0, 0.0, 0.0, 0.0)
        }

        // Reject artifactual IBIs deviating > 22% from median IBI
        val medianIbi = median(rawIbis.toDoubleArray())
        val validIbis = rawIbis.filter { abs(it - medianIbi) / medianIbi <= 0.22 }

        if (validIbis.size < 2) {
            return PeakAnalysisResult(0.0, emptyList(), 0.0, 0.0, 0.0, 0.0)
        }

        // Trimmed mean IBI (drop extreme 10% on each side if >= 6 beats) for robust BPM
        val sortedIbis = validIbis.sorted()
        val trim = if (sortedIbis.size >= 6) max(1, (sortedIbis.size * 0.1).toInt()) else 0
        val trimmedMeanIbi = sortedIbis.subList(trim, sortedIbis.size - trim).average()
        val bpm = 60_000.0 / trimmedMeanIbi

        // Compute HRV: SDNN, RMSSD, pNN50 on consecutive clean IBIs
        val meanIbi = validIbis.average()
        val variance = validIbis.sumOf { (it - meanIbi).pow(2) } / (validIbis.size - 1).coerceAtLeast(1)
        val sdnn = sqrt(variance)

        var sumSqDiff = 0.0
        var nn50Count = 0
        var successivePairs = 0
        // Only compute successive differences between genuinely adjacent valid peaks
        for (i in 1 until refinedPeakTimesMs.size - 1) {
            val ibi1 = refinedPeakTimesMs[i] - refinedPeakTimesMs[i - 1]
            val ibi2 = refinedPeakTimesMs[i + 1] - refinedPeakTimesMs[i]
            val valid1 = ibi1 in 285.0..1550.0 && abs(ibi1 - medianIbi) / medianIbi <= 0.22
            val valid2 = ibi2 in 285.0..1550.0 && abs(ibi2 - medianIbi) / medianIbi <= 0.22
            if (valid1 && valid2) {
                val diff = ibi2 - ibi1
                sumSqDiff += diff * diff
                if (abs(diff) > 50.0) nn50Count++
                successivePairs++
            }
        }

        val rmssd = if (successivePairs > 0) sqrt(sumSqDiff / successivePairs) else 0.0
        val pnn50 = if (successivePairs > 0) (nn50Count * 100.0) / successivePairs else 0.0

        val retentionRatio = validIbis.size.toDouble() / rawIbis.size.toDouble()
        val cv = (sdnn / meanIbi).coerceIn(0.0, 1.0)
        val consistency = (retentionRatio * (1.0 - (cv * 1.8).coerceIn(0.0, 0.7))).coerceIn(0.0, 1.0)

        return PeakAnalysisResult(
            bpm = bpm,
            validIbiMs = validIbis,
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            pnn50Percent = pnn50,
            ibiConsistencyScore = consistency
        )
    }

    private fun computeQualityMetrics(
        spectral: SpectralResult,
        peakResult: PeakAnalysisResult,
        perfusionIndexPercent: Double,
        motionRms: Double
    ): PpgQualityMetrics {
        val diffBpm = if (peakResult.bpm > 0.0) abs(peakResult.bpm - spectral.bpm) else 99.0

        // Component scores (0..1)
        val purityScore = ((spectral.spectralPurity - 0.18) / 0.52).coerceIn(0.0, 1.0)
        val agreementScore = (1.0 - (diffBpm / 7.5)).coerceIn(0.0, 1.0)
        val perfusionScore = ((perfusionIndexPercent - 0.08) / 0.65).coerceIn(0.0, 1.0)
        val motionScore = (1.0 - (motionRms / maxMotionRmsThreshold)).coerceIn(0.0, 1.0)
        val consistencyScore = peakResult.ibiConsistencyScore.coerceIn(0.0, 1.0)

        val weighted = (
            0.34 * purityScore +
            0.26 * agreementScore +
            0.16 * consistencyScore +
            0.12 * perfusionScore +
            0.12 * motionScore
        ) * 100.0

        // Penalize heavily if perfusion is practically flat (e.g. static surface / dead signal)
        val finalSqi = if (perfusionIndexPercent < 0.04) {
            min(weighted, 20.0).roundToInt()
        } else {
            weighted.roundToInt().coerceIn(0, 100)
        }

        return PpgQualityMetrics(
            sqiScore = finalSqi,
            spectralPurity = spectral.spectralPurity,
            perfusionIndexPercent = perfusionIndexPercent,
            timeFreqDiffBpm = diffBpm,
            motionRms = motionRms,
            validBeatCount = peakResult.validIbiMs.size
        )
    }

    private fun recentMotionRms(seconds: Double): Double {
        if (motionSamples.isEmpty() || timestampsSec.isEmpty()) return 0.0
        val cutoff = timestampsSec.last() - seconds
        var sum = 0.0
        var count = 0
        for (i in timestampsSec.lastIndex downTo 0) {
            if (timestampsSec[i] < cutoff) break
            sum += motionSamples[i]
            count++
        }
        return if (count > 0) sum / count else 0.0
    }

    private fun normalizeForDisplay(signal: DoubleArray): List<Float> {
        if (signal.isEmpty()) return emptyList()
        var maxAbs = 1e-6
        for (v in signal) {
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
        }
        return signal.map { (it / maxAbs).toFloat().coerceIn(-1f, 1f) }
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun DoubleArray.averageOrZero(): Double {
        if (isEmpty()) return 0.0
        var sum = 0.0
        for (v in this) sum += v
        return sum / size
    }
}

data class LiveAnalysisSnapshot(
    val instantBpm: Int?,
    val sqiScore: Int,
    val perfusionIndexPercent: Double,
    val waveform: List<Float>,
    val recentMotionRms: Double
)
