package org.opencell.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.Tmid

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val tmid: Long?,
    val lastSeenMillis: Long,
)

class ScanException(message: String) : Exception(message)

/** Scans for terminals: advertisements carrying the OpenCell service UUID. */
class BleScanner(private val context: Context) {
    /**
     * Emits every advertisement seen (the same device repeatedly) until the
     * collector stops. Fails with [ScanException] if scanning can't start.
     */
    @SuppressLint("MissingPermission") // checked below; the UI requests it first
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        if (!BlePermissions.hasScan(context)) throw ScanException("Nearby devices permission not granted")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ScanException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw ScanException("Bluetooth is off")
        val scanner = adapter.bluetoothLeScanner ?: throw ScanException("Bluetooth is off")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toDevice())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanException(scanError(errorCode)))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattContract.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(filters, settings, callback)
        awaitClose {
            // stopScan throws if Bluetooth was turned off meanwhile.
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDevice(): ScannedDevice {
        // The name is in the scan response (it doesn't fit next to the 128-bit UUID).
        val name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        return ScannedDevice(
            address = device.address,
            name = name,
            rssi = rssi,
            tmid = Tmid.fromDeviceName(name),
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    private fun scanError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Scan registration failed (Android limits apps to 5 scans per 30 s; wait and retry)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning not supported"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth out of resources"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning too frequently; wait 30 s"
        else -> "Scan failed (code $code)"
    }
}
