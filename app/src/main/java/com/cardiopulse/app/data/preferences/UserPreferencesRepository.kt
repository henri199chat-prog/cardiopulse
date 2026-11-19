package com.cardiopulse.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cardiopulse.app.core.bp.BpGuideline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cardioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cardiopulse_prefs"
)

enum class AppLanguage(val tag: String) {
    ARABIC("ar"),
    FRENCH("fr")
}

data class UserSettings(
    val language: AppLanguage = AppLanguage.ARABIC,
    val guideline: BpGuideline = BpGuideline.ESC_ESH,
    val patientName: String = "",
    val lastPairedBleAddress: String = "",
    val lastPairedBleName: String = ""
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("app_language")
        val GUIDELINE = stringPreferencesKey("bp_guideline")
        val PATIENT_NAME = stringPreferencesKey("patient_name")
        val BLE_ADDRESS = stringPreferencesKey("ble_address")
        val BLE_NAME = stringPreferencesKey("ble_name")
    }

    val userSettings: Flow<UserSettings> = context.cardioDataStore.data.map { prefs ->
        val lang = when (prefs[Keys.LANGUAGE]) {
            AppLanguage.FRENCH.tag -> AppLanguage.FRENCH
            else -> AppLanguage.ARABIC
        }
        val guideline = when (prefs[Keys.GUIDELINE]) {
            BpGuideline.AHA_ACC.name -> BpGuideline.AHA_ACC
            else -> BpGuideline.ESC_ESH
        }
        UserSettings(
            language = lang,
            guideline = guideline,
            patientName = prefs[Keys.PATIENT_NAME].orEmpty(),
            lastPairedBleAddress = prefs[Keys.BLE_ADDRESS].orEmpty(),
            lastPairedBleName = prefs[Keys.BLE_NAME].orEmpty()
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.cardioDataStore.edit { it[Keys.LANGUAGE] = language.tag }
    }

    suspend fun setGuideline(guideline: BpGuideline) {
        context.cardioDataStore.edit { it[Keys.GUIDELINE] = guideline.name }
    }

    suspend fun setPatientName(name: String) {
        context.cardioDataStore.edit { it[Keys.PATIENT_NAME] = name.trim() }
    }

    suspend fun saveLastPairedDevice(address: String, name: String) {
        context.cardioDataStore.edit {
            it[Keys.BLE_ADDRESS] = address
            it[Keys.BLE_NAME] = name
        }
    }
}
