package com.cardiopulse.app.core.export

import android.content.Context
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports blood pressure and heart rate logs as UTF-8 BOM CSV files so Arabic & French
 * notes render cleanly when opened in Microsoft Excel or Google Sheets.
 */
object CsvExporter {

    fun exportToCsv(
        context: Context,
        bpRecords: List<BloodPressureRecordEntity>,
        hrRecords: List<HeartRateRecordEntity>
    ): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(reportsDir, "CardioPulse_Export_$stamp.csv")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val sb = StringBuilder()
        // UTF-8 BOM for Excel Arabic/French compatibility
        sb.append('\uFEFF')
        sb.appendLine("Type,DateTime,Systolic_mmHg,Diastolic_mmHg,MAP_mmHg,Pulse_BPM,HRV_RMSSD_ms,SQI_Percent,Source,Arm,Posture,Warnings,Notes")

        for (bp in bpRecords) {
            val warnings = buildList {
                if (bp.bodyMovementWarning) add("MOVEMENT")
                if (bp.cuffFitWarning) add("CUFF_FIT")
                if (bp.irregularPulseWarning) add("IRREGULAR_PULSE")
            }.joinToString(";")
            sb.appendLine(
                listOf(
                    "BLOOD_PRESSURE",
                    isoFmt.format(Date(bp.timestampMillis)),
                    bp.systolicMmHg.toString(),
                    bp.diastolicMmHg.toString(),
                    bp.mapMmHg.toString(),
                    bp.pulseBpm?.toString().orEmpty(),
                    "",
                    "",
                    escapeCsv(bp.source),
                    escapeCsv(bp.arm),
                    escapeCsv(bp.posture),
                    escapeCsv(warnings),
                    escapeCsv(bp.notes)
                ).joinToString(",")
            )
        }

        for (hr in hrRecords) {
            val warnings = if (hr.rhythmIrregularFlag) "IRREGULAR_RHYTHM" else ""
            sb.appendLine(
                listOf(
                    "HEART_RATE",
                    isoFmt.format(Date(hr.timestampMillis)),
                    "",
                    "",
                    "",
                    hr.bpm.toString(),
                    "%.1f".format(Locale.US, hr.hrvRmssdMs),
                    hr.sqiScore.toString(),
                    escapeCsv(hr.source),
                    "",
                    escapeCsv(hr.context),
                    escapeCsv(warnings),
                    escapeCsv(hr.notes)
                ).joinToString(",")
            )
        }

        outFile.writeText(sb.toString(), Charsets.UTF_8)
        return outFile
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\"", "\"\"").replace("\n", " ")
        return if (clean.contains(",") || clean.contains("\"")) "\"$clean\"" else clean
    }
}
