package com.cardiopulse.app.core.bp

import kotlin.math.pow

/**
 * Decodes IEEE-11073 16-bit SFLOAT values used by Bluetooth SIG Medical Profiles
 * (Blood Pressure 0x2A35, Intermediate Cuff Pressure 0x2A36, etc.).
 */
object Ieee11073Parser {
    const val KPA_TO_MMHG = 7.50061683

    private const val SFLOAT_NAN = 0x07FF
    private const val SFLOAT_NRES = 0x0800
    private const val SFLOAT_POSITIVE_INFINITY = 0x07FE
    private const val SFLOAT_NEGATIVE_INFINITY = 0x0802
    private const val SFLOAT_RFU = 0x0801

    /**
     * Decodes a 16-bit little-endian SFLOAT starting at [offset] in [bytes].
     */
    fun decodeSFloat(bytes: ByteArray, offset: Int): Double {
        if (offset + 1 >= bytes.size) return Double.NaN
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val raw = b0 or (b1 shl 8)
        return decodeSFloatRaw(raw)
    }

    fun decodeSFloatRaw(rawUnsigned16: Int): Double {
        val raw = rawUnsigned16 and 0xFFFF
        return when (raw) {
            SFLOAT_NAN, SFLOAT_NRES, SFLOAT_RFU -> Double.NaN
            SFLOAT_POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
            SFLOAT_NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
            else -> {
                var mantissa = raw and 0x0FFF
                if (mantissa >= 0x0800) {
                    mantissa -= 0x1000
                }
                var exponent = (raw ushr 12) and 0x0F
                if (exponent >= 0x08) {
                    exponent -= 0x10
                }
                mantissa.toDouble() * 10.0.pow(exponent.toDouble())
            }
        }
    }

    /**
     * Encodes an integer value with optional base-10 exponent into 2 little-endian SFLOAT bytes
     * (useful for unit tests and protocol diagnostics).
     */
    fun encodeSFloat(mantissa: Int, exponent: Int = 0): ByteArray {
        require(mantissa in -2048..2047) { "Mantissa out of 12-bit signed range" }
        require(exponent in -8..7) { "Exponent out of 4-bit signed range" }
        val mBits = mantissa and 0x0FFF
        val eBits = (exponent and 0x0F) shl 12
        val raw = eBits or mBits
        return byteArrayOf((raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte())
    }
}
