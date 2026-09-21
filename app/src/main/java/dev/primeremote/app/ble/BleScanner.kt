package dev.primeremote.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dev.primeremote.core.protocol.Uuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class DiscoveredHub(
    val address: String,
    val name: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val lastSeenMs: Long,
)

/** Scans for hubs advertising the SPIKE Prime service. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val _hubs = MutableStateFlow<List<DiscoveredHub>>(emptyList())
    val hubs: StateFlow<List<DiscoveredHub>> = _hubs.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            add(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { add(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android refused the scan — try toggling Bluetooth"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support the required scan mode"
                else -> "Scanning failed (error $errorCode)"
            }
        }
    }

    private fun add(result: ScanResult) {
        val device = result.device ?: return
        val name = try {
            result.scanRecord?.deviceName ?: device.name ?: "SPIKE hub"
        } catch (e: SecurityException) {
            "SPIKE hub"
        }
        val hub = DiscoveredHub(
            address = device.address,
            name = name,
            rssi = result.rssi,
            device = device,
            lastSeenMs = System.currentTimeMillis(),
        )
        _hubs.value = (_hubs.value.filterNot { it.address == hub.address } + hub)
            .sortedByDescending { it.rssi }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _error.value = if (adapter == null) {
                "This phone has no Bluetooth adapter"
            } else {
                "Turn Bluetooth on to look for hubs"
            }
            return
        }
        _error.value = null
        _hubs.value = emptyList()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(Uuids.SERVICE)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            _scanning.value = true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            _error.value = "Could not start scanning: ${e.message}"
        }
    }

    fun stop() {
        if (!_scanning.value) return
        _scanning.value = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    /** Hubs the phone has already been paired/bonded with, so they show up before a scan finds them. */
    fun bondedHubs(): List<DiscoveredHub> = try {
        adapter?.bondedDevices.orEmpty()
            .filter { it.name?.contains("spike", ignoreCase = true) == true || it.name?.contains("hub", ignoreCase = true) == true }
            .map {
                DiscoveredHub(it.address, it.name ?: it.address, Int.MIN_VALUE, it, 0L)
            }
    } catch (e: SecurityException) {
        emptyList()
    }

    fun deviceFor(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "BleScanner"
    }
}
