package org.opencell.app.ble

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each Bluetooth adapter state means for a GATT link ([GattConnector]'s receiver). */
class AdapterChangeTest {
    @Test
    fun turningOffOffAndBleOnlyStatesLoseTheLink() {
        // 13 TURNING_OFF, 10 OFF, and the BLE-only 14 BLE_TURNING_ON, 15 BLE_ON, 16 BLE_TURNING_OFF
        for (state in listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF, 14, 15, 16)) {
            assertEquals("state $state", AdapterChange.LINK_LOST, adapterChange(state))
        }
    }

    @Test
    fun onMakesTheLinkPossibleAgain() {
        assertEquals(AdapterChange.AVAILABLE, adapterChange(BluetoothAdapter.STATE_ON))
    }

    @Test
    fun turningOnAndUnknownStatesChangeNothing() {
        assertNull(adapterChange(BluetoothAdapter.STATE_TURNING_ON))
        assertNull(adapterChange(BluetoothAdapter.ERROR))
        assertNull(adapterChange(99))
    }
}
