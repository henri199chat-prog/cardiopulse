package com.cardiopulse.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HeartRateRecordEntity::class,
        BloodPressureRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CardioPulseDatabase : RoomDatabase() {
    abstract fun heartRateDao(): HeartRateDao
    abstract fun bloodPressureDao(): BloodPressureDao

    companion object {
        @Volatile
        private var INSTANCE: CardioPulseDatabase? = null

        fun getInstance(context: Context): CardioPulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CardioPulseDatabase::class.java,
                    "cardiopulse_health.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
