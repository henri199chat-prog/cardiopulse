package com.cardiopulse.app.core.bp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Android Bluetooth Low Energy (BLE) GATT client for certified medical Blood Pressure
 * Monitors (GATT Service 0x1810) and Heart Rate Monitors (GATT Service 0x180D).
 *
 * Compatible with standard Bluetooth SIG tensiomètres (Omron, Beurer, Withings, Microlife,
 * A&D, Rossmax, Yuwell, etc.).
 */
class BleBloodPressureManager(private val context: Context) {

    companion object {
        val SERVICE_BLOOD_PRESSURE: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        val CHAR_BP_MEASUREMENT: UUID = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
        val CHAR_INTERMEDIATE_CUFF: UUID = UUID.fromString("00002a36-0000-1000-8000-00805f9b34fb")

        val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        val CCCD_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val KNOWN_BP_KEYWORDS = listOf(
            "omron", "beurer", "withings", "microlife", "rossmax",
            "yuwell", "ihealth", "and", "a&d", "bp", "hem-", "bm", "tension", "cuff"
        )
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var activeScanCallback: ScanCallback? = null
    private val discoveredMap = LinkedHashMap<String, BleDiscoveredDevice>()
    private val pendingDescriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()

    private val _connectionState = MutableStateFlow(BleConnectionState.IDLE)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _liveCuffPressureMmHg = MutableStateFlow<Int?>(null)
    val liveCuffPressureMmHg: StateFlow<Int?> = _liveCuffPressureMmHg.asStateFlow()

    private val _receivedBpReadings = MutableSharedFlow<ParsedBleBloodPressure>(extraBufferCapacity = 8)
    val receivedBpReadings: SharedFlow<ParsedBleBloodPressure> = _receivedBpReadings.asSharedFlow()

    private val _receivedHrReadings = MutableSharedFlow<ParsedBleHeartRate>(extraBufferCapacity = 16)
    val receivedHrReadings: SharedFlow<ParsedBleHeartRate> = _receivedHrReadings.asSharedFlow()

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.ERROR
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        stopScan()
        discoveredMap.clear()
        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val scanRecord = result.scanRecord
                val serviceUuids = scanRecord?.serviceUuids.orEmpty()
                val hasBpService = serviceUuids.contains(ParcelUuid(SERVICE_BLOOD_PRESSURE))
                val hasHrService = serviceUuids.contains(ParcelUuid(SERVICE_HEART_RATE))

                val rawName = try {
                    device.name ?: scanRecord?.deviceName ?: ""
                } catch (_: SecurityException) {
                    scanRecord?.deviceName ?: ""
                }

                val nameLower = rawName.lowercase()
                val looksLikeMedicalMonitor = hasBpService || hasHrService ||
                    KNOWN_BP_KEYWORDS.any { nameLower.contains(it) }

                if (!looksLikeMedicalMonitor) return

                val displayName = rawName.ifBlank { "BLE Monitor (${device.address.takeLast(5)})" }
                discoveredMap[device.address] = BleDiscoveredDevice(
                    address = device.address,
                    name = displayName,
                    rssi = result.rssi,
                    advertisesBpService = hasBpService,
                    advertisesHrService = hasHrService
                )
                _discoveredDevices.value = discoveredMap.values.sortedWith(
                    compareByDescending<BleDiscoveredDevice> { it.advertisesBpService }
                        .thenByDescending { it.rssi }
                )
            }

            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.ERROR
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        activeScanCallback = callback
        try {
            scanner.startScan(null, settings, callback)
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = activeScanCallback ?: return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
            // Ignore permission race during cleanup
        }
        activeScanCallback = null
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.IDLE
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, displayName: String) {
        stopScan()
        disconnect()

        val device: BluetoothDevice = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: run {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        _connectedDeviceName.value = displayName
        _connectionState.value = BleConnectionState.CONNECTING
        _liveCuffPressureMmHg.value = null

        try {
            activeGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        pendingDescriptorQueue.clear()
        _liveCuffPressureMmHg.value = null
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: SecurityException) {
            // Safe cleanup
        }
        activeGatt = null
        _connectedDeviceName.value = null
        _connectionState.value = BleConnectionState.IDLE
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.DISCONNECTED
                _liveCuffPressureMmHg.value = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.CONNECTED_DISCOVERING
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    _liveCuffPressureMmHg.value = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            pendingDescriptorQueue.clear()

            // Subscribe to Blood Pressure Measurement (Indication) + Intermediate Cuff (Notification)
            val bpService = gatt.getService(SERVICE_BLOOD_PRESSURE)
            bpService?.getCharacteristic(CHAR_BP_MEASUREMENT)?.let { bpChar ->
                enqueueSubscription(gatt, bpChar, useIndication = true)
            }
            bpService?.getCharacteristic(CHAR_INTERMEDIATE_CUFF)?.let { cuffChar ->
                enqueueSubscription(gatt, cuffChar, useIndication = false)
            }

            // Also subscribe to Heart Rate Service if exposed by the device
            val hrService = gatt.getService(SERVICE_HEART_RATE)
            hrService?.getCharacteristic(CHAR_HR_MEASUREMENT)?.let { hrChar ->
                enqueueSubscription(gatt, hrChar, useIndication = false)
            }

            if (pendingDescriptorQueue.isEmpty()) {
                _connectionState.value = BleConnectionState.ERROR
            } else {
                writeNextDescriptor(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (pendingDescriptorQueue.isNotEmpty()) {
                writeNextDescriptor(gatt)
            } else {
                _connectionState.value = BleConnectionState.READY_FOR_MEASUREMENT
            }
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicPayload(characteristic.uuid, value)
        }

        // Android 8–12 (API 26–32) callback
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = characteristic.value ?: return
            handleCharacteristicPayload(characteristic.uuid, payload)
        }
    }

    private fun handleCharacteristicPayload(uuid: UUID, payload: ByteArray) {
        when (uuid) {
            CHAR_BP_MEASUREMENT -> {
                _liveCuffPressureMmHg.value = null
                BleBloodPressureParser.parseBloodPressureMeasurement(payload)?.let { parsed ->
                    _receivedBpReadings.tryEmit(parsed)
                }
            }
            CHAR_INTERMEDIATE_CUFF -> {
                BleBloodPressureParser.parseIntermediateCuffPressure(payload)?.let { cuffMmHg ->
                    _liveCuffPressureMmHg.value = cuffMmHg
                }
            }
            CHAR_HR_MEASUREMENT -> {
                BleBloodPressureParser.parseHeartRateMeasurement(payload)?.let { parsedHr ->
                    _receivedHrReadings.tryEmit(parsedHr)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueSubscription(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        useIndication: Boolean
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_DESCRIPTOR) ?: return
            val value = if (useIndication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            pendingDescriptorQueue.addLast(descriptor to value)
        } catch (_: SecurityException) {
            // Handled via connectionState
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val next = pendingDescriptorQueue.removeFirstOrNull() ?: return
        val (descriptor, value) = next
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value)
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.ERROR
        }
    }
}
