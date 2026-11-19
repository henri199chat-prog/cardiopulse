package com.cardiopulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import com.cardiopulse.app.ui.theme.ArterialCrimson
import com.cardiopulse.app.ui.theme.ClinicalBlue
import com.cardiopulse.app.ui.theme.StatusOptimal

@Composable
fun BloodPressureTrendChart(
    records: List<BloodPressureRecordEntity>,
    modifier: Modifier = Modifier
) {
    val chronological = records.take(20).reversed()
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            LegendDot(color = ArterialCrimson, label = "SYS")
            LegendDot(color = ClinicalBlue, label = "DIA")
            LegendDot(color = StatusOptimal.copy(alpha = 0.45f), label = "80–135")
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
        ) {
            val w = size.width
            val h = size.height
            val padX = 16f
            val padY = 12f
            val minVal = 50f
            val maxVal = 200f

            fun yFor(mmHg: Float): Float {
                val ratio = ((mmHg.coerceIn(minVal, maxVal) - minVal) / (maxVal - minVal))
                return (h - padY) - ratio * (h - 2f * padY)
            }

            // Shaded home target zone (80..135 mmHg)
            val y135 = yFor(135f)
            val y80 = yFor(80f)
            drawRect(
                color = StatusOptimal.copy(alpha = 0.11f),
                topLeft = Offset(0f, y135),
                size = Size(w, y80 - y135)
            )

            // Horizontal reference lines (60, 90, 120, 140, 180)
            listOf(60f, 90f, 120f, 140f, 180f).forEach { level ->
                val y = yFor(level)
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.2f)
            }

            if (chronological.isEmpty()) return@Canvas

            val stepX = if (chronological.size == 1) 0f else (w - 2f * padX) / (chronological.size - 1)
            val sysPath = Path()
            val diaPath = Path()

            chronological.forEachIndexed { idx, rec ->
                val x = if (chronological.size == 1) w / 2f else padX + idx * stepX
                val ySys = yFor(rec.systolicMmHg.toFloat())
                val yDia = yFor(rec.diastolicMmHg.toFloat())

                // Vertical pulse-pressure bridge bar
                drawLine(
                    color = gridColor.copy(alpha = 0.25f),
                    start = Offset(x, ySys),
                    end = Offset(x, yDia),
                    strokeWidth = 3f
                )

                if (idx == 0) {
                    sysPath.moveTo(x, ySys)
                    diaPath.moveTo(x, yDia)
                } else {
                    sysPath.lineTo(x, ySys)
                    diaPath.lineTo(x, yDia)
                }
            }

            if (chronological.size >= 2) {
                drawPath(sysPath, ArterialCrimson, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(diaPath, ClinicalBlue, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            chronological.forEachIndexed { idx, rec ->
                val x = if (chronological.size == 1) w / 2f else padX + idx * stepX
                drawCircle(ArterialCrimson, radius = 6.5f, center = Offset(x, yFor(rec.systolicMmHg.toFloat())))
                drawCircle(ClinicalBlue, radius = 6.5f, center = Offset(x, yFor(rec.diastolicMmHg.toFloat())))
            }
        }
    }
}

@Composable
fun HeartRateTrendChart(
    records: List<HeartRateRecordEntity>,
    modifier: Modifier = Modifier
) {
    val chronological = records.take(20).reversed()
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(145.dp)
    ) {
        val w = size.width
        val h = size.height
        val padX = 16f
        val padY = 12f
        val minBpm = 40f
        val maxBpm = 150f

        fun yFor(bpm: Float): Float {
            val ratio = (bpm.coerceIn(minBpm, maxBpm) - minBpm) / (maxBpm - minBpm)
            return (h - padY) - ratio * (h - 2f * padY)
        }

        // Normal resting HR zone (60..100 BPM)
        val y100 = yFor(100f)
        val y60 = yFor(60f)
        drawRect(
            color = StatusOptimal.copy(alpha = 0.11f),
            topLeft = Offset(0f, y100),
            size = Size(w, y60 - y100)
        )

        listOf(50f, 70f, 90f, 110f, 130f).forEach { level ->
            val y = yFor(level)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.2f)
        }

        if (chronological.isEmpty()) return@Canvas

        val stepX = if (chronological.size == 1) 0f else (w - 2f * padX) / (chronological.size - 1)
        val hrPath = Path()
        chronological.forEachIndexed { idx, rec ->
            val x = if (chronological.size == 1) w / 2f else padX + idx * stepX
            val y = yFor(rec.bpm.toFloat())
            if (idx == 0) hrPath.moveTo(x, y) else hrPath.lineTo(x, y)
        }

        if (chronological.size >= 2) {
            drawPath(hrPath, ArterialCrimson, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        chronological.forEachIndexed { idx, rec ->
            val x = if (chronological.size == 1) w / 2f else padX + idx * stepX
            drawCircle(ArterialCrimson, radius = 6.5f, center = Offset(x, yFor(rec.bpm.toFloat())))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(10.dp)
        ) {}
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
