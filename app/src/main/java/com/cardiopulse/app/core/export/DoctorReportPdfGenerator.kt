package com.cardiopulse.app.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.cardiopulse.app.core.analytics.CardioAnalyticsSummary
import com.cardiopulse.app.core.bp.BloodPressureClassifier
import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import com.cardiopulse.app.data.preferences.AppLanguage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a clean, printable A4 Cardiology Summary PDF report using Android's
 * native PdfDocument API — ready to share directly with a physician.
 */
object DoctorReportPdfGenerator {

    private const val PAGE_WIDTH = 595  // A4 at 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f

    fun generateReport(
        context: Context,
        patientName: String,
        language: AppLanguage,
        guideline: BpGuideline,
        summary: CardioAnalyticsSummary,
        bpRecords: List<BloodPressureRecordEntity>,
        hrRecords: List<HeartRateRecordEntity>
    ): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(reportsDir, "CardioPulse_Report_$fileStamp.pdf")

        val isFrench = language == AppLanguage.FRENCH
        val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        drawPageContent(
            canvas = canvas,
            isFrench = isFrench,
            patientName = patientName,
            guideline = guideline,
            summary = summary,
            bpRecords = bpRecords.take(18),
            hrRecords = hrRecords.take(8),
            dateFmt = dateFmt
        )

        document.finishPage(page)

        FileOutputStream(outFile).use { fos ->
            document.writeTo(fos)
        }
        document.close()
        return outFile
    }

    private fun drawPageContent(
        canvas: Canvas,
        isFrench: Boolean,
        patientName: String,
        guideline: BpGuideline,
        summary: CardioAnalyticsSummary,
        bpRecords: List<BloodPressureRecordEntity>,
        hrRecords: List<HeartRateRecordEntity>,
        dateFmt: SimpleDateFormat
    ) {
        val headerBg = Paint().apply { color = Color.parseColor("#0B132B") }
        val whiteTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val whiteSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 10f
        }
        val sectionTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 9.5f
        }
        val boldBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F5F9") }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 0.8f
        }

        // Top Clinical Header Banner
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 84f, headerBg)
        val title = if (isFrench) {
            "CardioPulse — Rapport Médical Tension & Pouls"
        } else {
            "CardioPulse — Medical Cardiology Report (تقرير طبي للضغط والنبض)"
        }
        canvas.drawText(title, MARGIN, 36f, whiteTitle)

        val generatedDate = dateFmt.format(Date())
        val patientDisplay = patientName.ifBlank { if (isFrench) "Non spécifié" else "Patient" }
        val guidelineLabel = if (guideline == BpGuideline.ESC_ESH) "ESC/ESH" else "AHA/ACC"
        canvas.drawText(
            "Patient: $patientDisplay   |   Date: $generatedDate   |   Standard: $guidelineLabel",
            MARGIN,
            60f,
            whiteSub
        )

        var y = 106f

        // 4 Summary KPI Boxes
        val boxW = (PAGE_WIDTH - 2 * MARGIN - 18f) / 4f
        val boxH = 58f
        val kpis = listOf(
            (if (isFrench) "Tension Moy. 7j" else "7-Day Avg BP") to
                (summary.sevenDayBpAvg?.let { "${it.systolic}/${it.diastolic} mmHg" } ?: "--"),
            (if (isFrench) "Matin / Soir" else "Morning / Evening") to
                "${summary.morningBpAvg?.let { "${it.systolic}/${it.diastolic}" } ?: "--"} | ${summary.eveningBpAvg?.let { "${it.systolic}/${it.diastolic}" } ?: "--"}",
            (if (isFrench) "Pouls Moy. 7j" else "7-Day Avg HR") to
                (summary.sevenDayHrAvg?.let { "$it BPM" } ?: "--"),
            (if (isFrench) "HRV (RMSSD)" else "HRV (RMSSD)") to
                (summary.sevenDayHrvRmssdAvg?.let { "${it.toInt()} ms" } ?: "--")
        )

        kpis.forEachIndexed { idx, (label, value) ->
            val left = MARGIN + idx * (boxW + 6f)
            canvas.drawRoundRect(RectF(left, y, left + boxW, y + boxH), 8f, 8f, cardBg)
            canvas.drawText(label, left + 8f, y + 20f, bodyPaint)
            canvas.drawText(value, left + 8f, y + 42f, boldBody)
        }
        y += boxH + 20f

        // Visual Mini Blood Pressure Trend Chart inside the PDF
        if (bpRecords.size >= 2) {
            canvas.drawText(
                if (isFrench) "Courbe de Tension Artérielle (mmHg)" else "Blood Pressure Trend (mmHg)",
                MARGIN,
                y,
                sectionTitle
            )
            y += 10f
            val chartRect = RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + 105f)
            drawBpMiniChart(canvas, chartRect, bpRecords.reversed())
            y += 124f
        }

        // Blood Pressure Measurement Table
        canvas.drawText(
            if (isFrench) "Mesures de Tension Artérielle Récentes" else "Recent Blood Pressure Readings",
            MARGIN,
            y,
            sectionTitle
        )
        y += 16f

        canvas.drawText("Date", MARGIN, y, boldBody)
        canvas.drawText("SYS/DIA", MARGIN + 125f, y, boldBody)
        canvas.drawText("MAP", MARGIN + 195f, y, boldBody)
        canvas.drawText("Pouls", MARGIN + 240f, y, boldBody)
        canvas.drawText("Source", MARGIN + 290f, y, boldBody)
        canvas.drawText("Classification", MARGIN + 375f, y, boldBody)
        y += 6f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
        y += 14f

        for (rec in bpRecords) {
            if (y > PAGE_HEIGHT - 165f) break
            val cat = BloodPressureClassifier.classify(rec.systolicMmHg, rec.diastolicMmHg, guideline).category.name
            val warn = if (rec.bodyMovementWarning || rec.irregularPulseWarning || rec.cuffFitWarning) " [!]" else ""
            canvas.drawText(dateFmt.format(Date(rec.timestampMillis)), MARGIN, y, bodyPaint)
            canvas.drawText("${rec.systolicMmHg}/${rec.diastolicMmHg} mmHg", MARGIN + 125f, y, boldBody)
            canvas.drawText("${rec.mapMmHg}", MARGIN + 195f, y, bodyPaint)
            canvas.drawText(rec.pulseBpm?.let { "$it BPM" } ?: "-", MARGIN + 240f, y, bodyPaint)
            canvas.drawText(rec.source, MARGIN + 290f, y, bodyPaint)
            canvas.drawText("$cat$warn", MARGIN + 375f, y, bodyPaint)
            y += 15f
        }

        y += 12f
        if (hrRecords.isNotEmpty() && y < PAGE_HEIGHT - 120f) {
            canvas.drawText(
                if (isFrench) "Mesures Fréquence Cardiaque & VFC (PPG)" else "Recent Heart Rate & HRV (PPG)",
                MARGIN,
                y,
                sectionTitle
            )
            y += 15f
            for (hr in hrRecords.take(5)) {
                if (y > PAGE_HEIGHT - 70f) break
                val rhythmTxt = if (hr.rhythmIrregularFlag) "Irregular!" else "Regular"
                val line = "${dateFmt.format(Date(hr.timestampMillis))}  |  ${hr.bpm} BPM  |  " +
                    "HRV RMSSD: ${hr.hrvRmssdMs.toInt()} ms  |  SQI: ${hr.sqiScore}%  |  $rhythmTxt"
                canvas.drawText(line, MARGIN, y, bodyPaint)
                y += 14f
            }
        }

        // Medical Disclaimer Footer
        val footerY = PAGE_HEIGHT - 36f
        canvas.drawLine(MARGIN, footerY - 14f, PAGE_WIDTH - MARGIN, footerY - 14f, dividerPaint)
        val disclaimer = if (isFrench) {
            "Avertissement : Ce rapport d'autosurveillance ne remplace pas un diagnostic médical. Consultez votre cardiologue."
        } else {
            "Disclaimer: Self-monitoring summary generated by CardioPulse. Does not replace medical diagnosis."
        }
        canvas.drawText(disclaimer, MARGIN, footerY, bodyPaint)
    }

    private fun drawBpMiniChart(
        canvas: Canvas,
        rect: RectF,
        chronological: List<BloodPressureRecordEntity>
    ) {
        val bg = Paint().apply { color = Color.parseColor("#F8FAFC") }
        val border = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val targetZone = Paint().apply { color = Color.parseColor("#DCFCE7") }
        val sysLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E11D48")
            strokeWidth = 2.2f
        }
        val diaLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB")
            strokeWidth = 2.2f
        }

        canvas.drawRect(rect, bg)

        val minVal = 50f
        val maxVal = 200f
        fun yFor(v: Int): Float {
            val clamped = v.toFloat().coerceIn(minVal, maxVal)
            val ratio = (clamped - minVal) / (maxVal - minVal)
            return rect.bottom - ratio * rect.height()
        }

        // Normal band (80..135 mmHg shaded lightly)
        canvas.drawRect(rect.left, yFor(135), rect.right, yFor(80), targetZone)
        canvas.drawRect(rect, border)

        val stepX = rect.width() / (chronological.size - 1).coerceAtLeast(1)
        for (i in 0 until chronological.size - 1) {
            val x1 = rect.left + i * stepX
            val x2 = rect.left + (i + 1) * stepX
            canvas.drawLine(x1, yFor(chronological[i].systolicMmHg), x2, yFor(chronological[i + 1].systolicMmHg), sysLine)
            canvas.drawLine(x1, yFor(chronological[i].diastolicMmHg), x2, yFor(chronological[i + 1].diastolicMmHg), diaLine)
        }
        chronological.forEachIndexed { idx, r ->
            val x = rect.left + idx * stepX
            canvas.drawCircle(x, yFor(r.systolicMmHg), 3f, sysLine)
            canvas.drawCircle(x, yFor(r.diastolicMmHg), 3f, diaLine)
        }
    }
}
