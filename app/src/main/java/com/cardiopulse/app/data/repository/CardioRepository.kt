package com.cardiopulse.app.data.repository

import com.cardiopulse.app.core.bp.BloodPressureClassifier
import com.cardiopulse.app.core.bp.BpMeasurementArm
import com.cardiopulse.app.core.bp.BpMeasurementPosture
import com.cardiopulse.app.core.bp.DataSource
import com.cardiopulse.app.core.bp.ParsedBleBloodPressure
import com.cardiopulse.app.core.ppg.MeasurementContext
import com.cardiopulse.app.core.ppg.PpgOutcome
import com.cardiopulse.app.core.ppg.RhythmAssessment
import com.cardiopulse.app.data.local.BloodPressureDao
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateDao
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow

class CardioRepository(
    private val heartRateDao: HeartRateDao,
    private val bloodPressureDao: BloodPressureDao
) {
    val allHeartRateRecords: Flow<List<HeartRateRecordEntity>> = heartRateDao.observeAll()
    val latestHeartRate: Flow<HeartRateRecordEntity?> = heartRateDao.observeLatest()

    val allBloodPressureRecords: Flow<List<BloodPressureRecordEntity>> = bloodPressureDao.observeAll()
    val latestBloodPressure: Flow<BloodPressureRecordEntity?> = bloodPressureDao.observeLatest()

    suspend fun savePpgMeasurement(
        verified: PpgOutcome.Verified,
        context: MeasurementContext,
        notes: String = ""
    ): Long {
        val entity = HeartRateRecordEntity(
            timestampMillis = System.currentTimeMillis(),
            bpm = verified.bpm,
            hrvRmssdMs = verified.hrvRmssdMs,
            hrvSdnnMs = verified.hrvSdnnMs,
            pnn50Percent = verified.pnn50Percent,
            sqiScore = verified.sqiScore,
            perfusionIndexPercent = verified.perfusionIndexPercent,
            confidenceLevel = verified.confidence.name,
            rhythmIrregularFlag = verified.rhythmAssessment == RhythmAssessment.IRREGULAR_DETECTED,
            durationSeconds = verified.durationSeconds,
            source = DataSource.CAMERA_PPG.name,
            context = context.name,
            notes = notes.trim()
        )
        return heartRateDao.insert(entity)
    }

    suspend fun saveManualBloodPressure(
        systolicMmHg: Int,
        diastolicMmHg: Int,
        pulseBpm: Int?,
        arm: BpMeasurementArm,
        posture: BpMeasurementPosture,
        notes: String = ""
    ): Long {
        val map = BloodPressureClassifier.computeMap(systolicMmHg, diastolicMmHg)
        val entity = BloodPressureRecordEntity(
            timestampMillis = System.currentTimeMillis(),
            systolicMmHg = systolicMmHg,
            diastolicMmHg = diastolicMmHg,
            pulseBpm = pulseBpm,
            mapMmHg = map,
            pulsePressureMmHg = systolicMmHg - diastolicMmHg,
            source = DataSource.MANUAL.name,
            arm = arm.name,
            posture = posture.name,
            notes = notes.trim()
        )
        return bloodPressureDao.insert(entity)
    }

    suspend fun saveBleBloodPressure(
        parsed: ParsedBleBloodPressure,
        deviceName: String,
        arm: BpMeasurementArm = BpMeasurementArm.LEFT,
        posture: BpMeasurementPosture = BpMeasurementPosture.SEATED,
        notes: String = ""
    ): Long {
        val map = parsed.meanArterialPressureMmHg
            ?: BloodPressureClassifier.computeMap(parsed.systolicMmHg, parsed.diastolicMmHg)
        val entity = BloodPressureRecordEntity(
            timestampMillis = parsed.timestampEpochMillis ?: System.currentTimeMillis(),
            systolicMmHg = parsed.systolicMmHg,
            diastolicMmHg = parsed.diastolicMmHg,
            pulseBpm = parsed.pulseBpm,
            mapMmHg = map,
            pulsePressureMmHg = parsed.systolicMmHg - parsed.diastolicMmHg,
            source = DataSource.BLE_CUFF.name,
            deviceName = deviceName,
            arm = arm.name,
            posture = posture.name,
            bodyMovementWarning = parsed.statusFlags.bodyMovementDetected,
            cuffFitWarning = parsed.statusFlags.cuffTooLoose || parsed.statusFlags.improperPosition,
            irregularPulseWarning = parsed.statusFlags.irregularPulseDetected,
            notes = notes.trim()
        )
        return bloodPressureDao.insert(entity)
    }

    suspend fun deleteHeartRate(id: Long) = heartRateDao.deleteById(id)

    suspend fun deleteBloodPressure(id: Long) = bloodPressureDao.deleteById(id)
}
