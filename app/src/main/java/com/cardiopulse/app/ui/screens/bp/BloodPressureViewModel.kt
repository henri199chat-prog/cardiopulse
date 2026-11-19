package com.cardiopulse.app.ui.screens.bp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardiopulse.app.core.bp.BleBloodPressureManager
import com.cardiopulse.app.core.bp.BleConnectionState
import com.cardiopulse.app.core.bp.BleDiscoveredDevice
import com.cardiopulse.app.core.bp.BloodPressureClassifier
import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.core.bp.BpMeasurementArm
import com.cardiopulse.app.core.bp.BpMeasurementPosture
import com.cardiopulse.app.core.bp.BpValidationResult
import com.cardiopulse.app.core.bp.ParsedBleBloodPressure
import com.cardiopulse.app.data.preferences.UserPreferencesRepository
import com.cardiopulse.app.data.repository.CardioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PendingFirstReading(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?
)

data class BpScreenUiState(
    val systolicInput: String = "120",
    val diastolicInput: String = "80",
    val pulseInput: String = "",
    val notesInput: String = "",
    val selectedArm: BpMeasurementArm = BpMeasurementArm.LEFT,
    val selectedPosture: BpMeasurementPosture = BpMeasurementPosture.SEATED,
    val twoReadingAverageMode: Boolean = false,
    val firstReading: PendingFirstReading? = null,
    val latestBleReading: ParsedBleBloodPressure? = null,
    val restTimerSecondsRemaining: Int = 300,
    val isRestTimerRunning: Boolean = false,
    val saveSuccessCounter: Int = 0
)

class BloodPressureViewModel(
    private val repository: CardioRepository,
    private val bleManager: BleBloodPressureManager,
    private val preferences: UserPreferencesRepository
) : ViewModel() {

    val bleConnectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val bleConnectedDeviceName: StateFlow<String?> = bleManager.connectedDeviceName
    val bleDiscoveredDevices: StateFlow<List<BleDiscoveredDevice>> = bleManager.discoveredDevices
    val liveCuffPressureMmHg: StateFlow<Int?> = bleManager.liveCuffPressureMmHg

    private val _uiState = MutableStateFlow(BpScreenUiState())
    val uiState: StateFlow<BpScreenUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            bleManager.receivedBpReadings.collect { parsed ->
                _uiState.value = _uiState.value.copy(
                    latestBleReading = parsed,
                    systolicInput = parsed.systolicMmHg.toString(),
                    diastolicInput = parsed.diastolicMmHg.toString(),
                    pulseInput = parsed.pulseBpm?.toString().orEmpty()
                )
            }
        }
    }

    fun startBleScan() = bleManager.startScan()
    fun stopBleScan() = bleManager.stopScan()

    fun connectBleDevice(device: BleDiscoveredDevice) {
        bleManager.connect(device.address, device.name)
        viewModelScope.launch {
            preferences.saveLastPairedDevice(device.address, device.name)
        }
    }

    fun disconnectBle() = bleManager.disconnect()

    fun updateSystolic(value: String) {
        _uiState.value = _uiState.value.copy(systolicInput = value.filter { it.isDigit() }.take(3))
    }

    fun updateDiastolic(value: String) {
        _uiState.value = _uiState.value.copy(diastolicInput = value.filter { it.isDigit() }.take(3))
    }

    fun updatePulse(value: String) {
        _uiState.value = _uiState.value.copy(pulseInput = value.filter { it.isDigit() }.take(3))
    }

    fun updateNotes(value: String) {
        _uiState.value = _uiState.value.copy(notesInput = value.take(200))
    }

    fun selectArm(arm: BpMeasurementArm) {
        _uiState.value = _uiState.value.copy(selectedArm = arm)
    }

    fun selectPosture(posture: BpMeasurementPosture) {
        _uiState.value = _uiState.value.copy(selectedPosture = posture)
    }

    fun toggleAverageMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            twoReadingAverageMode = enabled,
            firstReading = if (enabled) _uiState.value.firstReading else null
        )
    }

    fun clearFirstReading() {
        _uiState.value = _uiState.value.copy(firstReading = null)
    }

    fun validateCurrentInputs(guideline: BpGuideline): BpValidationResult? {
        val sys = _uiState.value.systolicInput.toIntOrNull() ?: return null
        val dia = _uiState.value.diastolicInput.toIntOrNull() ?: return null
        return BloodPressureClassifier.validateAndClassify(sys, dia, guideline)
    }

    fun saveBleReading() {
        val parsed = _uiState.value.latestBleReading ?: return
        val deviceName = bleConnectedDeviceName.value.orEmpty()
        val state = _uiState.value
        viewModelScope.launch {
            repository.saveBleBloodPressure(
                parsed = parsed,
                deviceName = deviceName,
                arm = state.selectedArm,
                posture = state.selectedPosture,
                notes = state.notesInput
            )
            _uiState.value = state.copy(
                latestBleReading = null,
                notesInput = "",
                saveSuccessCounter = state.saveSuccessCounter + 1
            )
        }
    }

    fun submitManualReading(guideline: BpGuideline): Boolean {
        val state = _uiState.value
        val sys = state.systolicInput.toIntOrNull() ?: return false
        val dia = state.diastolicInput.toIntOrNull() ?: return false
        val pulse = state.pulseInput.toIntOrNull()?.takeIf { it in 25..240 }

        val validation = BloodPressureClassifier.validateAndClassify(sys, dia, guideline)
        if (validation !is BpValidationResult.Valid) return false

        if (state.twoReadingAverageMode) {
            val first = state.firstReading
            if (first == null) {
                _uiState.value = state.copy(firstReading = PendingFirstReading(sys, dia, pulse))
                return true
            } else {
                val (avgSys, avgDia, avgPulse) = BloodPressureClassifier.averageTwoReadings(
                    first.systolic, first.diastolic, first.pulse,
                    sys, dia, pulse
                )
                viewModelScope.launch {
                    val avgNote = buildString {
                        append("[2-Reading Avg: ${first.systolic}/${first.diastolic} & $sys/$dia]")
                        if (state.notesInput.isNotBlank()) {
                            append(" ")
                            append(state.notesInput.trim())
                        }
                    }
                    repository.saveManualBloodPressure(
                        systolicMmHg = avgSys,
                        diastolicMmHg = avgDia,
                        pulseBpm = avgPulse,
                        arm = state.selectedArm,
                        posture = state.selectedPosture,
                        notes = avgNote
                    )
                    _uiState.value = state.copy(
                        firstReading = null,
                        notesInput = "",
                        saveSuccessCounter = state.saveSuccessCounter + 1
                    )
                }
                return true
            }
        } else {
            viewModelScope.launch {
                repository.saveManualBloodPressure(
                    systolicMmHg = sys,
                    diastolicMmHg = dia,
                    pulseBpm = pulse,
                    arm = state.selectedArm,
                    posture = state.selectedPosture,
                    notes = state.notesInput
                )
                _uiState.value = state.copy(
                    notesInput = "",
                    saveSuccessCounter = state.saveSuccessCounter + 1
                )
            }
            return true
        }
    }

    fun startRestTimer() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(
            restTimerSecondsRemaining = 300,
            isRestTimerRunning = true
        )
        timerJob = viewModelScope.launch {
            while (_uiState.value.restTimerSecondsRemaining > 0) {
                delay(1000L)
                _uiState.value = _uiState.value.copy(
                    restTimerSecondsRemaining = (_uiState.value.restTimerSecondsRemaining - 1).coerceAtLeast(0)
                )
            }
            _uiState.value = _uiState.value.copy(isRestTimerRunning = false)
        }
    }

    fun resetRestTimer() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(
            restTimerSecondsRemaining = 300,
            isRestTimerRunning = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        bleManager.stopScan()
        bleManager.disconnect()
    }
}
