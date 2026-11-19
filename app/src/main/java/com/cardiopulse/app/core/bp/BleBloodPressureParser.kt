package com.cardiopulse.app.core.bp

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Spec-compliant Bluetooth SIG GATT parser for:
 * - Blood Pressure Measurement (UUID 0x2A35)
 * - Intermediate Cuff Pressure (UUID 0x2A36)
 * - Heart Rate Measurement (UUID 0x2A37)
 */
object BleBloodPressureParser {

    /**
     * Parses characteristic 0x2A35 (Blood Pressure Measurement).
     * Returns null if payload is malformed or contains NaN/non-finite systolic/diastolic values.
     */
    fun parseBloodPressureMeasurement(data: ByteArray): ParsedBleBloodPressure? {
        // Minimum size: 1 byte flags + 6 bytes (Sys, Dia, MAP SFLOATs)
        if (data.size < 7) return null

        val flags = data[0].toInt() and 0xFF
        val isKpa = (flags and 0x01) != 0
        val hasTimestamp = (flags and 0x02) != 0
        val hasPulseRate = (flags and 0x04) != 0
        val hasUserId = (flags and 0x08) != 0
        val hasStatus = (flags and 0x10) != 0

        val unitFactor = if (isKpa) Ieee11073Parser.KPA_TO_MMHG else 1.0

        val sysRaw = Ieee11073Parser.decodeSFloat(data, 1)
        val diaRaw = Ieee11073Parser.decodeSFloat(data, 3)
        val mapRaw = Ieee11073Parser.decodeSFloat(data, 5)

        if (!sysRaw.isFinite() || !diaRaw.isFinite()) return null

        val systolicMmHg = (sysRaw * unitFactor).roundToInt()
        val diastolicMmHg = (diaRaw * unitFactor).roundToInt()
        val mapMmHg = if (mapRaw.isFinite() && mapRaw > 0.0) {
            (mapRaw * unitFactor).roundToInt()
        } else {
            null
        }

        var offset = 7

        // Optional Timestamp (7 bytes: Year uint16, Month, Day, Hour, Min, Sec)
        var timestampMillis: Long? = null
        if (hasTimestamp) {
            if (offset + 7 <= data.size) {
                val year = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)
                val month = data[offset + 2].toInt() and 0xFF
                val day = data[offset + 3].toInt() and 0xFF
                val hour = data[offset + 4].toInt() and 0xFF
                val minute = data[offset + 5].toInt() and 0xFF
                val second = data[offset + 6].toInt() and 0xFF

                if (year in 2000..2100 && month in 1..12 && day in 1..31) {
                    val cal = Calendar.getInstance(TimeZone.getDefault())
                    cal.set(year, month - 1, day, hour, minute, second)
                    cal.set(Calendar.MILLISECOND, 0)
                    timestampMillis = cal.timeInMillis
                }
            }
            offset += 7
        }

        // Optional Pulse Rate (2 bytes SFLOAT in BPM)
        var pulseBpm: Int? = null
        if (hasPulseRate) {
            if (offset + 2 <= data.size) {
                val pulseRaw = Ieee11073Parser.decodeSFloat(data, offset)
                if (pulseRaw.isFinite() && pulseRaw in 25.0..250.0) {
                    pulseBpm = pulseRaw.roundToInt()
                }
            }
            offset += 2
        }

        // Optional User ID (1 byte uint8)
        var userId: Int? = null
        if (hasUserId) {
            if (offset + 1 <= data.size) {
                val uid = data[offset].toInt() and 0xFF
                if (uid != 0xFF) userId = uid
            }
            offset += 1
        }

        // Optional Measurement Status (2 bytes uint16 bitmask)
        var statusFlags = BleBpStatusFlags()
        if (hasStatus && offset + 2 <= data.size) {
            val statusWord = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
            val pulseRangeBits = (statusWord ushr 3) and 0x03
            statusFlags = BleBpStatusFlags(
                bodyMovementDetected = (statusWord and 0x0001) != 0,
                cuffTooLoose = (statusWord and 0x0002) != 0,
                irregularPulseDetected = (statusWord and 0x0004) != 0,
                pulseRateOutOfRange = pulseRangeBits != 0,
                improperPosition = (statusWord and 0x0020) != 0
            )
        }

        return ParsedBleBloodPressure(
            systolicMmHg = systolicMmHg,
            diastolicMmHg = diastolicMmHg,
            meanArterialPressureMmHg = mapMmHg,
            pulseBpm = pulseBpm,
            timestampEpochMillis = timestampMillis,
            userId = userId,
            statusFlags = statusFlags,
            originalUnitWasKpa = isKpa
        )
    }

    /**
     * Parses characteristic 0x2A36 (Intermediate Cuff Pressure) and returns mmHg.
     */
    fun parseIntermediateCuffPressure(data: ByteArray): Int? {
        if (data.size < 3) return null
        val flags = data[0].toInt() and 0xFF
        val isKpa = (flags and 0x01) != 0
        val unitFactor = if (isKpa) Ieee11073Parser.KPA_TO_MMHG else 1.0
        val cuffRaw = Ieee11073Parser.decodeSFloat(data, 1)
        if (!cuffRaw.isFinite() || cuffRaw < 0.0) return null
        return (cuffRaw * unitFactor).roundToInt()
    }

    /**
     * Parses characteristic 0x2A37 (Heart Rate Measurement) supporting 8-bit/16-bit HR and RR intervals.
     */
    fun parseHeartRateMeasurement(data: ByteArray): ParsedBleHeartRate? {
        if (data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0
        val hasEnergyExpended = (flags and 0x08) != 0
        val hasRrIntervals = (flags and 0x10) != 0

        var offset = 1
        val bpm = if (isUint16) {
            if (offset + 2 > data.size) return null
            val v = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v
        } else {
            val v = data[offset].toInt() and 0xFF
            offset += 1
            v
        }

        if (bpm !in 25..250) return null
        if (hasEnergyExpended) offset += 2

        val rrList = ArrayList<Double>()
        if (hasRrIntervals) {
            while (offset + 1 < data.size) {
                val rawRr = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)
                if (rawRr > 0) {
                    rrList.add(rawRr * 1000.0 / 1024.0)
                }
                offset += 2
            }
        }

        return ParsedBleHeartRate(bpm = bpm, rrIntervalsMs = rrList)
    }
}
