package com.cardiopulse.app.ui.screens.ppg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardiopulse.app.core.ppg.FingerContactState
import com.cardiopulse.app.core.ppg.FingerPresenceDetector
import com.cardiopulse.app.core.ppg.FrameColorSample
import com.cardiopulse.app.core.ppg.MeasurementContext
import com.cardiopulse.app.core.ppg.PpgGuidanceState
import com.cardiopulse.app.core.ppg.PpgLiveState
import com.cardiopulse.app.core.ppg.PpgOutcome
import com.cardiopulse.app.core.ppg.PpgSignalProcessor
import com.cardiopulse.app.data.repository.CardioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Coordinates real-time PPG camera frame ingestion, finger contact gating,
 * motion artifact monitoring, 20-second measurement lifecycle, and final
 * dual-method verification before allowing the user to save a reading.
 */
class PpgMeasureViewModel(
    private val repository: CardioRepository
) : ViewModel() {

    companion object {
        private const val TARGET_DURATION_SECONDS = 20.0
        private const val MOTION_WARNING_THRESHOLD = 0.42
        private const val LIVE_ANALYSIS_FRAME_INTERVAL = 4
    }

    private val fingerDetector = FingerPresenceDetector()
    private val signalProcessor = PpgSignalProcessor()

    private val _liveState = MutableStateFlow(PpgLiveState())
    val liveState: StateFlow<PpgLiveState> = _liveState.asStateFlow()

    private val _outcome = MutableStateFlow<PpgOutcome?>(null)
    val outcome: StateFlow<PpgOutcome?> = _outcome.asStateFlow()

    private val _selectedContext = MutableStateFlow(MeasurementContext.RESTING)
    val selectedContext: StateFlow<MeasurementContext> = _selectedContext.asStateFlow()

    private val _savedSuccess = MutableStateFlow(false)
    val savedSuccess: StateFlow<Boolean> = _savedSuccess.asStateFlow()

    private var frameCounter = 0

    fun startMeasurement() {
        fingerDetector.reset()
        signalProcessor.reset()
        frameCounter = 0
        _outcome.value = null
        _savedSuccess.value = false
        _liveState.value = PpgLiveState(
            isMeasuring = true,
            targetSeconds = TARGET_DURATION_SECONDS.toFloat(),
            guidance = PpgGuidanceState.PLACE_FINGER
        )
    }

    fun stopMeasurement() {
        _liveState.value = _liveState.value.copy(
            isMeasuring = false,
            guidance = PpgGuidanceState.IDLE
        )
    }

    fun selectContext(context: MeasurementContext) {
        _selectedContext.value = context
    }

    fun dismissOutcome() {
        _outcome.value = null
        _savedSuccess.value = false
    }

    /**
     * Called on camera analyzer background executor for every YUV frame.
     */
    @Synchronized
    fun onCameraFrame(sample: FrameColorSample, motionRms: Double) {
        if (!_liveState.value.isMeasuring) return

        val contact = fingerDetector.evaluate(sample)
        val redPreview = sample.meanRed.roundToInt().coerceIn(0, 255)

        if (contact != FingerContactState.VALID_CONTACT) {
            // Reset accumulation if finger leaves lens so partial disconnected segments aren't spliced
            if (signalProcessor.getSampleCount() > 0 && signalProcessor.getElapsedSeconds() < 12.0) {
                signalProcessor.reset()
            }
            val guidance = when (contact) {
                FingerContactState.PRESSING_TOO_HARD -> PpgGuidanceState.PRESSING_TOO_HARD
                FingerContactState.TOO_LIGHT_OR_AMBIENT_LEAK -> PpgGuidanceState.AMBIENT_LIGHT_LEAK
                else -> PpgGuidanceState.PLACE_FINGER
            }
            _liveState.value = _liveState.value.copy(
                contactState = contact,
                guidance = guidance,
                motionRms = motionRms,
                elapsedSeconds = signalProcessor.getElapsedSeconds().toFloat(),
                progress = (signalProcessor.getElapsedSeconds() / TARGET_DURATION_SECONDS).toFloat().coerceIn(0f, 1f),
                meanRedPreview = redPreview,
                instantBpm = null,
                sqiScore = 0
            )
            return
        }

        // Valid fingertip contact — ingest sample
        signalProcessor.addSample(sample, motionRms)
        val elapsed = signalProcessor.getElapsedSeconds()
        frameCounter++

        if (elapsed >= TARGET_DURATION_SECONDS) {
            val result = signalProcessor.finalizeMeasurement(minDurationSeconds = 12.0)
            _liveState.value = _liveState.value.copy(
                isMeasuring = false,
                elapsedSeconds = TARGET_DURATION_SECONDS.toFloat(),
                progress = 1f,
                guidance = PpgGuidanceState.IDLE
            )
            _outcome.value = result
            return
        }

        // Throttle DSP live window computation to ~7–8 Hz for buttery smooth UI + low battery
        if (frameCounter % LIVE_ANALYSIS_FRAME_INTERVAL != 0) return

        val snapshot = signalProcessor.analyzeLiveWindow()
        val guidance = when {
            motionRms > MOTION_WARNING_THRESHOLD -> PpgGuidanceState.EXCESSIVE_MOTION
            elapsed < 4.5 -> PpgGuidanceState.CALIBRATING
            snapshot.sqiScore >= 75 -> PpgGuidanceState.SIGNAL_EXCELLENT
            snapshot.sqiScore >= 48 -> PpgGuidanceState.SIGNAL_GOOD
            else -> PpgGuidanceState.SIGNAL_WEAK
        }

        _liveState.value = PpgLiveState(
            isMeasuring = true,
            elapsedSeconds = elapsed.toFloat(),
            targetSeconds = TARGET_DURATION_SECONDS.toFloat(),
            progress = (elapsed / TARGET_DURATION_SECONDS).toFloat().coerceIn(0f, 1f),
            instantBpm = snapshot.instantBpm,
            sqiScore = snapshot.sqiScore,
            contactState = FingerContactState.VALID_CONTACT,
            guidance = guidance,
            motionRms = motionRms,
            perfusionIndexPercent = snapshot.perfusionIndexPercent,
            waveformWindow = snapshot.waveform,
            meanRedPreview = redPreview
        )
    }

    fun saveVerifiedOutcome(notes: String = "") {
        val verified = _outcome.value as? PpgOutcome.Verified ?: return
        viewModelScope.launch {
            repository.savePpgMeasurement(
                verified = verified,
                context = _selectedContext.value,
                notes = notes
            )
            _savedSuccess.value = true
        }
    }
}
