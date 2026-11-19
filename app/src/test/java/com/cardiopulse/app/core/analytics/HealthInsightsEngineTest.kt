package com.cardiopulse.app.core.analytics

import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HealthInsightsEngineTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun epochAt(dayOffset: Int, hour: Int): Long {
        val cal = Calendar.getInstance(utc).apply {
            set(2026, Calendar.NOVEMBER, 18 - dayOffset, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun bp(ts: Long, sys: Int, dia: Int, irregular: Boolean = false) = BloodPressureRecordEntity(
        timestampMillis = ts,
        systolicMmHg = sys,
        diastolicMmHg = dia,
        pulseBpm = 70,
        mapMmHg = dia + (sys - dia) / 3,
        pulsePressureMmHg = sys - dia,
        source = "BLE_CUFF",
        arm = "LEFT",
        posture = "SEATED",
        irregularPulseWarning = irregular
    )

    private fun hr(ts: Long, bpm: Int, context: String = "RESTING", irregular: Boolean = false) =
        HeartRateRecordEntity(
            timestampMillis = ts,
            bpm = bpm,
            hrvRmssdMs = 45.0,
            hrvSdnnMs = 52.0,
            pnn50Percent = 20.0,
            sqiScore = 90,
            perfusionIndexPercent = 0.85,
            confidenceLevel = "HIGH",
            rhythmIrregularFlag = irregular,
            durationSeconds = 20,
            source = "CAMERA_PPG",
            context = context
        )

    @Test
    fun detectsMorningSurgeWidePulsePressureAndIrregularFlags() {
        val now = epochAt(0, 21)
        val bps = listOf(
            bp(epochAt(1, 8), 156, 82),  // Morning high, PP=74
            bp(epochAt(0, 9), 152, 80, irregular = true), // Morning high, PP=72
            bp(epochAt(1, 20), 130, 78), // Evening lower
            bp(epochAt(0, 20), 128, 76)  // Evening lower
        )
        val hrs = listOf(
            hr(epochAt(1, 10), 106, context = "RESTING"),
            hr(epochAt(0, 10), 104, context = "RESTING", irregular = true),
            hr(epochAt(0, 18), 145, context = "AFTER_EXERCISE") // Should not skew resting HR check
        )

        val summary = HealthInsightsEngine.analyze(
            bpRecords = bps,
            hrRecords = hrs,
            guideline = BpGuideline.ESC_ESH,
            nowMillis = now,
            timeZone = utc
        )

        assertNotNull(summary.morningBpAvg)
        assertNotNull(summary.eveningBpAvg)
        assertEquals(154, summary.morningBpAvg!!.systolic)
        assertEquals(129, summary.eveningBpAvg!!.systolic)

        assertTrue(summary.insights.any { it is ClinicalInsight.MorningSurgeDetected })
        assertTrue(summary.insights.any { it is ClinicalInsight.BpElevatedAverage })
        assertTrue(summary.insights.any { it is ClinicalInsight.WidePulsePressure })
        assertTrue(summary.insights.any { it is ClinicalInsight.RestingTachycardia })

        val irregularInsight = summary.insights.filterIsInstance<ClinicalInsight.IrregularRhythmFlags>().firstOrNull()
        assertNotNull(irregularInsight)
        assertEquals(2, irregularInsight!!.count)
    }

    @Test
    fun reportsControlledBpWhenReadingsStayInTarget() {
        val now = epochAt(0, 21)
        val bps = listOf(
            bp(epochAt(2, 8), 118, 76),
            bp(epochAt(1, 20), 122, 78),
            bp(epochAt(0, 8), 120, 75),
            bp(epochAt(0, 19), 116, 74)
        )

        val summary = HealthInsightsEngine.analyze(
            bpRecords = bps,
            hrRecords = emptyList(),
            guideline = BpGuideline.ESC_ESH,
            nowMillis = now,
            timeZone = utc
        )

        assertEquals(100, summary.bpTargetCompliancePercent)
        assertTrue(summary.insights.any { it is ClinicalInsight.BpControlled })
    }
}
