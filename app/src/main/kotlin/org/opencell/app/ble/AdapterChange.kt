package org.opencell.app.ble

import android.bluetooth.BluetoothAdapter

/** What a Bluetooth adapter state change means for a GATT link (see [adapterChange]). */
enum class AdapterChange {
    /** Classic Bluetooth is going or gone: any GATT client is dead, and Android won't say so on it. */
    LINK_LOST,

    /** Bluetooth is on: a connect can succeed again. */
    AVAILABLE,
}

// BLE-only ("BLE on, Bluetooth off") states: hidden in the SDK, but a phone reports them.
private const val STATE_BLE_TURNING_ON = 14
private const val STATE_BLE_ON = 15
private const val STATE_BLE_TURNING_OFF = 16

/**
 * Maps [BluetoothAdapter.EXTRA_STATE] to what [GattConnector] does. When the
 * adapter turns off, Android cleans up GATT clients without calling
 * `onConnectionStateChange`, so TURNING_OFF must count as the link dropping;
 * OFF and the BLE-only states catch a TURNING_OFF that was missed. TURNING_ON
 * changes nothing: connects only work from ON.
 */
fun adapterChange(state: Int): AdapterChange? = when (state) {
    BluetoothAdapter.STATE_TURNING_OFF,
    BluetoothAdapter.STATE_OFF,
    STATE_BLE_TURNING_ON,
    STATE_BLE_ON,
    STATE_BLE_TURNING_OFF,
    -> AdapterChange.LINK_LOST
    BluetoothAdapter.STATE_ON -> AdapterChange.AVAILABLE
    else -> null
}
