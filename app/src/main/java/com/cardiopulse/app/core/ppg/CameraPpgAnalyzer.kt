package com.cardiopulse.app.core.ppg

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.sqrt

/**
 * CameraX ImageAnalysis.Analyzer extracting center-ROI RGB statistics from
 * YUV_420_888 frames with full rowStride/pixelStride handling and monotonic
 * hardware timestamps for jitter-free PPG signal acquisition.
 */
class CameraPpgAnalyzer(
    private val onFrameSample: (FrameColorSample) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val planes = image.planes
            if (planes.size < 3) return

            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val yRowStride = planes[0].rowStride
            val yPixelStride = planes[0].pixelStride
            val uRowStride = planes[1].rowStride
            val uPixelStride = planes[1].pixelStride
            val vRowStride = planes[2].rowStride
            val vPixelStride = planes[2].pixelStride

            // Inspect central 50% x 50% ROI to avoid edge vignetting & light bleed
            val startX = width / 4
            val endX = (width * 3) / 4
            val startY = height / 4
            val endY = (height * 3) / 4

            var sumR = 0.0
            var sumR2 = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var saturatedCount = 0
            var sampleCount = 0

            val step = 2
            var row = startY
            while (row < endY) {
                val uvRow = row shr 1
                val yRowOffset = row * yRowStride
                val uRowOffset = uvRow * uRowStride
                val vRowOffset = uvRow * vRowStride

                var col = startX
                while (col < endX) {
                    val uvCol = col shr 1
                    val yIdx = yRowOffset + col * yPixelStride
                    val uIdx = uRowOffset + uvCol * uPixelStride
                    val vIdx = vRowOffset + uvCol * vPixelStride

                    if (yIdx < yBuffer.limit() && uIdx < uBuffer.limit() && vIdx < vBuffer.limit()) {
                        val y = (yBuffer.get(yIdx).toInt() and 0xFF).toDouble()
                        val u = (uBuffer.get(uIdx).toInt() and 0xFF).toDouble() - 128.0
                        val v = (vBuffer.get(vIdx).toInt() and 0xFF).toDouble() - 128.0

                        // Standard BT.601 YUV -> RGB conversion
                        val r = (y + 1.402 * v).coerceIn(0.0, 255.0)
                        val g = (y - 0.344136 * u - 0.714136 * v).coerceIn(0.0, 255.0)
                        val b = (y + 1.772 * u).coerceIn(0.0, 255.0)

                        sumR += r
                        sumR2 += r * r
                        sumG += g
                        sumB += b
                        if (r >= 253.0) saturatedCount++
                        sampleCount++
                    }
                    col += step
                }
                row += step
            }

            if (sampleCount > 0) {
                val meanR = sumR / sampleCount
                val meanG = sumG / sampleCount
                val meanB = sumB / sampleCount
                val varianceR = (sumR2 / sampleCount - meanR * meanR).coerceAtLeast(0.0)
                val stdR = sqrt(varianceR)
                val satRatio = saturatedCount.toDouble() / sampleCount

                onFrameSample(
                    FrameColorSample(
                        timestampNanos = image.imageInfo.timestamp,
                        meanRed = meanR,
                        meanGreen = meanG,
                        meanBlue = meanB,
                        redStdDev = stdR,
                        saturatedPixelRatio = satRatio
                    )
                )
            }
        } finally {
            image.close()
        }
    }
}
