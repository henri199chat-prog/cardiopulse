package com.cardiopulse.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.cardiopulse.app.R
import com.cardiopulse.app.core.analytics.ClinicalInsight
import com.cardiopulse.app.core.analytics.InsightSeverity
import com.cardiopulse.app.core.bp.BpSeverity
import com.cardiopulse.app.core.bp.ClinicalBpCategory
import com.cardiopulse.app.core.bp.DataSource
import com.cardiopulse.app.core.ppg.MeasurementContext
import com.cardiopulse.app.ui.theme.StatusCritical
import com.cardiopulse.app.ui.theme.StatusHigh
import com.cardiopulse.app.ui.theme.StatusLow
import com.cardiopulse.app.ui.theme.StatusNormal
import com.cardiopulse.app.ui.theme.StatusOptimal
import com.cardiopulse.app.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@StringRes
fun ClinicalBpCategory.labelRes(): Int = when (this) {
    ClinicalBpCategory.HYPOTENSION -> R.string.cat_hypotension
    ClinicalBpCategory.OPTIMAL -> R.string.cat_optimal
    ClinicalBpCategory.NORMAL -> R.string.cat_normal
    ClinicalBpCategory.HIGH_NORMAL -> R.string.cat_high_normal
    ClinicalBpCategory.ELEVATED -> R.string.cat_elevated
    ClinicalBpCategory.GRADE_1 -> R.string.cat_grade1
    ClinicalBpCategory.GRADE_2 -> R.string.cat_grade2
    ClinicalBpCategory.GRADE_3 -> R.string.cat_grade3
    ClinicalBpCategory.ISOLATED_SYSTOLIC -> R.string.cat_isolated_sys
    ClinicalBpCategory.HYPERTENSIVE_CRISIS -> R.string.cat_crisis
}

fun BpSeverity.toColor(): Color = when (this) {
    BpSeverity.LOW -> StatusLow
    BpSeverity.OPTIMAL -> StatusOptimal
    BpSeverity.NORMAL -> StatusNormal
    BpSeverity.WARNING -> StatusWarning
    BpSeverity.HIGH -> StatusHigh
    BpSeverity.CRITICAL -> StatusCritical
}

fun InsightSeverity.toColor(): Color = when (this) {
    InsightSeverity.POSITIVE -> StatusOptimal
    InsightSeverity.INFO -> StatusLow
    InsightSeverity.WARNING -> StatusWarning
    InsightSeverity.ALERT -> StatusCritical
}

@Composable
fun ClinicalInsight.localizedText(): String = when (this) {
    is ClinicalInsight.BpControlled -> stringResource(R.string.insight_bp_controlled)
    is ClinicalInsight.BpElevatedAverage -> stringResource(R.string.insight_bp_elevated, avgSys, avgDia)
    is ClinicalInsight.MorningSurgeDetected -> stringResource(R.string.insight_morning_surge, deltaSysMmHg)
    is ClinicalInsight.WidePulsePressure -> stringResource(R.string.insight_wide_pp, avgPulsePressure)
    is ClinicalInsight.RestingTachycardia -> stringResource(R.string.insight_hr_tachy, avgBpm)
    is ClinicalInsight.RestingBradycardia -> stringResource(R.string.insight_hr_brady, avgBpm)
    is ClinicalInsight.IrregularRhythmFlags -> stringResource(R.string.insight_irregular_detected, count)
    is ClinicalInsight.NeedMoreData -> stringResource(R.string.insight_need_more_data)
}

@StringRes
fun sourceLabelRes(sourceName: String): Int = when (sourceName) {
    DataSource.CAMERA_PPG.name -> R.string.source_camera
    DataSource.BLE_CUFF.name, DataSource.BLE_HR.name -> R.string.source_ble
    else -> R.string.source_manual
}

@StringRes
fun MeasurementContext.labelRes(): Int = when (this) {
    MeasurementContext.RESTING -> R.string.context_resting
    MeasurementContext.MORNING -> R.string.context_morning
    MeasurementContext.EVENING -> R.string.context_evening
    MeasurementContext.AFTER_EXERCISE -> R.string.context_after_exercise
}

fun formatTimestamp(epochMillis: Long): String {
    val sdf = SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}
