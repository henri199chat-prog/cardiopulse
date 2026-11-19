package com.cardiopulse.app.core.bp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressureClassifierTest {

    @Test
    fun classifiesEscEshCategoriesCorrectly() {
        assertEquals(ClinicalBpCategory.HYPOTENSION, BloodPressureClassifier.classify(85, 55, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.OPTIMAL, BloodPressureClassifier.classify(115, 75, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.NORMAL, BloodPressureClassifier.classify(125, 82, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.HIGH_NORMAL, BloodPressureClassifier.classify(134, 86, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.GRADE_1, BloodPressureClassifier.classify(145, 92, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.GRADE_2, BloodPressureClassifier.classify(165, 102, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.GRADE_3, BloodPressureClassifier.classify(172, 112, BpGuideline.ESC_ESH).category)
        assertEquals(ClinicalBpCategory.ISOLATED_SYSTOLIC, BloodPressureClassifier.classify(152, 78, BpGuideline.ESC_ESH).category)
        // High systolic with low diastolic must NOT be misclassified as hypotension
        assertEquals(ClinicalBpCategory.ISOLATED_SYSTOLIC, BloodPressureClassifier.classify(150, 55, BpGuideline.ESC_ESH).category)

        val crisis = BloodPressureClassifier.classify(185, 122, BpGuideline.ESC_ESH)
        assertEquals(ClinicalBpCategory.HYPERTENSIVE_CRISIS, crisis.category)
        assertTrue(crisis.isEmergencyCrisis)
    }

    @Test
    fun classifiesAhaAccCategoriesCorrectly() {
        assertEquals(ClinicalBpCategory.NORMAL, BloodPressureClassifier.classify(116, 74, BpGuideline.AHA_ACC).category)
        assertEquals(ClinicalBpCategory.ELEVATED, BloodPressureClassifier.classify(124, 76, BpGuideline.AHA_ACC).category)
        assertEquals(ClinicalBpCategory.GRADE_1, BloodPressureClassifier.classify(134, 84, BpGuideline.AHA_ACC).category)
        assertEquals(ClinicalBpCategory.GRADE_2, BloodPressureClassifier.classify(145, 92, BpGuideline.AHA_ACC).category)
        assertTrue(BloodPressureClassifier.classify(182, 115, BpGuideline.AHA_ACC).isEmergencyCrisis)
    }

    @Test
    fun rejectsPhysiologicallyImpossibleInputs() {
        val inverted = BloodPressureClassifier.validateAndClassify(80, 120)
        assertTrue(inverted is BpValidationResult.Invalid)
        assertEquals(BpValidationError.DIASTOLIC_TOO_CLOSE_OR_ABOVE_SYSTOLIC, (inverted as BpValidationResult.Invalid).error)

        val tooClose = BloodPressureClassifier.validateAndClassify(115, 110)
        assertTrue(tooClose is BpValidationResult.Invalid)

        val outOfRange = BloodPressureClassifier.validateAndClassify(320, 150)
        assertTrue(outOfRange is BpValidationResult.Invalid)
        assertEquals(BpValidationError.OUT_OF_PHYSIOLOGICAL_RANGE, (outOfRange as BpValidationResult.Invalid).error)
    }

    @Test
    fun computesMapPulsePressureAndTwoReadingAverage() {
        val res = BloodPressureClassifier.classify(120, 80)
        assertEquals(93, res.mapMmHg)
        assertEquals(40, res.pulsePressureMmHg)
        assertFalse(res.isWidePulsePressure)

        val wide = BloodPressureClassifier.classify(160, 85)
        assertEquals(75, wide.pulsePressureMmHg)
        assertTrue(wide.isWidePulsePressure)

        val (avgSys, avgDia, avgPulse) = BloodPressureClassifier.averageTwoReadings(
            134, 84, 72,
            128, 80, 68
        )
        assertEquals(131, avgSys)
        assertEquals(82, avgDia)
        assertEquals(70, avgPulse)
    }
}
