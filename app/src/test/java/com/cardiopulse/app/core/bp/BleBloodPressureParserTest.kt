package com.cardiopulse.app.core.bp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BleBloodPressureParserTest {

    @Test
    fun decodesIeee11073SFloatValuesAndSpecialCodes() {
        val s120 = Ieee11073Parser.encodeSFloat(120, 0)
        assertEquals(120.0, Ieee11073Parser.decodeSFloat(s120, 0), 1e-6)

        val s16Point0 = Ieee11073Parser.encodeSFloat(160, -1)
        assertEquals(16.0, Ieee11073Parser.decodeSFloat(s16Point0, 0), 1e-6)

        val sNeg = Ieee11073Parser.encodeSFloat(-42, -1)
        assertEquals(-4.2, Ieee11073Parser.decodeSFloat(sNeg, 0), 1e-6)

        assertTrue(Ieee11073Parser.decodeSFloatRaw(0x07FF).isNaN())
        assertTrue(Ieee11073Parser.decodeSFloatRaw(0x0800).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Ieee11073Parser.decodeSFloatRaw(0x07FE), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, Ieee11073Parser.decodeSFloatRaw(0x0802), 0.0)
    }

    @Test
    fun parsesFullMmHgBloodPressurePayloadWithTimestampPulseAndStatusFlags() {
        // Flags: mmHg(0) | Timestamp(0x02) | Pulse(0x04) | UserID(0x08) | Status(0x10) = 0x1E
        val out = ByteArrayOutputStream()
        out.write(0x1E)
        out.write(Ieee11073Parser.encodeSFloat(138, 0)) // SYS 138
        out.write(Ieee11073Parser.encodeSFloat(86, 0))  // DIA 86
        out.write(Ieee11073Parser.encodeSFloat(103, 0)) // MAP 103
        // Timestamp: 2026-11-18 08:30:15
        out.write(2026 and 0xFF)
        out.write((2026 ushr 8) and 0xFF)
        out.write(11)
        out.write(18)
        out.write(8)
        out.write(30)
        out.write(15)
        // Pulse: 74 BPM
        out.write(Ieee11073Parser.encodeSFloat(74, 0))
        // User ID: 1
        out.write(1)
        // Status word: Body Movement (0x01) + Irregular Pulse (0x04) = 0x0005
        out.write(0x05)
        out.write(0x00)

        val parsed = BleBloodPressureParser.parseBloodPressureMeasurement(out.toByteArray())
        assertNotNull(parsed)
        parsed!!

        assertEquals(138, parsed.systolicMmHg)
        assertEquals(86, parsed.diastolicMmHg)
        assertEquals(103, parsed.meanArterialPressureMmHg)
        assertEquals(74, parsed.pulseBpm)
        assertEquals(1, parsed.userId)
        assertNotNull(parsed.timestampEpochMillis)
        assertFalse(parsed.originalUnitWasKpa)
        assertTrue(parsed.statusFlags.bodyMovementDetected)
        assertFalse(parsed.statusFlags.cuffTooLoose)
        assertTrue(parsed.statusFlags.irregularPulseDetected)
        assertTrue(parsed.statusFlags.hasAnyWarning)
    }

    @Test
    fun convertsKpaBloodPressurePayloadAccuratelyToMmHg() {
        // Flags: kPa(0x01) | Pulse(0x04) = 0x05
        // 16.0 kPa -> 120 mmHg, 10.7 kPa -> 80 mmHg, 12.4 kPa -> 93 mmHg
        val out = ByteArrayOutputStream()
        out.write(0x05)
        out.write(Ieee11073Parser.encodeSFloat(160, -1))
        out.write(Ieee11073Parser.encodeSFloat(107, -1))
        out.write(Ieee11073Parser.encodeSFloat(124, -1))
        out.write(Ieee11073Parser.encodeSFloat(68, 0))

        val parsed = BleBloodPressureParser.parseBloodPressureMeasurement(out.toByteArray())
        assertNotNull(parsed)
        parsed!!

        assertTrue(parsed.originalUnitWasKpa)
        assertEquals(120, parsed.systolicMmHg)
        assertEquals(80, parsed.diastolicMmHg)
        assertEquals(93, parsed.meanArterialPressureMmHg)
        assertEquals(68, parsed.pulseBpm)
    }

    @Test
    fun parsesIntermediateCuffPressureAndHeartRateWithRrIntervals() {
        val cuffBytes = byteArrayOf(0x00) + Ieee11073Parser.encodeSFloat(145, 0)
        assertEquals(145, BleBloodPressureParser.parseIntermediateCuffPressure(cuffBytes))

        // HR 0x2A37: 8-bit HR (72 BPM) + RR intervals (832/1024s = 812.5ms, 850/1024s = 830.08ms)
        val hrPayload = byteArrayOf(
            0x10, // RR present, 8-bit BPM
            72,
            (832 and 0xFF).toByte(), ((832 ushr 8) and 0xFF).toByte(),
            (850 and 0xFF).toByte(), ((850 ushr 8) and 0xFF).toByte()
        )
        val parsedHr = BleBloodPressureParser.parseHeartRateMeasurement(hrPayload)
        assertNotNull(parsedHr)
        assertEquals(72, parsedHr!!.bpm)
        assertEquals(2, parsedHr.rrIntervalsMs.size)
        assertEquals(812.5, parsedHr.rrIntervalsMs[0], 0.1)
    }

    @Test
    fun rejectsMalformedOrShortPayloadsSafely() {
        assertNull(BleBloodPressureParser.parseBloodPressureMeasurement(byteArrayOf(0x00, 0x01)))
        assertNull(BleBloodPressureParser.parseIntermediateCuffPressure(byteArrayOf(0x00)))
        assertNull(BleBloodPressureParser.parseHeartRateMeasurement(byteArrayOf(0x00)))
    }
}
