package com.cardiopulse.app.core.ppg

import org.junit.Assert.assertEquals
import org.junit.Test

class FingerPresenceDetectorTest {

    private fun sample(
        r: Double,
        g: Double,
        b: Double,
        std: Double = 6.0,
        sat: Double = 0.0
    ) = FrameColorSample(
        timestampNanos = 1_000_000L,
        meanRed = r,
        meanGreen = g,
        meanBlue = b,
        redStdDev = std,
        saturatedPixelRatio = sat
    )

    @Test
    fun classifiesDarkLensAsNoFinger() {
        val detector = FingerPresenceDetector()
        assertEquals(FingerContactState.NO_FINGER, detector.classifyRaw(sample(40.0, 35.0, 32.0)))
    }

    @Test
    fun classifiesProperTorchIlluminatedFingertipAsValid() {
        val detector = FingerPresenceDetector()
        assertEquals(
            FingerContactState.VALID_CONTACT,
            detector.classifyRaw(sample(r = 215.0, g = 32.0, b = 22.0, std = 7.5, sat = 0.02))
        )
    }

    @Test
    fun detectsExcessiveBlanchingPressure() {
        val detector = FingerPresenceDetector()
        assertEquals(
            FingerContactState.PRESSING_TOO_HARD,
            detector.classifyRaw(sample(r = 255.0, g = 210.0, b = 205.0, std = 1.0, sat = 0.96))
        )
    }

    @Test
    fun detectsAmbientLightLeak() {
        val detector = FingerPresenceDetector()
        assertEquals(
            FingerContactState.TOO_LIGHT_OR_AMBIENT_LEAK,
            detector.classifyRaw(sample(r = 175.0, g = 155.0, b = 150.0, std = 12.0, sat = 0.0))
        )
    }

    @Test
    fun hysteresisSurvivesSingleFrameGlitchOnceLocked() {
        val detector = FingerPresenceDetector(minValidFramesToLock = 3, maxInvalidFramesToDrop = 4)
        val valid = sample(210.0, 28.0, 20.0)
        val glitch = sample(80.0, 70.0, 65.0)

        repeat(4) { assertEquals(FingerContactState.VALID_CONTACT, detector.evaluate(valid)) }
        // Single transient glitch shouldn't drop locked state
        assertEquals(FingerContactState.VALID_CONTACT, detector.evaluate(glitch))
        // Sustained removal drops lock
        repeat(4) { detector.evaluate(glitch) }
        assertEquals(FingerContactState.NO_FINGER, detector.evaluate(glitch))
    }
}
