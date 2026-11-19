package com.cardiopulse.app.core.bp

enum class BpGuideline {
    ESC_ESH,
    AHA_ACC
}

enum class ClinicalBpCategory {
    HYPOTENSION,
    OPTIMAL,
    NORMAL,
    HIGH_NORMAL,
    ELEVATED,
    GRADE_1,
    GRADE_2,
    GRADE_3,
    ISOLATED_SYSTOLIC,
    HYPERTENSIVE_CRISIS
}

enum class BpSeverity {
    LOW,
    OPTIMAL,
    NORMAL,
    WARNING,
    HIGH,
    CRITICAL
}

enum class BpMeasurementArm {
    LEFT,
    RIGHT
}

enum class BpMeasurementPosture {
    SEATED,
    LYING,
    STANDING
}

enum class DataSource {
    CAMERA_PPG,
    BLE_CUFF,
    BLE_HR,
    MANUAL
}

data class BleBpStatusFlags(
    val bodyMovementDetected: Boolean = false,
    val cuffTooLoose: Boolean = false,
    val irregularPulseDetected: Boolean = false,
    val pulseRateOutOfRange: Boolean = false,
    val improperPosition: Boolean = false
) {
    val hasAnyWarning: Boolean
        get() = bodyMovementDetected || cuffTooLoose || irregularPulseDetected || improperPosition
}

data class ParsedBleBloodPressure(
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val meanArterialPressureMmHg: Int?,
    val pulseBpm: Int?,
    val timestampEpochMillis: Long?,
    val userId: Int?,
    val statusFlags: BleBpStatusFlags,
    val originalUnitWasKpa: Boolean
)

data class ParsedBleHeartRate(
    val bpm: Int,
    val rrIntervalsMs: List<Double>
)

data class BpClassificationResult(
    val category: ClinicalBpCategory,
    val severity: BpSeverity,
    val mapMmHg: Int,
    val pulsePressureMmHg: Int,
    val isEmergencyCrisis: Boolean,
    val isHypotension: Boolean,
    val isWidePulsePressure: Boolean
)

sealed class BpValidationResult {
    data class Valid(val classification: BpClassificationResult) : BpValidationResult()
    data class Invalid(val error: BpValidationError) : BpValidationResult()
}

enum class BpValidationError {
    OUT_OF_PHYSIOLOGICAL_RANGE,
    DIASTOLIC_TOO_CLOSE_OR_ABOVE_SYSTOLIC
}

data class BleDiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val advertisesBpService: Boolean,
    val advertisesHrService: Boolean
)

enum class BleConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED_DISCOVERING,
    READY_FOR_MEASUREMENT,
    DISCONNECTED,
    ERROR
}
