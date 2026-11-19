package com.cardiopulse.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HeartRateRecordEntity): Long

    @Query("SELECT * FROM heart_rate_records ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<HeartRateRecordEntity>>

    @Query("SELECT * FROM heart_rate_records ORDER BY timestampMillis DESC LIMIT 1")
    fun observeLatest(): Flow<HeartRateRecordEntity?>

    @Query("SELECT * FROM heart_rate_records WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC")
    fun observeSince(sinceMillis: Long): Flow<List<HeartRateRecordEntity>>

    @Query("DELETE FROM heart_rate_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface BloodPressureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BloodPressureRecordEntity): Long

    @Query("SELECT * FROM blood_pressure_records ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<BloodPressureRecordEntity>>

    @Query("SELECT * FROM blood_pressure_records ORDER BY timestampMillis DESC LIMIT 1")
    fun observeLatest(): Flow<BloodPressureRecordEntity?>

    @Query("SELECT * FROM blood_pressure_records WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC")
    fun observeSince(sinceMillis: Long): Flow<List<BloodPressureRecordEntity>>

    @Query("DELETE FROM blood_pressure_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
