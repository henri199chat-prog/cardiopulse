package com.cardiopulse.app

import android.app.Application
import com.cardiopulse.app.core.bp.BleBloodPressureManager
import com.cardiopulse.app.data.local.CardioPulseDatabase
import com.cardiopulse.app.data.preferences.UserPreferencesRepository
import com.cardiopulse.app.data.repository.CardioRepository

class AppContainer(application: Application) {
    private val database: CardioPulseDatabase = CardioPulseDatabase.getInstance(application)
    val repository: CardioRepository = CardioRepository(
        heartRateDao = database.heartRateDao(),
        bloodPressureDao = database.bloodPressureDao()
    )
    val preferences: UserPreferencesRepository = UserPreferencesRepository(application)
    val bleManager: BleBloodPressureManager = BleBloodPressureManager(application)
}

class CardioPulseApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
