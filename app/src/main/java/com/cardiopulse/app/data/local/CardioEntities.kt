package com.cardiopulse.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "heart_rate_records")
data class HeartRateRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val bpm: Int,
    val hrvRmssdMs: Double,
    val hrvSdnnMs: Double,
    val pnn50Percent: Double,
    val sqiScore: Int,
    val perfusionIndexPercent: Double,
    val confidenceLevel: String,
    val rhythmIrregularFlag: Boolean,
    val durationSeconds: Int,
    val source: String,
    val context: String,
    val notes: String = ""
)

@Entity(tableName = "blood_pressure_records")
data class BloodPressureRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val pulseBpm: Int?,
    val mapMmHg: Int,
    val pulsePressureMmHg: Int,
    val source: String,
    val deviceName: String = "",
    val arm: String,
    val posture: String,
    val bodyMovementWarning: Boolean = false,
    val cuffFitWarning: Boolean = false,
    val irregularPulseWarning: Boolean = false,
    val notes: String = ""
)
