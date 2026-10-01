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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
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
import org.opencell.core.link.PairingRules
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.Hex
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
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy, currentTime
@RunWith(AndroidJUnit4::class)
class GattConnectorTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val closedReasons = mutableListOf<String>()
    private var bluetoothOn = 0
    private var pairing = 0
    private var bonded = 0
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
        override fun onBonded() {
            bonded++
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
        HeldReadGatt.hold = false
        ServiceNotBoundGatt.notBound = false
        CccdGatt.refuseWith = 0
        CccdGatt.hold = false
        CccdGatt.dropAfterWith = 0
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

    private fun bondState(state: Int, reason: Int? = null) {
        val device = adapter.getRemoteDevice(ADDRESS)
        shadowOf(device).setBondState(state)
        val intent = Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state)
        if (reason != null) intent.putExtra("android.bluetooth.device.extra.REASON", reason)
        app.sendBroadcast(intent)
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
        assertEquals(0, bonded)
        bondState(BluetoothDevice.BOND_BONDED)
        assertEquals(WriteResult.Accepted, c.await().writeCommand(Command.Reject.encode()))
        assertEquals("the code prompt ends once bonded, before the rest of setup", 1, bonded)
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
    @Config(shadows = [CccdGatt::class])
    fun anAuthRefusalWithAnOldBondIsAStaleBond() {
        CccdGatt.refuseWith = GattContract.ATT_ERR_INSUFFICIENT_AUTHENTICATION
        val e = runCatching { connect() }.exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.STALE_BOND)
        assertEquals(0, pairing)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    @Config(shadows = [CccdGatt::class])
    fun anAuthRefusalRightAfterPairingIsAFailedPairing() = runTest {
        unbonded()
        CccdGatt.refuseWith = GattContract.ATT_ERR_INSUFFICIENT_ENCRYPTION
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_BONDED)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
    }

    /** The terminal dropping the link mid-pairing (e.g. locked after 3 wrong codes) ends the wait at once. */
    @Test
    fun theLinkDroppingWhilePairingFailsThePairingAtOnce() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 0x05, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertTrue("not left waiting out the bond timeout", c.isCompleted)
        assertTrue("no virtual time passed", currentTime < 60_000)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue("${e?.message}", e!!.message!!.contains("try again in a minute"))
        assertTrue(shadowOf(gatt).isClosed)
    }

    /** Only 0x05 is the terminal's lock-out refusal: other drops (here a supervision timeout) aren't blamed on it. */
    @Test
    fun anotherDropWhilePairingIsNotBlamedOnTheLockOut() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 0x08, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue("${e?.message}", e!!.message!!.contains("move closer and tap Retry"))
        assertFalse("${e.message}", e.message!!.contains("3 wrong codes"))
    }

    /**
     * One UI may start its own pairing when the terminal lacks the phone's key: a
     * setup op then hangs while the bond is BONDING. That is pairing, not a dead
     * link: wait for it (the passkey dialog is up) instead of dropping the link.
     * Once bonded, that connection is given up as a link loss (not a pairing
     * failure): the timed-out op's callback may still come, so the next attempt
     * is a fresh connection, which is bonded and asks for no code.
     */
    @Test
    @Config(shadows = [CccdGatt::class])
    fun aSystemPairingDuringSetupIsWaitedForNotDropped() = runTest {
        CccdGatt.hold = true
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_BONDING)
        advanceTimeBy(6_000) // past OP_TIMEOUT
        runCurrent()
        assertFalse("waits for the user to type the code", c.isCompleted)
        assertFalse("the link is kept for the pairing", shadowOf(gatt).isClosed)
        assertEquals(1, pairing)

        CccdGatt.hold = false // setting up again on this connection would now succeed
        bondState(BluetoothDevice.BOND_BONDED)
        val e = c.await().exceptionOrNull()
        assertTrue("a link loss, so LinkManager reconnects: $e", e is ConnectException)
        assertEquals(1, bonded)
        val first = gatt!!
        assertTrue("given up, not set up again", shadowOf(first).isClosed)

        // The reconnect: bonded now, so no code prompt. Its first CCCD write is pending
        // when the timed-out op's late callback turns up on the old connection.
        CccdGatt.hold = true
        val again = async { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) }
        runCurrent()
        assertTrue("a fresh GATT client", gatt !== first)
        shadowOf(first).gattCallback.onDescriptorWrite(first, cccd(), GattContract.ATT_ERR_INSUFFICIENT_AUTHENTICATION)
        runCurrent()
        assertFalse("the stale callback completed nothing in the new attempt", again.isCompleted)

        CccdGatt.hold = false
        shadowOf(gatt).gattCallback.onDescriptorWrite(gatt, cccd(), BluetoothGatt.GATT_SUCCESS)
        assertEquals(WriteResult.Accepted, again.await().writeCommand(Command.Reject.encode()))
        assertEquals("no second code prompt", 1, pairing)
    }

    /** ... and a system pairing that fails is a pairing failure (no automatic retry), not a link loss. */
    @Test
    @Config(shadows = [CccdGatt::class])
    fun aSystemPairingThatFailsDuringSetupIsAPairingFailure() = runTest {
        CccdGatt.hold = true
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_BONDING)
        advanceTimeBy(6_000)
        runCurrent()
        bondState(BluetoothDevice.BOND_NONE)
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue(shadowOf(gatt).isClosed)
    }

    /** The link dropping while the system pairs is a pairing failure too, so LinkManager doesn't reconnect into another prompt. */
    @Test
    @Config(shadows = [CccdGatt::class])
    fun aDropWhileTheSystemPairsIsAPairingFailure() = runTest {
        CccdGatt.hold = true
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_BONDING)
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 0x08, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue("${e?.message}", e!!.message!!.contains("move closer and tap Retry"))
    }

    /** Re-encrypting with a key the terminal no longer has: it disconnects with "key missing". */
    @Test
    @Config(shadows = [CccdGatt::class])
    fun aKeyMissingDropDuringACccdWriteIsAStaleBond() = runTest {
        CccdGatt.hold = true
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        assertFalse("the CCCD write is pending", c.isCompleted)
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, PairingRules.HCI_PIN_OR_KEY_MISSING, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.STALE_BOND)
    }

    /** A drop landing between two ops: the next op's "not connected" carries the drop's status. */
    @Test
    @Config(shadows = [CccdGatt::class])
    fun aKeyMissingDropBetweenOpsIsAStaleBond() {
        CccdGatt.dropAfterWith = PairingRules.HCI_PIN_OR_KEY_MISSING
        val e = runCatching { connect() }.exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.STALE_BOND)
    }

    @Test
    fun theRefusalReasonAndroidGivesIsInTheMessage() = runTest {
        unbonded()
        val c = async { runCatching { GattConnector(app).connect(LinkTarget(ADDRESS, null), events) } }
        runCurrent()
        bondState(BluetoothDevice.BOND_NONE, reason = 7) // UNBOND_REASON_REPEATED_ATTEMPTS
        val e = c.await().exceptionOrNull()
        assertTrue("$e", e is PairingException && e.problem == PairingProblem.FAILED)
        assertTrue("${e?.message}", e!!.message!!.contains("try again in a minute"))
    }

    @Test
    @Config(shadows = [CccdGatt::class])
    fun otherSetupFailuresAreNotAboutPairing() {
        CccdGatt.refuseWith = GattContract.ATT_ERR_NOT_NOW
        val e = runCatching { connect() }.exceptionOrNull()
        assertTrue("$e", e is ConnectException)
    }

    /** Contract v3 firmware has no SCAN: the connection still comes up, and reads no scan list. */
    @Test
    fun aTerminalWithoutScanConnectsAndReadsNoScanList() {
        val (_, c) = connect()
        assertEquals(null, runBlocking { c.readScan() })
        c.close()
    }

    /** Contract v4: SCAN is read like STATUS (Android does the long read) and comes back as it is. */
    @Test
    fun aV4TerminalReadsItsScanList() {
        scanValue = byteArrayOf(1, 1, 2, 13, 0, 0)
        val (_, c) = connect()
        assertEquals("01 01 02 0d 00 00", Hex.format(runBlocking { c.readScan() }!!))
        c.close()
    }

    /**
     * Read answers are matched on the characteristic, not just on being a read:
     * a late SCAN answer (say after a read timed out during a system pairing)
     * must not complete a pending STATUS read, which would decode it as status.
     */
    @Test
    @Config(shadows = [HeldReadGatt::class])
    fun aReadAnswerForAnotherCharacteristicDoesNotCompleteThePendingRead() = runTest {
        scanValue = byteArrayOf(1, 1, 2, 13, 0, 0)
        val (_, c) = connect()
        val g = gatt!!
        val service = g.getService(GattContract.SERVICE)
        HeldReadGatt.hold = true
        val read = async { c.readStatus() }
        runCurrent()
        assertFalse("the STATUS read is pending", read.isCompleted)

        val lateScan = ByteArray(27) { 0x5a }
        shadowOf(g).gattCallback.onCharacteristicRead(g, service.getCharacteristic(GattContract.SCAN), lateScan, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse("a SCAN answer doesn't complete a STATUS read", read.isCompleted)

        val status = ByteArray(27) { it.toByte() }
        shadowOf(g).gattCallback.onCharacteristicRead(g, service.getCharacteristic(GattContract.STATUS), status, BluetoothGatt.GATT_SUCCESS)
        assertEquals(Hex.format(status), Hex.format(read.await()!!))
        c.close()
    }

    private fun cccd() = BluetoothGattDescriptor(GattContract.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE)

    /** The SCAN characteristic's value (contract v4); null: a v3 terminal without SCAN. */
    private var scanValue: ByteArray? = null

    private fun terminalService() = BluetoothGattService(GattContract.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ
        addCharacteristic(characteristic(GattContract.UP, write))
        addCharacteristic(characteristic(GattContract.COMMAND, write))
        addCharacteristic(characteristic(GattContract.DOWN, notify))
        addCharacteristic(characteristic(GattContract.STATUS, notify))
        addCharacteristic(characteristic(GattContract.EVENT, notify))
        scanValue?.let { v ->
            // Robolectric answers readCharacteristic with the characteristic's own value.
            @Suppress("DEPRECATION")
            addCharacteristic(characteristic(GattContract.SCAN, BluetoothGattCharacteristic.PROPERTY_READ).apply { value = v })
        }
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

/**
 * [ShadowBluetoothGatt] whose CCCD writes act like the terminal's: answered
 * with an ATT error ([refuseWith], 0 = accept), never answered ([hold]), or
 * accepted and then followed by a disconnect with [dropAfterWith] (0 = none).
 */
@Implements(BluetoothGatt::class)
class CccdGatt : ShadowBluetoothGatt() {
    @RealObject
    private lateinit var real: BluetoothGatt

    @Implementation(minSdk = 33)
    override fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int {
        when {
            hold -> Unit
            refuseWith != 0 -> gattCallback.onDescriptorWrite(real, d, refuseWith)
            dropAfterWith != 0 -> {
                gattCallback.onDescriptorWrite(real, d, BluetoothGatt.GATT_SUCCESS)
                gattCallback.onConnectionStateChange(real, dropAfterWith, BluetoothProfile.STATE_DISCONNECTED)
            }
            else -> return super.writeDescriptor(d, value)
        }
        return BluetoothStatusCodes.SUCCESS
    }

    companion object {
        @Volatile
        var refuseWith = 0

        @Volatile
        var hold = false

        @Volatile
        var dropAfterWith = 0
    }
}

/** [ShadowBluetoothGatt] whose characteristic reads start but aren't answered while [hold] is set. */
@Implements(BluetoothGatt::class)
class HeldReadGatt : ShadowBluetoothGatt() {
    @Implementation
    public override fun readCharacteristic(c: BluetoothGattCharacteristic): Boolean =
        if (hold) true else super.readCharacteristic(c)

    companion object {
        @Volatile
        var hold = false
    }
}
