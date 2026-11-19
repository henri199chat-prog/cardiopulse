package com.cardiopulse.app.core.ppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2nd-order Butterworth IIR biquad filter with zero-phase forward-backward
 * execution (`filtfilt`) and odd-reflection edge padding.
 *
 * Used to isolate the cardiac PPG frequency band (0.65 Hz – 3.5 Hz, i.e., 39 – 210 BPM)
 * without introducing phase shift or distorting peak timing needed for HRV (RMSSD).
 */
class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    /**
     * Applies causal Direct Form II Transposed filtering to [input].
     */
    fun filterForward(input: DoubleArray): DoubleArray {
        val output = DoubleArray(input.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in input.indices) {
            val x = input[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            output[i] = y
        }
        return output
    }

    /**
     * Applies zero-phase forward-backward filtering (`filtfilt`) using odd-reflection
     * edge padding to eliminate boundary transients.
     */
    fun filterZeroPhase(input: DoubleArray): DoubleArray {
        val n = input.size
        if (n < 6) return input.copyOf()

        val pad = min(24, n - 1)
        val padded = DoubleArray(n + 2 * pad)

        val x0 = input[0]
        val xn = input[n - 1]
        for (i in 0 until pad) {
            padded[i] = 2.0 * x0 - input[pad - i]
        }
        System.arraycopy(input, 0, padded, pad, n)
        for (i in 0 until pad) {
            padded[pad + n + i] = 2.0 * xn - input[n - 2 - i]
        }

        // Forward pass
        val forward = filterForward(padded)

        // Reverse -> Backward pass -> Reverse
        forward.reverse()
        val backward = filterForward(forward)
        backward.reverse()

        return backward.copyOfRange(pad, pad + n)
    }

    companion object {
        private val BUTTERWORTH_Q = 1.0 / sqrt(2.0)

        fun butterworthLowPass(cutoffHz: Double, sampleRateHz: Double): BiquadFilter {
            val w0 = 2.0 * PI * cutoffHz / sampleRateHz
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * BUTTERWORTH_Q)

            val b0 = (1.0 - cosW0) / 2.0
            val b1 = 1.0 - cosW0
            val b2 = (1.0 - cosW0) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha

            return BiquadFilter(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0
            )
        }

        fun butterworthHighPass(cutoffHz: Double, sampleRateHz: Double): BiquadFilter {
            val w0 = 2.0 * PI * cutoffHz / sampleRateHz
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * BUTTERWORTH_Q)

            val b0 = (1.0 + cosW0) / 2.0
            val b1 = -(1.0 + cosW0)
            val b2 = (1.0 + cosW0) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha

            return BiquadFilter(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0
            )
        }

        /**
         * Resamples irregular camera timestamps (in seconds) onto a uniform grid at [targetFsHz].
         */
        fun resampleUniform(
            timestampsSec: DoubleArray,
            values: DoubleArray,
            targetFsHz: Double
        ): DoubleArray {
            require(timestampsSec.size == values.size) { "Timestamp and value arrays must match" }
            if (timestampsSec.size < 2) return values.copyOf()

            val tStart = timestampsSec.first()
            val tEnd = timestampsSec.last()
            val duration = tEnd - tStart
            if (duration <= 0.0) return values.copyOf()

            val dt = 1.0 / targetFsHz
            val count = (duration * targetFsHz).toInt() + 1
            val uniform = DoubleArray(count)

            var srcIdx = 0
            for (i in 0 until count) {
                val t = tStart + i * dt
                while (srcIdx < timestampsSec.size - 2 && timestampsSec[srcIdx + 1] < t) {
                    srcIdx++
                }
                val t0 = timestampsSec[srcIdx]
                val t1 = timestampsSec[srcIdx + 1]
                val v0 = values[srcIdx]
                val v1 = values[srcIdx + 1]
                uniform[i] = if (t1 > t0) {
                    val frac = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
                    v0 + frac * (v1 - v0)
                } else {
                    v0
                }
            }
            return uniform
        }

        /**
         * Removes low-frequency respiratory and finger-pressure drift using a centered
         * moving-average baseline subtraction.
         */
        fun removeMovingBaseline(signal: DoubleArray, windowSamples: Int): DoubleArray {
            val n = signal.size
            if (n == 0 || windowSamples <= 1) return signal.copyOf()

            val half = windowSamples / 2
            val prefix = DoubleArray(n + 1)
            for (i in 0 until n) {
                prefix[i + 1] = prefix[i] + signal[i]
            }

            val detrended = DoubleArray(n)
            for (i in 0 until n) {
                val left = (i - half).coerceAtLeast(0)
                val right = (i + half).coerceAtMost(n - 1)
                val mean = (prefix[right + 1] - prefix[left]) / (right - left + 1)
                detrended[i] = signal[i] - mean
            }
            return detrended
        }
    }
}
