package org.opencell.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.OpenCellApplication
import org.opencell.core.link.ConnectException
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingException
import org.opencell.core.link.PairingProblem
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.GattContract
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBluetoothGatt

/**
 * [GattConnector] follows the adapter: Bluetooth turning off drops the live
 * connection the way a disconnect does (Android itself says nothing on the
 * GATT client), and turning back on asks for a reconnect. It bonds before
 * GATT setup when the phone has no bond, and turns auth refusals during setup
 * into [PairingException]s; Bluetooth turning off mid-pairing is link loss.
 */
@RunWith(AndroidJUnit4::class)
class GattConnectorTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val closedReasons = mutableListOf<String>()
    private var bluetoothOn = 0
    private var pairing = 0
    private var gatt: BluetoothGatt? = null

    /** Bluetooth turns off inside connectGatt, before open() has the client in hand. */
    private var turnOffDuringConnectGatt = false

    private val events = object : ConnectionEvents {
        override fun onDownlink(payload: ByteArray) = Unit
        override fun onStatus(raw: ByteArray) = Unit
        override fun onEvent(raw: ByteArray) = Unit
        override fun onPairing() {
            pairing++
        }
        override fun onClosed(reason: String) {
            closedReasons += reason
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice(ADDRESS)
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED) // unless a test pairs
        shadowOf(device).setGattConnectionInterceptor { g ->
            gatt = g
            if (turnOffDuringConnectGatt) adapterState(BluetoothAdapter.STATE_TURNING_OFF)
            val service = terminalService()
            shadowOf(g).addDiscoverableService(service)
            service.characteristics.forEach { shadowOf(g).allowCharacteristicNotification(it) }
            shadowOf(g).notifyConnection(ADDRESS)
        }
    }

    @After
    fun tearDown() {
        ServiceNotBoundGatt.notBound = false
        AuthRefusingGatt.refuseWith = 0
    }

    private fun connect(): Pair<GattConnector, Connection> {
        val connector = GattConnector(app) { bluetoothOn++ }
        return connector to runBlocking { connector.connect(LinkTarget(ADDRESS, null), events) }
    }

    private fun adapterState(state: Int) {
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The phone has no bond; createBond() starts one, which then ends as [bondStates] say. */
    private fun unbonded() {
        val device = adapter.getRemoteDevice(ADDRESS)
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(device).setCreatedBond(true)
    }

    private fun bondState(state: Int) {
        val device = adapter.getRemoteDevice(ADDRESS)
        shadowOf(device).setBondState(state)
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
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

    @Test
    fun bluetoothTurningOffBeforeOpenHasTheClientStillClosesIt() {
        turnOffDuringConnectGatt = true
        val connector = GattConnector(app) { bluetoothOn++ }
        val failed = runCatching { runBlocking { connector.connect(LinkTarget(ADDRESS, null), events) } }
        assertTrue(failed.exceptionOrNull() is ConnectException)
        assertTrue("the half-open client is closed, not left holding the terminal", shadowOf(gatt).isClosed)
        assertTrue("never ready, so no onClosed", closedReasons.isEmpty())
    }

    @Test
    fun aLateDisconnectAfterBluetoothTurnedOffIsNotReportedAgain() {
        val (_, c) = connect()
        val callback = shadowOf(gatt).gattCallback
        adapterState(BluetoothAdapter.STATE_TURNING_OFF)
        callback.onConnectionStateChange(gatt, 0x16, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(listOf("Bluetooth turned off"), closedReasons)
        assertEquals(WriteResult.NotConnected, runBlocking { c.writeCommand(Command.Reject.encode()) })
    }

    /** The Fold 7's "write not started (Bluetooth service not bound)": the client is dead, so the link drops. */
    @Test
    @Config(shadows = [ServiceNotBoundGatt::class])
    fun aWriteTheStackCanNoLongerServeDropsTheLinkAndIsNotConnected() {
        val (_, c) = connect()
        ServiceNotBoundGatt.notBound = true
        assertEquals(WriteResult.NotConnected, runBlocking { c.writeCommand(Command.Reject.encode()) })
        assertEquals(1, closedReasons.size)
        assertTrue(closedReasons.single(), closedReasons.single().startsWith("Bluetooth is off"))
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aPhoneWithoutABondPairsBeforeSettingUp() = runTest {
        unbonded()
        val connector = GattConnector(app)
        val c = async { connector.connect(LinkTarget(ADDRESS, null), events) }
        runCurrent()
        assertEquals(1, pairing)
        assertFalse("waits for the user to type the code", c.isCompleted)
        bondState(BluetoothDevice.BOND_BONDING)
        bondState(BluetoothDevice.BOND_BONDED)
        assertEquals(WriteResult.Accepted, c.await().writeCommand(Command.Reject.encode()))
    }

    @Test
    fun aBondedPhoneDoesNotPair() {
        connect()
        assertEquals(0, pairing)
    }

    @Test
    fun aCancelledPairingIsAPairingFailure() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_NONE)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue(shadowOf(gatt).isClosed)
    }

    /** Not a failed pairing: LinkManager must retry (once Bluetooth is back), not wait for the user. */
    @Test
    fun bluetoothTurningOffWhilePairingIsALinkLoss() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        shadowOf(adapter).setEnabled(false)
        adapterState(BluetoothAdapter.STATE_TURNING_OFF)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is ConnectException)
        assertTrue(shadowOf(gatt).isClosed)
        assertTrue("never ready, so no onClosed", closedReasons.isEmpty())
    }

    /** The stack tearing down may end the bond (NONE) before TURNING_OFF reaches us. */
    @Test
    fun aBondEndedByBluetoothTurningOffIsALinkLoss() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        shadowOf(adapter).setEnabled(false)
        bondState(BluetoothDevice.BOND_NONE)
        adapterState(BluetoothAdapter.STATE_TURNING_OFF)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is ConnectException)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    @Config(shadows = [AuthRefusingGatt::class])
    fun anAuthRefusalWithAnOldBondIsAStaleBond() {
        AuthRefusingGatt.refuseWith = GattContract.ATT_ERR_INSUFFICIENT_AUTHENTICATION
        val e = runCatching { connect() }.exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.STALE_BOND)
        assertEquals(0, pairing)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    @Config(shadows = [AuthRefusingGatt::class])
    fun anAuthRefusalRightAfterPairingIsAFailedPairing() = runTest {
        unbonded()
        AuthRefusingGatt.refuseWith = GattContract.ATT_ERR_INSUFFICIENT_ENCRYPTION
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_BONDED)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
    }

    @Test
    @Config(shadows = [AuthRefusingGatt::class])
    fun otherSetupFailuresAreNotAboutPairing() {
        AuthRefusingGatt.refuseWith = GattContract.ATT_ERR_NOT_NOW
        val e = runCatching { connect() }.exceptionOrNull()
        assertTrue("$e", e is ConnectException)
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

/** [ShadowBluetoothGatt] whose writes can be made to fail to start with ERROR_PROFILE_SERVICE_NOT_BOUND. */
@Implements(BluetoothGatt::class)
class ServiceNotBoundGatt : ShadowBluetoothGatt() {
    @Implementation(minSdk = 33)
    override fun writeCharacteristic(c: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int =
        if (notBound) BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND else super.writeCharacteristic(c, value, writeType)

    companion object {
        @Volatile
        var notBound = false
    }
}

/** [ShadowBluetoothGatt] whose CCCD writes the terminal can answer with an ATT error ([refuseWith], 0 = accept). */
@Implements(BluetoothGatt::class)
class AuthRefusingGatt : ShadowBluetoothGatt() {
    @RealObject
    private lateinit var real: BluetoothGatt

    @Implementation(minSdk = 33)
    override fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int {
        if (refuseWith == 0) return super.writeDescriptor(d, value)
        gattCallback.onDescriptorWrite(real, d, refuseWith)
        return BluetoothStatusCodes.SUCCESS
    }

    companion object {
        @Volatile
        var refuseWith = 0
    }
}
