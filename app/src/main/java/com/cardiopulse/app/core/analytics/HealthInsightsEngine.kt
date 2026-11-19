package com.cardiopulse.app.core.analytics

import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class InsightSeverity {
    POSITIVE,
    INFO,
    WARNING,
    ALERT
}

sealed class ClinicalInsight {
    abstract val severity: InsightSeverity

    data class BpControlled(override val severity: InsightSeverity = InsightSeverity.POSITIVE) : ClinicalInsight()
    data class BpElevatedAverage(
        val avgSys: Int,
        val avgDia: Int,
        override val severity: InsightSeverity = InsightSeverity.WARNING
    ) : ClinicalInsight()
    data class MorningSurgeDetected(
        val deltaSysMmHg: Int,
        override val severity: InsightSeverity = InsightSeverity.WARNING
    ) : ClinicalInsight()
    data class WidePulsePressure(
        val avgPulsePressure: Int,
        override val severity: InsightSeverity = InsightSeverity.WARNING
    ) : ClinicalInsight()
    data class RestingTachycardia(
        val avgBpm: Int,
        override val severity: InsightSeverity = InsightSeverity.WARNING
    ) : ClinicalInsight()
    data class RestingBradycardia(
        val avgBpm: Int,
        override val severity: InsightSeverity = InsightSeverity.INFO
    ) : ClinicalInsight()
    data class IrregularRhythmFlags(
        val count: Int,
        override val severity: InsightSeverity = InsightSeverity.ALERT
    ) : ClinicalInsight()
    data class NeedMoreData(override val severity: InsightSeverity = InsightSeverity.INFO) : ClinicalInsight()
}

data class BpPairAverage(
    val systolic: Int,
    val diastolic: Int,
    val count: Int
)

data class CardioAnalyticsSummary(
    val sevenDayBpAvg: BpPairAverage?,
    val morningBpAvg: BpPairAverage?,
    val eveningBpAvg: BpPairAverage?,
    val bpTargetCompliancePercent: Int?,
    val bpSystolicVariabilitySd: Double?,
    val sevenDayHrAvg: Int?,
    val sevenDayHrvRmssdAvg: Double?,
    val insights: List<ClinicalInsight>
)

/**
 * Computes clinical cardiovascular metrics and actionable insights from local
 * heart rate and blood pressure history (pure JVM logic for 100% unit testability).
 */
object HealthInsightsEngine {

    private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

    fun analyze(
        bpRecords: List<BloodPressureRecordEntity>,
        hrRecords: List<HeartRateRecordEntity>,
        guideline: BpGuideline = BpGuideline.ESC_ESH,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): CardioAnalyticsSummary {
        val cutoff7d = nowMillis - SEVEN_DAYS_MS
        val recentBp = bpRecords.filter { it.timestampMillis >= cutoff7d }
        val recentHr = hrRecords.filter { it.timestampMillis >= cutoff7d }

        val sevenDayBpAvg = if (recentBp.isNotEmpty()) {
            BpPairAverage(
                systolic = recentBp.map { it.systolicMmHg }.average().roundToInt(),
                diastolic = recentBp.map { it.diastolicMmHg }.average().roundToInt(),
                count = recentBp.size
            )
        } else null

        val morningRecords = recentBp.filter { isMorningHour(it.timestampMillis, timeZone) }
        val eveningRecords = recentBp.filter { isEveningHour(it.timestampMillis, timeZone) }

        val morningAvg = if (morningRecords.isNotEmpty()) {
            BpPairAverage(
                systolic = morningRecords.map { it.systolicMmHg }.average().roundToInt(),
                diastolic = morningRecords.map { it.diastolicMmHg }.average().roundToInt(),
                count = morningRecords.size
            )
        } else null

        val eveningAvg = if (eveningRecords.isNotEmpty()) {
            BpPairAverage(
                systolic = eveningRecords.map { it.systolicMmHg }.average().roundToInt(),
                diastolic = eveningRecords.map { it.diastolicMmHg }.average().roundToInt(),
                count = eveningRecords.size
            )
        } else null

        // Home BP target: < 135/85 mmHg (ESC/ESH home monitoring threshold) or < 130/80 (AHA)
        val sysTarget = if (guideline == BpGuideline.AHA_ACC) 130 else 135
        val diaTarget = if (guideline == BpGuideline.AHA_ACC) 80 else 85

        val compliancePercent = if (recentBp.isNotEmpty()) {
            val inTarget = recentBp.count {
                it.systolicMmHg in 90 until sysTarget && it.diastolicMmHg in 60 until diaTarget
            }
            ((inTarget * 100.0) / recentBp.size).roundToInt()
        } else null

        val sysSd = if (recentBp.size >= 3) {
            val mean = recentBp.map { it.systolicMmHg.toDouble() }.average()
            val variance = recentBp.sumOf { (it.systolicMmHg - mean).pow(2) } / (recentBp.size - 1)
            sqrt(variance)
        } else null

        val sevenDayHrAvg = if (recentHr.isNotEmpty()) {
            recentHr.map { it.bpm }.average().roundToInt()
        } else null

        val validHrv = recentHr.filter { it.hrvRmssdMs > 0.0 }
        val sevenDayHrvAvg = if (validHrv.isNotEmpty()) {
            validHrv.map { it.hrvRmssdMs }.average()
        } else null

        val insights = ArrayList<ClinicalInsight>()

        // 1. Irregular rhythm warnings across both BLE BP monitors and PPG HR captures
        val irregularCount = recentHr.count { it.rhythmIrregularFlag } +
            recentBp.count { it.irregularPulseWarning }
        if (irregularCount > 0) {
            insights.add(ClinicalInsight.IrregularRhythmFlags(irregularCount))
        }

        // 2. 7-day BP elevation vs control
        if (sevenDayBpAvg != null) {
            if (sevenDayBpAvg.systolic >= sysTarget || sevenDayBpAvg.diastolic >= diaTarget) {
                insights.add(
                    ClinicalInsight.BpElevatedAverage(
                        avgSys = sevenDayBpAvg.systolic,
                        avgDia = sevenDayBpAvg.diastolic
                    )
                )
            } else if ((compliancePercent ?: 0) >= 75) {
                insights.add(ClinicalInsight.BpControlled())
            }
        }

        // 3. Morning BP surge detection (Morning SYS >= Evening SYS + 15 mmHg)
        if (morningAvg != null && eveningAvg != null && morningAvg.count >= 2 && eveningAvg.count >= 2) {
            val delta = morningAvg.systolic - eveningAvg.systolic
            if (delta >= 15) {
                insights.add(ClinicalInsight.MorningSurgeDetected(delta))
            }
        }

        // 4. Wide pulse pressure detection (average PP > 60 mmHg)
        if (recentBp.size >= 2) {
            val avgPp = recentBp.map { it.pulsePressureMmHg }.average().roundToInt()
            if (avgPp > 60) {
                insights.add(ClinicalInsight.WidePulsePressure(avgPp))
            }
        }

        // 5. Resting heart rate anomalies (exclude AFTER_EXERCISE)
        val restingHr = recentHr.filter { it.context != "AFTER_EXERCISE" }
        if (restingHr.size >= 2) {
            val avgRestBpm = restingHr.map { it.bpm }.average().roundToInt()
            if (avgRestBpm > 100) {
                insights.add(ClinicalInsight.RestingTachycardia(avgRestBpm))
            } else if (avgRestBpm < 50) {
                insights.add(ClinicalInsight.RestingBradycardia(avgRestBpm))
            }
        }

        if (insights.isEmpty()) {
            insights.add(ClinicalInsight.NeedMoreData())
        }

        return CardioAnalyticsSummary(
            sevenDayBpAvg = sevenDayBpAvg,
            morningBpAvg = morningAvg,
            eveningBpAvg = eveningAvg,
            bpTargetCompliancePercent = compliancePercent,
            bpSystolicVariabilitySd = sysSd,
            sevenDayHrAvg = sevenDayHrAvg,
            sevenDayHrvRmssdAvg = sevenDayHrvAvg,
            insights = insights
        )
    }

    private fun isMorningHour(timestampMillis: Long, timeZone: TimeZone): Boolean {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in 5..11
    }

    private fun isEveningHour(timestampMillis: Long, timeZone: TimeZone): Boolean {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in 17..23
    }
}
