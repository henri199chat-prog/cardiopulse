package com.cardiopulse.app.core.ppg

/**
 * Optical fingertip contact validator with debounce hysteresis.
 *
 * Ensures PPG processing only runs when a fingertip properly covers the rear
 * camera and LED torch — rejecting open-air frames, dark pockets, ambient light
 * leaks, and excessive pressing that blanches capillaries.
 */
class FingerPresenceDetector(
    private val minValidFramesToLock: Int = 5,
    private val maxInvalidFramesToDrop: Int = 6
) {
    private var consecutiveValidFrames = 0
    private var consecutiveInvalidFrames = 0
    private var isLocked = false

    fun reset() {
        consecutiveValidFrames = 0
        consecutiveInvalidFrames = 0
        isLocked = false
    }

    /**
     * Classifies a single frame and updates contact lock with hysteresis.
     */
    fun evaluate(sample: FrameColorSample): FingerContactState {
        val rawState = classifyRaw(sample)
        if (rawState == FingerContactState.VALID_CONTACT) {
            consecutiveValidFrames++
            consecutiveInvalidFrames = 0
            if (consecutiveValidFrames >= minValidFramesToLock) {
                isLocked = true
            }
            return FingerContactState.VALID_CONTACT
        } else {
            consecutiveInvalidFrames++
            consecutiveValidFrames = 0
            if (isLocked && consecutiveInvalidFrames < maxInvalidFramesToDrop) {
                // Brief single-frame optical glitch while locked; preserve continuity
                return FingerContactState.VALID_CONTACT
            }
            isLocked = false
            return rawState
        }
    }

    /**
     * Stateless optical physics classifier for a single frame ROI.
     */
    fun classifyRaw(sample: FrameColorSample): FingerContactState {
        val r = sample.meanRed
        val g = sample.meanGreen
        val b = sample.meanBlue

        // 1. Dark / uncovered / no torch reflection
        if (r < 120.0) {
            return FingerContactState.NO_FINGER
        }

        // 2. Check excessive saturation / pressing too hard (blanching capillaries or clipping sensor)
        // When pressed too hard or white flash bleeds directly, >90% pixels clip at 255
        // and red dominance collapses or green/blue saturate alongside red.
        if (sample.saturatedPixelRatio > 0.92 && g > 175.0 && b > 175.0) {
            return FingerContactState.PRESSING_TOO_HARD
        }

        // 3. Ambient light / background scene check:
        // Fingertip illuminated by LED flash strongly transmits red (>600nm) while
        // hemoglobin absorbs green & blue heavily, yielding high R/G and R/B ratios
        // and low spatial texture variance.
        val rgRatio = r / (g + 1.0)
        val rbRatio = r / (b + 1.0)

        if (rgRatio < 1.35 || rbRatio < 1.45) {
            return if (sample.redStdDev > 45.0) {
                FingerContactState.NO_FINGER
            } else {
                FingerContactState.TOO_LIGHT_OR_AMBIENT_LEAK
            }
        }

        // 4. High spatial variance indicates non-uniform background (camera pointing at room)
        if (sample.redStdDev > 55.0) {
            return FingerContactState.NO_FINGER
        }

        // 5. Severe clipping of red channel with almost zero dynamic range (hard press on lens)
        if (sample.saturatedPixelRatio > 0.96 && sample.redStdDev < 1.2 && r > 253.5) {
            return FingerContactState.PRESSING_TOO_HARD
        }

        return FingerContactState.VALID_CONTACT
    }
}
