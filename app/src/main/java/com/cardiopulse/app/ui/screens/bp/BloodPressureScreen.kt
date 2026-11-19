package com.cardiopulse.app.ui.screens.bp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cardiopulse.app.R
import com.cardiopulse.app.core.bp.BleConnectionState
import com.cardiopulse.app.core.bp.BleDiscoveredDevice
import com.cardiopulse.app.core.bp.BloodPressureClassifier
import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.core.bp.BpMeasurementArm
import com.cardiopulse.app.core.bp.BpMeasurementPosture
import com.cardiopulse.app.core.bp.BpValidationError
import com.cardiopulse.app.core.bp.BpValidationResult
import com.cardiopulse.app.core.bp.ParsedBleBloodPressure
import com.cardiopulse.app.ui.components.labelRes
import com.cardiopulse.app.ui.components.toColor
import com.cardiopulse.app.ui.theme.ArterialCrimson
import com.cardiopulse.app.ui.theme.ClinicalBlue
import com.cardiopulse.app.ui.theme.StatusCritical
import com.cardiopulse.app.ui.theme.StatusLow
import com.cardiopulse.app.ui.theme.StatusOptimal
import com.cardiopulse.app.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodPressureScreen(
    viewModel: BloodPressureViewModel,
    guideline: BpGuideline,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val bleState by viewModel.bleConnectionState.collectAsState()
    val connectedName by viewModel.bleConnectedDeviceName.collectAsState()
    val discoveredDevices by viewModel.bleDiscoveredDevices.collectAsState()
    val liveCuffPressure by viewModel.liveCuffPressureMmHg.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.bp_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.bp_tab_ble), maxLines = 1) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.bp_tab_manual), maxLines = 1) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.bp_tab_protocol), maxLines = 1) }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                0 -> item {
                    BleMonitorTab(
                        bleState = bleState,
                        connectedName = connectedName,
                        devices = discoveredDevices,
                        liveCuffMmHg = liveCuffPressure,
                        latestBleReading = uiState.latestBleReading,
                        guideline = guideline,
                        onStartScan = viewModel::startBleScan,
                        onStopScan = viewModel::stopBleScan,
                        onConnect = viewModel::connectBleDevice,
                        onDisconnect = viewModel::disconnectBle,
                        onSaveBleReading = viewModel::saveBleReading
                    )
                }
                1 -> item {
                    SmartManualEntryTab(
                        uiState = uiState,
                        guideline = guideline,
                        validation = viewModel.validateCurrentInputs(guideline),
                        onSystolicChange = viewModel::updateSystolic,
                        onDiastolicChange = viewModel::updateDiastolic,
                        onPulseChange = viewModel::updatePulse,
                        onNotesChange = viewModel::updateNotes,
                        onSelectArm = viewModel::selectArm,
                        onSelectPosture = viewModel::selectPosture,
                        onToggleAverageMode = viewModel::toggleAverageMode,
                        onClearFirstReading = viewModel::clearFirstReading,
                        onSubmit = { viewModel.submitManualReading(guideline) }
                    )
                }
                2 -> item {
                    MedicalProtocolTab(
                        secondsRemaining = uiState.restTimerSecondsRemaining,
                        isRunning = uiState.isRestTimerRunning,
                        onStartTimer = viewModel::startRestTimer,
                        onResetTimer = viewModel::resetRestTimer
                    )
                }
            }
        }
    }
}

@Composable
private fun BleMonitorTab(
    bleState: BleConnectionState,
    connectedName: String?,
    devices: List<BleDiscoveredDevice>,
    liveCuffMmHg: Int?,
    latestBleReading: ParsedBleBloodPressure?,
    guideline: BpGuideline,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSaveBleReading: () -> Unit
) {
    val context = LocalContext.current
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var hasBlePermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasBlePermissions = grants.values.all { it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Scientific Transparency Banner
        Surface(
            color = ClinicalBlue.copy(alpha = 0.10f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = ClinicalBlue)
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.bp_transparency_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ClinicalBlue
                    )
                    Text(
                        text = stringResource(R.string.bp_transparency_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (!hasBlePermissions) {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = ClinicalBlue,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = stringResource(R.string.ble_permission_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.ble_permission_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { permLauncher.launch(requiredPermissions) }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }
        } else {
            // Connection & Scan Control Card
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val statusText = when (bleState) {
                        BleConnectionState.IDLE, BleConnectionState.DISCONNECTED, BleConnectionState.ERROR ->
                            stringResource(R.string.ble_status_idle)
                        BleConnectionState.SCANNING -> stringResource(R.string.ble_status_scanning)
                        BleConnectionState.CONNECTING, BleConnectionState.CONNECTED_DISCOVERING ->
                            stringResource(R.string.ble_status_connecting)
                        BleConnectionState.READY_FOR_MEASUREMENT ->
                            "${connectedName.orEmpty()} • ${stringResource(R.string.ble_status_ready)}"
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (bleState == BleConnectionState.READY_FOR_MEASUREMENT) {
                                Icons.Rounded.BluetoothConnected
                            } else {
                                Icons.AutoMirrored.Rounded.BluetoothSearching
                            },
                            contentDescription = null,
                            tint = if (bleState == BleConnectionState.READY_FOR_MEASUREMENT) StatusOptimal else ClinicalBlue
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = statusText, style = MaterialTheme.typography.titleMedium)
                    }

                    // Live cuff inflation pressure (UUID 0x2A36)
                    liveCuffMmHg?.let { cuff ->
                        Surface(
                            color = ArterialCrimson.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.ble_cuff_live),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "$cuff ${stringResource(R.string.mmhg_unit)}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = ArterialCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    when (bleState) {
                        BleConnectionState.SCANNING -> {
                            OutlinedButton(
                                onClick = onStopScan,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(stringResource(R.string.ble_scan_stop))
                            }
                        }
                        BleConnectionState.CONNECTING,
                        BleConnectionState.CONNECTED_DISCOVERING,
                        BleConnectionState.READY_FOR_MEASUREMENT -> {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(stringResource(R.string.ble_disconnect))
                            }
                        }
                        else -> {
                            Button(
                                onClick = onStartScan,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClinicalBlue)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.BluetoothSearching, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ble_scan_start))
                            }
                        }
                    }

                    // Discovered BLE Monitors List
                    if (devices.isEmpty() && bleState == BleConnectionState.SCANNING) {
                        Text(
                            text = stringResource(R.string.ble_no_devices),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        devices.forEach { dev ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dev.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "${dev.address} • ${dev.rssi} dBm" +
                                                if (dev.advertisesBpService) " • GATT 0x1810" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { onConnect(dev) },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(stringResource(R.string.ble_connect))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Received BLE Reading Card with Device Hardware Status Flags
            latestBleReading?.let { reading ->
                val cls = BloodPressureClassifier.classify(reading.systolicMmHg, reading.diastolicMmHg, guideline)
                val accent = cls.severity.toColor()
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${reading.systolicMmHg}/${reading.diastolicMmHg} ${stringResource(R.string.mmhg_unit)}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(color = accent.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                                Text(
                                    text = stringResource(cls.category.labelRes()),
                                    color = accent,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        reading.pulseBpm?.let { pulse ->
                            Text(
                                text = "${stringResource(R.string.nav_pulse)}: $pulse ${stringResource(R.string.bpm_unit)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (reading.statusFlags.bodyMovementDetected) {
                            BleFlagWarning(stringResource(R.string.ble_warn_movement))
                        }
                        if (reading.statusFlags.cuffTooLoose || reading.statusFlags.improperPosition) {
                            BleFlagWarning(stringResource(R.string.ble_warn_cuff))
                        }
                        if (reading.statusFlags.irregularPulseDetected) {
                            BleFlagWarning(stringResource(R.string.ble_warn_irregular))
                        }

                        Button(
                            onClick = onSaveBleReading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusOptimal),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.bp_save_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BleFlagWarning(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(StatusWarning.copy(alpha = 0.15f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StatusWarning)
        Spacer(Modifier.width(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SmartManualEntryTab(
    uiState: BpScreenUiState,
    guideline: BpGuideline,
    validation: BpValidationResult?,
    onSystolicChange: (String) -> Unit,
    onDiastolicChange: (String) -> Unit,
    onPulseChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSelectArm: (BpMeasurementArm) -> Unit,
    onSelectPosture: (BpMeasurementPosture) -> Unit,
    onToggleAverageMode: (Boolean) -> Unit,
    onClearFirstReading: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 2-Reading Cardiologist Averaging Mode Toggle
        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.bp_avg_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = uiState.twoReadingAverageMode,
                        onCheckedChange = onToggleAverageMode
                    )
                }
                Text(
                    text = stringResource(R.string.bp_avg_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.firstReading?.let { first ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ClinicalBlue.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stringResource(R.string.bp_avg_first_recorded)}: ${first.systolic}/${first.diastolic}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ClinicalBlue
                        )
                        TextButton(onClick = onClearFirstReading) {
                            Text(stringResource(R.string.bp_avg_clear))
                        }
                    }
                }
            }
        }

        // Numeric Entry Card
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.systolicInput,
                        onValueChange = onSystolicChange,
                        label = { Text(stringResource(R.string.bp_systolic_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = uiState.diastolicInput,
                        onValueChange = onDiastolicChange,
                        label = { Text(stringResource(R.string.bp_diastolic_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = uiState.pulseInput,
                    onValueChange = onPulseChange,
                    label = { Text(stringResource(R.string.bp_pulse_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Clinical Classification Preview OR Physiological Validation Error
                when (validation) {
                    is BpValidationResult.Valid -> {
                        val cls = validation.classification
                        val accent = cls.severity.toColor()
                        Surface(
                            color = accent.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(cls.category.labelRes()),
                                    color = accent,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${stringResource(R.string.map_label)}: ${cls.mapMmHg}  •  ${stringResource(R.string.pp_label)}: ${cls.pulsePressureMmHg} ${stringResource(R.string.mmhg_unit)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        if (cls.isEmergencyCrisis) {
                            Surface(
                                color = StatusCritical.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Rounded.Error, contentDescription = null, tint = StatusCritical)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = stringResource(R.string.bp_crisis_alert_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = StatusCritical
                                        )
                                        Text(
                                            text = stringResource(R.string.bp_crisis_alert_body),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        } else if (cls.isHypotension) {
                            Surface(
                                color = StatusLow.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.bp_hypotension_alert_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    is BpValidationResult.Invalid -> {
                        val errRes = when (validation.error) {
                            BpValidationError.OUT_OF_PHYSIOLOGICAL_RANGE -> R.string.bp_err_invalid_range
                            BpValidationError.DIASTOLIC_TOO_CLOSE_OR_ABOVE_SYSTOLIC -> R.string.bp_err_dia_ge_sys
                        }
                        Text(
                            text = stringResource(errRes),
                            color = StatusCritical,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    null -> Unit
                }

                // Arm Selection
                Text(stringResource(R.string.bp_arm_label), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.selectedArm == BpMeasurementArm.LEFT,
                        onClick = { onSelectArm(BpMeasurementArm.LEFT) },
                        label = { Text(stringResource(R.string.bp_arm_left)) }
                    )
                    FilterChip(
                        selected = uiState.selectedArm == BpMeasurementArm.RIGHT,
                        onClick = { onSelectArm(BpMeasurementArm.RIGHT) },
                        label = { Text(stringResource(R.string.bp_arm_right)) }
                    )
                }

                // Posture Selection
                Text(stringResource(R.string.bp_posture_label), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.selectedPosture == BpMeasurementPosture.SEATED,
                        onClick = { onSelectPosture(BpMeasurementPosture.SEATED) },
                        label = { Text(stringResource(R.string.bp_posture_seated)) }
                    )
                    FilterChip(
                        selected = uiState.selectedPosture == BpMeasurementPosture.LYING,
                        onClick = { onSelectPosture(BpMeasurementPosture.LYING) },
                        label = { Text(stringResource(R.string.bp_posture_lying)) }
                    )
                }

                OutlinedTextField(
                    value = uiState.notesInput,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(R.string.bp_notes_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                val buttonText = when {
                    !uiState.twoReadingAverageMode -> stringResource(R.string.bp_save_btn)
                    uiState.firstReading == null -> stringResource(R.string.bp_avg_add_first)
                    else -> stringResource(R.string.bp_avg_add_second)
                }

                Button(
                    onClick = onSubmit,
                    enabled = validation is BpValidationResult.Valid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ArterialCrimson)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(buttonText)
                }

                if (uiState.saveSuccessCounter > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StatusOptimal)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.bp_saved_msg),
                            color = StatusOptimal,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicalProtocolTab(
    secondsRemaining: Int,
    isRunning: Boolean,
    onStartTimer: () -> Unit,
    onResetTimer: () -> Unit
) {
    val checkedStates = remember { mutableStateListOf(false, false, false, false, false) }
    val checklistRes = listOf(
        R.string.bp_check_1,
        R.string.bp_check_2,
        R.string.bp_check_3,
        R.string.bp_check_4,
        R.string.bp_check_5
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 5-Minute Clinical Rest Timer
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, contentDescription = null, tint = ClinicalBlue)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bp_rest_timer_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                val mins = secondsRemaining / 60
                val secs = secondsRemaining % 60
                Text(
                    text = "%02d:%02d".format(mins, secs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (secondsRemaining == 0) StatusOptimal else MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStartTimer,
                        enabled = !isRunning,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.bp_rest_timer_start))
                    }
                    OutlinedButton(
                        onClick = onResetTimer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.bp_rest_timer_reset))
                    }
                }
            }
        }

        // ESC/ESH Clinical Accuracy Checklist
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.bp_checklist_title),
                    style = MaterialTheme.typography.titleMedium
                )
                checklistRes.forEachIndexed { idx, resId ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = checkedStates[idx],
                            onCheckedChange = { checkedStates[idx] = it }
                        )
                        Text(
                            text = stringResource(resId),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
