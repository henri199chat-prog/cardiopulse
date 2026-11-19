package com.cardiopulse.app.core.ppg

/**
 * Raw optical statistics extracted from the center ROI of a single CameraX frame.
 */
data class FrameColorSample(
    val timestampNanos: Long,
    val meanRed: Double,
    val meanGreen: Double,
    val meanBlue: Double,
    val redStdDev: Double,
    val saturatedPixelRatio: Double
)

enum class FingerContactState {
    NO_FINGER,
    TOO_LIGHT_OR_AMBIENT_LEAK,
    PRESSING_TOO_HARD,
    VALID_CONTACT
}

enum class PpgGuidanceState {
    IDLE,
    PLACE_FINGER,
    PRESSING_TOO_HARD,
    AMBIENT_LIGHT_LEAK,
    EXCESSIVE_MOTION,
    CALIBRATING,
    SIGNAL_WEAK,
    SIGNAL_GOOD,
    SIGNAL_EXCELLENT
}

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}

enum class RhythmAssessment {
    REGULAR,
    IRREGULAR_DETECTED
}

enum class MeasurementContext {
    RESTING,
    MORNING,
    EVENING,
    AFTER_EXERCISE
}

enum class PpgFailureReason {
    INSUFFICIENT_DURATION,
    EXCESSIVE_MOTION,
    LOW_SIGNAL_QUALITY,
    METHOD_DISAGREEMENT
}

data class PpgQualityMetrics(
    val sqiScore: Int,                 // 0..100 overall Signal Quality Index
    val spectralPurity: Double,        // 0..1 ratio of fundamental+harmonic energy to band energy
    val perfusionIndexPercent: Double, // AC/DC * 100
    val timeFreqDiffBpm: Double,       // |timeDomainBpm - spectralBpm|
    val motionRms: Double,             // m/s^2 from linear accelerometer
    val validBeatCount: Int
)

/**
 * Real-time UI state emitted during active PPG measurement.
 */
data class PpgLiveState(
    val isMeasuring: Boolean = false,
    val elapsedSeconds: Float = 0f,
    val targetSeconds: Float = 20f,
    val progress: Float = 0f,
    val instantBpm: Int? = null,
    val sqiScore: Int = 0,
    val contactState: FingerContactState = FingerContactState.NO_FINGER,
    val guidance: PpgGuidanceState = PpgGuidanceState.IDLE,
    val motionRms: Double = 0.0,
    val perfusionIndexPercent: Double = 0.0,
    val waveformWindow: List<Float> = emptyList(),
    val meanRedPreview: Int = 0
)

/**
 * Final outcome of a completed PPG session — either a verified high-integrity
 * measurement or an explicit refusal to prevent inaccurate readings.
 */
sealed class PpgOutcome {
    data class Verified(
        val bpm: Int,
        val timeDomainBpm: Double,
        val spectralBpm: Double,
        val hrvRmssdMs: Double,
        val hrvSdnnMs: Double,
        val pnn50Percent: Double,
        val sqiScore: Int,
        val perfusionIndexPercent: Double,
        val confidence: ConfidenceLevel,
        val rhythmAssessment: RhythmAssessment,
        val durationSeconds: Int,
        val validBeatCount: Int,
        val ibiValuesMs: List<Double>
    ) : PpgOutcome()

    data class Rejected(
        val reason: PpgFailureReason,
        val sqiScore: Int,
        val motionRms: Double,
        val detailMessage: String
    ) : PpgOutcome()
}
