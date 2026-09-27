package org.opencell.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.OpenCellApplication
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.GattContract
import org.robolectric.Shadows.shadowOf

/**
 * [GattConnector] follows the adapter: Bluetooth turning off drops the live
 * connection the way a disconnect does (Android itself says nothing on the
 * GATT client), and turning back on asks for a reconnect.
 */
@RunWith(AndroidJUnit4::class)
class GattConnectorTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val closedReasons = mutableListOf<String>()
    private var bluetoothOn = 0
    private var gatt: BluetoothGatt? = null

    private val events = object : ConnectionEvents {
        override fun onDownlink(payload: ByteArray) = Unit
        override fun onStatus(raw: ByteArray) = Unit
        override fun onEvent(raw: ByteArray) = Unit
        override fun onClosed(reason: String) {
            closedReasons += reason
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice(ADDRESS)
        shadowOf(device).setGattConnectionInterceptor { g ->
            gatt = g
            val service = terminalService()
            shadowOf(g).addDiscoverableService(service)
            service.characteristics.forEach { shadowOf(g).allowCharacteristicNotification(it) }
            shadowOf(g).notifyConnection(ADDRESS)
        }
    }

    private fun connect(): Pair<GattConnector, Connection> {
        val connector = GattConnector(app) { bluetoothOn++ }
        return connector to runBlocking { connector.connect(LinkTarget(ADDRESS, null), events) }
    }

    private fun adapterState(state: Int) {
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun bluetoothTurningOffDropsTheLinkAndLaterWritesAreNotConnected() {
        val (_, c) = connect()
        assertEquals(WriteResult.Accepted, runBlocking { c.writeCommand(Command.Reject.encode()) })

        adapterState(BluetoothAdapter.STATE_TURNING_OFF)
        assertEquals(listOf("Bluetooth turned off"), closedReasons)
        assertTrue("the GATT client is closed", shadowOf(gatt).isClosed)
        assertEquals(WriteResult.NotConnected, runBlocking { c.writeCommand(Command.Reject.encode()) })
        assertEquals(WriteResult.NotConnected, runBlocking { c.write(byteArrayOf(1)) })

        adapterState(BluetoothAdapter.STATE_OFF)
        assertEquals("reported once", 1, closedReasons.size)
        assertEquals(0, bluetoothOn)
    }

    @Test
    fun bluetoothOnAsksForAReconnectAndTurningOnDoesNot() {
        connect()
        adapterState(BluetoothAdapter.STATE_TURNING_ON)
        assertEquals(0, bluetoothOn)
        assertTrue(closedReasons.isEmpty())
        adapterState(BluetoothAdapter.STATE_ON)
        assertEquals(1, bluetoothOn)
        assertTrue("an ON doesn't touch a live link", closedReasons.isEmpty())
    }

    private fun terminalService() = BluetoothGattService(GattContract.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ
        addCharacteristic(characteristic(GattContract.UP, write))
        addCharacteristic(characteristic(GattContract.COMMAND, write))
        addCharacteristic(characteristic(GattContract.DOWN, notify))
        addCharacteristic(characteristic(GattContract.STATUS, notify))
        addCharacteristic(characteristic(GattContract.EVENT, notify))
    }

    private fun characteristic(uuid: java.util.UUID, properties: Int) =
        BluetoothGattCharacteristic(
            uuid,
            properties,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(GattContract.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
        }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
