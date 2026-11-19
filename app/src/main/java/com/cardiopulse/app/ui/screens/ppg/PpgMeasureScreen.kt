package com.cardiopulse.app.ui.screens.ppg

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cardiopulse.app.R
import com.cardiopulse.app.core.ppg.CameraPpgAnalyzer
import com.cardiopulse.app.core.ppg.ConfidenceLevel
import com.cardiopulse.app.core.ppg.MeasurementContext
import com.cardiopulse.app.core.ppg.MotionMonitor
import com.cardiopulse.app.core.ppg.PpgFailureReason
import com.cardiopulse.app.core.ppg.PpgGuidanceState
import com.cardiopulse.app.core.ppg.PpgOutcome
import com.cardiopulse.app.core.ppg.RhythmAssessment
import com.cardiopulse.app.ui.components.PpgWaveformCanvas
import com.cardiopulse.app.ui.components.labelRes
import com.cardiopulse.app.ui.theme.ArterialCrimson
import com.cardiopulse.app.ui.theme.StatusCritical
import com.cardiopulse.app.ui.theme.StatusOptimal
import com.cardiopulse.app.ui.theme.StatusWarning
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun PpgMeasureScreen(
    viewModel: PpgMeasureViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val liveState by viewModel.liveState.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val selectedContext by viewModel.selectedContext.collectAsState()
    val savedSuccess by viewModel.savedSuccess.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val motionMonitor = remember { MotionMonitor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Bind/unbind CameraX + LED Torch + Accelerometer cleanly with measurement state
    DisposableEffect(liveState.isMeasuring, hasCameraPermission) {
        var cameraProvider: ProcessCameraProvider? = null
        if (liveState.isMeasuring && hasCameraPermission) {
            motionMonitor.start()
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(320, 240),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                    // Stabilize frame rate to 30 FPS and disable optical jitter where supported
                    Camera2Interop.Extender(analysisBuilder).apply {
                        setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(30, 30)
                        )
                    }

                    val imageAnalysis = analysisBuilder.build()
                    imageAnalysis.setAnalyzer(
                        cameraExecutor,
                        CameraPpgAnalyzer { frameSample ->
                            viewModel.onCameraFrame(frameSample, motionMonitor.currentMotionRms)
                        }
                    )

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageAnalysis
                    )
                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(true)
                    }
                } catch (_: Exception) {
                    viewModel.stopMeasurement()
                }
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            motionMonitor.stop()
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
                // Safe cleanup
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopMeasurement()
            cameraExecutor.shutdown()
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = liveState.progress,
        label = "ppgProgress"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.ppg_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = stringResource(R.string.ppg_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!hasCameraPermission) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            tint = ArterialCrimson,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.ppg_permission_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.ppg_permission_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = ArterialCrimson)
                        ) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }
                }
            }
        } else {
            // Live PPG Dial + Progress Ring + Fingertip Optical Ring
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(196.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 10.dp,
                                color = when {
                                    liveState.sqiScore >= 75 -> StatusOptimal
                                    liveState.sqiScore >= 45 -> StatusWarning
                                    else -> ArterialCrimson
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            val fingerTint = Color(
                                red = (liveState.meanRedPreview / 255f).coerceIn(0.12f, 1f),
                                green = 0.08f,
                                blue = 0.14f,
                                alpha = if (liveState.isMeasuring) 0.16f else 0.06f
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .size(164.dp)
                                    .clip(CircleShape)
                                    .background(fingerTint)
                                    .border(
                                        width = 2.dp,
                                        color = ArterialCrimson.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    tint = ArterialCrimson,
                                    modifier = Modifier.size(26.dp)
                                )
                                Text(
                                    text = liveState.instantBpm?.toString() ?: "--",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.bpm_unit),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (liveState.isMeasuring) {
                                    Text(
                                        text = "${liveState.elapsedSeconds.toInt()}s / ${liveState.targetSeconds.toInt()}s",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Real-time Coaching Pill
                        GuidanceBanner(guidance = liveState.guidance)

                        // Signal Quality Index (SQI) Progress Bar
                        if (liveState.isMeasuring) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.sqi_label)}: ${liveState.sqiScore}%",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = "${stringResource(R.string.ppg_perfusion)}: ${"%.2f".format(liveState.perfusionIndexPercent)}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { liveState.sqiScore / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = when {
                                        liveState.sqiScore >= 75 -> StatusOptimal
                                        liveState.sqiScore >= 45 -> StatusWarning
                                        else -> StatusCritical
                                    }
                                )
                            }
                        }

                        // Live PPG Waveform Oscilloscope
                        PpgWaveformCanvas(
                            samples = liveState.waveformWindow,
                            sqiScore = liveState.sqiScore
                        )

                        // Start / Stop Button
                        if (!liveState.isMeasuring) {
                            Button(
                                onClick = { viewModel.startMeasurement() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ArterialCrimson)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ppg_start))
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.stopMeasurement() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ppg_stop))
                            }
                        }
                    }
                }
            }

            // Outcome Card (Verified High-Precision OR Explicit Rejection)
            outcome?.let { result ->
                item {
                    when (result) {
                        is PpgOutcome.Verified -> VerifiedPpgResultCard(
                            result = result,
                            selectedContext = selectedContext,
                            savedSuccess = savedSuccess,
                            onSelectContext = viewModel::selectContext,
                            onSave = { viewModel.saveVerifiedOutcome() },
                            onRetry = { viewModel.startMeasurement() }
                        )
                        is PpgOutcome.Rejected -> RejectedPpgCard(
                            rejected = result,
                            onRetry = { viewModel.startMeasurement() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceBanner(guidance: PpgGuidanceState) {
    val (textRes, accentColor) = when (guidance) {
        PpgGuidanceState.IDLE, PpgGuidanceState.PLACE_FINGER ->
            R.string.ppg_guide_place_finger to MaterialTheme.colorScheme.primary
        PpgGuidanceState.PRESSING_TOO_HARD ->
            R.string.ppg_guide_too_hard to StatusWarning
        PpgGuidanceState.AMBIENT_LIGHT_LEAK ->
            R.string.ppg_guide_too_light to StatusWarning
        PpgGuidanceState.EXCESSIVE_MOTION ->
            R.string.ppg_guide_motion to StatusCritical
        PpgGuidanceState.CALIBRATING ->
            R.string.ppg_guide_calibrating to MaterialTheme.colorScheme.secondary
        PpgGuidanceState.SIGNAL_EXCELLENT ->
            R.string.ppg_guide_excellent to StatusOptimal
        PpgGuidanceState.SIGNAL_GOOD ->
            R.string.ppg_guide_good to StatusOptimal
        PpgGuidanceState.SIGNAL_WEAK ->
            R.string.ppg_guide_weak to StatusWarning
    }

    Surface(
        color = accentColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(textRes),
            color = accentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifiedPpgResultCard(
    result: PpgOutcome.Verified,
    selectedContext: MeasurementContext,
    savedSuccess: Boolean,
    onSelectContext: (MeasurementContext) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = StatusOptimal
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ppg_result_verified),
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusOptimal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "${result.bpm} ${stringResource(R.string.bpm_unit)}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            if (result.confidence == ConfidenceLevel.HIGH) {
                                R.string.ppg_confidence_high
                            } else {
                                R.string.ppg_confidence_medium
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusOptimal
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${stringResource(R.string.sqi_label)}: ${result.sqiScore}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "${stringResource(R.string.hrv_label)}: ${result.hrvRmssdMs.toInt()} ${stringResource(R.string.ms_unit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Dual-method scientific verification telemetry
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${stringResource(R.string.ppg_time_bpm)}: ${"%.1f".format(result.timeDomainBpm)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "${stringResource(R.string.ppg_freq_bpm)}: ${"%.1f".format(result.spectralBpm)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "SDNN: ${result.hrvSdnnMs.toInt()}ms",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Rhythm regularity banner
            val isIrregular = result.rhythmAssessment == RhythmAssessment.IRREGULAR_DETECTED
            Surface(
                color = (if (isIrregular) StatusWarning else StatusOptimal).copy(alpha = 0.13f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isIrregular) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (isIrregular) StatusWarning else StatusOptimal
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (isIrregular) R.string.ppg_rhythm_irregular else R.string.ppg_rhythm_regular
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Context Chips
            Text(
                text = stringResource(R.string.context_label),
                style = MaterialTheme.typography.labelMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MeasurementContext.entries.forEach { ctx ->
                    FilterChip(
                        selected = selectedContext == ctx,
                        onClick = { onSelectContext(ctx) },
                        label = { Text(stringResource(ctx.labelRes())) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ppg_retry))
                }
                Button(
                    onClick = onSave,
                    enabled = !savedSuccess,
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusOptimal)
                ) {
                    Text(
                        text = if (savedSuccess) "✓" else stringResource(R.string.ppg_save_reading)
                    )
                }
            }
        }
    }
}

@Composable
private fun RejectedPpgCard(
    rejected: PpgOutcome.Rejected,
    onRetry: () -> Unit
) {
    val reasonRes = when (rejected.reason) {
        PpgFailureReason.EXCESSIVE_MOTION -> R.string.ppg_reject_motion
        PpgFailureReason.LOW_SIGNAL_QUALITY -> R.string.ppg_reject_low_sqi
        PpgFailureReason.METHOD_DISAGREEMENT -> R.string.ppg_reject_disagreement
        PpgFailureReason.INSUFFICIENT_DURATION -> R.string.ppg_reject_insufficient
    }

    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(StatusCritical.copy(alpha = 0.08f))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = StatusCritical
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ppg_result_rejected_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusCritical
                )
            }
            Text(
                text = stringResource(reasonRes),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${stringResource(R.string.sqi_label)}: ${rejected.sqiScore}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ArterialCrimson)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ppg_retry))
            }
        }
    }
}
