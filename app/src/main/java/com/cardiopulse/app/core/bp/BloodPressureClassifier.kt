package com.cardiopulse.app.core.bp

import kotlin.math.roundToInt

/**
 * Clinical blood pressure validator and classifier supporting both
 * ESC/ESH (European Society of Cardiology / Hypertension) and AHA/ACC guidelines.
 */
object BloodPressureClassifier {

    /**
     * Validates physiological bounds and computes clinical classification, MAP, and Pulse Pressure.
     */
    fun validateAndClassify(
        systolicMmHg: Int,
        diastolicMmHg: Int,
        guideline: BpGuideline = BpGuideline.ESC_ESH
    ): BpValidationResult {
        if (systolicMmHg !in 50..300 || diastolicMmHg !in 30..200) {
            return BpValidationResult.Invalid(BpValidationError.OUT_OF_PHYSIOLOGICAL_RANGE)
        }
        if (systolicMmHg - diastolicMmHg < 10) {
            return BpValidationResult.Invalid(BpValidationError.DIASTOLIC_TOO_CLOSE_OR_ABOVE_SYSTOLIC)
        }

        val classification = classify(systolicMmHg, diastolicMmHg, guideline)
        return BpValidationResult.Valid(classification)
    }

    fun classify(
        systolicMmHg: Int,
        diastolicMmHg: Int,
        guideline: BpGuideline = BpGuideline.ESC_ESH
    ): BpClassificationResult {
        val map = computeMap(systolicMmHg, diastolicMmHg)
        val pp = systolicMmHg - diastolicMmHg
        val isCrisis = systolicMmHg >= 180 || diastolicMmHg >= 120
        val isWidePp = pp > 60

        val category = when (guideline) {
            BpGuideline.ESC_ESH -> classifyEscEsh(systolicMmHg, diastolicMmHg)
            BpGuideline.AHA_ACC -> classifyAhaAcc(systolicMmHg, diastolicMmHg)
        }

        val severity = when (category) {
            ClinicalBpCategory.HYPOTENSION -> BpSeverity.LOW
            ClinicalBpCategory.OPTIMAL -> BpSeverity.OPTIMAL
            ClinicalBpCategory.NORMAL -> BpSeverity.NORMAL
            ClinicalBpCategory.HIGH_NORMAL,
            ClinicalBpCategory.ELEVATED -> BpSeverity.WARNING
            ClinicalBpCategory.GRADE_1,
            ClinicalBpCategory.ISOLATED_SYSTOLIC -> BpSeverity.HIGH
            ClinicalBpCategory.GRADE_2,
            ClinicalBpCategory.GRADE_3,
            ClinicalBpCategory.HYPERTENSIVE_CRISIS -> BpSeverity.CRITICAL
        }

        return BpClassificationResult(
            category = category,
            severity = severity,
            mapMmHg = map,
            pulsePressureMmHg = pp,
            isEmergencyCrisis = isCrisis,
            isHypotension = category == ClinicalBpCategory.HYPOTENSION,
            isWidePulsePressure = isWidePp
        )
    }

    private fun classifyEscEsh(sys: Int, dia: Int): ClinicalBpCategory {
        // Emergency crisis threshold always takes highest priority
        if (sys >= 180 || dia >= 120) return ClinicalBpCategory.HYPERTENSIVE_CRISIS
        if (dia >= 110) return ClinicalBpCategory.GRADE_3

        // Isolated Systolic Hypertension (common in older adults): SYS >= 140 and DIA < 90
        if (sys >= 140 && dia < 90) return ClinicalBpCategory.ISOLATED_SYSTOLIC

        if (sys >= 160 || dia >= 100) return ClinicalBpCategory.GRADE_2
        if (sys >= 140 || dia >= 90) return ClinicalBpCategory.GRADE_1
        if (sys >= 130 || dia >= 85) return ClinicalBpCategory.HIGH_NORMAL
        if (sys >= 120 || dia >= 80) return ClinicalBpCategory.NORMAL
        if (sys < 90 || dia < 60) return ClinicalBpCategory.HYPOTENSION
        return ClinicalBpCategory.OPTIMAL
    }

    private fun classifyAhaAcc(sys: Int, dia: Int): ClinicalBpCategory {
        if (sys >= 180 || dia >= 120) return ClinicalBpCategory.HYPERTENSIVE_CRISIS
        if (sys >= 140 || dia >= 90) return ClinicalBpCategory.GRADE_2
        if (sys >= 130 || dia >= 80) return ClinicalBpCategory.GRADE_1
        if (sys in 120..129 && dia < 80) return ClinicalBpCategory.ELEVATED
        if (sys < 90 || dia < 60) return ClinicalBpCategory.HYPOTENSION
        return ClinicalBpCategory.NORMAL
    }

    /**
     * Mean Arterial Pressure (MAP) = DBP + (SBP - DBP) / 3.
     */
    fun computeMap(systolicMmHg: Int, diastolicMmHg: Int): Int {
        return (diastolicMmHg + (systolicMmHg - diastolicMmHg) / 3.0).roundToInt()
    }

    /**
     * Computes the clinical average of two consecutive readings spaced 1–2 minutes apart
     * per ESC/ESH home blood pressure monitoring protocol.
     */
    fun averageTwoReadings(
        sys1: Int,
        dia1: Int,
        pulse1: Int?,
        sys2: Int,
        dia2: Int,
        pulse2: Int?
    ): Triple<Int, Int, Int?> {
        val avgSys = ((sys1 + sys2) / 2.0).roundToInt()
        val avgDia = ((dia1 + dia2) / 2.0).roundToInt()
        val avgPulse = when {
            pulse1 != null && pulse2 != null -> ((pulse1 + pulse2) / 2.0).roundToInt()
            pulse1 != null -> pulse1
            pulse2 != null -> pulse2
            else -> null
        }
        return Triple(avgSys, avgDia, avgPulse)
    }
}
