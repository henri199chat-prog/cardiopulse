package com.cardiopulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cardiopulse.app.ui.theme.ArterialCrimson
import com.cardiopulse.app.ui.theme.NavyDeep
import com.cardiopulse.app.ui.theme.StatusOptimal
import com.cardiopulse.app.ui.theme.StatusWarning

/**
 * Medical-grade live PPG oscilloscope waveform display.
 * Renders subtle ECG-style grid lines and a smooth normalized cardiac pulse curve.
 */
@Composable
fun PpgWaveformCanvas(
    samples: List<Float>,
    sqiScore: Int,
    modifier: Modifier = Modifier
) {
    val lineColor = when {
        sqiScore >= 75 -> StatusOptimal
        sqiScore >= 45 -> StatusWarning
        else -> ArterialCrimson
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(NavyDeep)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Medical grid background
        val gridColor = Color.White.copy(alpha = 0.06f)
        val cols = 12
        val rows = 6
        for (i in 1 until cols) {
            val x = w * i / cols
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        for (j in 1 until rows) {
            val y = h * j / rows
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // Center baseline
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.2f
        )

        if (samples.size < 2) return@Canvas

        val padY = h * 0.14f
        val ampScale = (h / 2f) - padY
        val stepX = w / (samples.size - 1).coerceAtLeast(1)

        val strokePath = Path()
        val fillPath = Path()

        samples.forEachIndexed { idx, value ->
            val x = idx * stepX
            val y = midY - value.coerceIn(-1f, 1f) * ampScale
            if (idx == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                strokePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        // Soft vertical glow under the pulse wave
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.28f),
                    lineColor.copy(alpha = 0.0f)
                )
            )
        )

        // Outer soft halo
        drawPath(
            path = strokePath,
            color = lineColor.copy(alpha = 0.35f),
            style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Crisp foreground PPG wave
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Leading pulse cursor dot
        val lastY = midY - samples.last().coerceIn(-1f, 1f) * ampScale
        drawCircle(
            color = Color.White,
            radius = 5f,
            center = Offset(w - 2f, lastY)
        )
    }
}
