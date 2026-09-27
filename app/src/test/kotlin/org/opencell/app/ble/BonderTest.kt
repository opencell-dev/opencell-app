package org.opencell.app.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import kotlin.time.Duration.Companion.seconds

/** [Bonder] against Robolectric's BluetoothDevice and ACTION_BOND_STATE_CHANGED broadcasts. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy, currentTime
@RunWith(AndroidJUnit4::class)
class BonderTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val terminal = ShadowBluetoothDevice.newInstance("44:B1:76:AD:04:8A")
    private val other = ShadowBluetoothDevice.newInstance("44:B1:76:AE:20:66")

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(terminal).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(terminal).setCreatedBond(true)
    }

    private fun broadcast(device: BluetoothDevice, state: Int) {
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun bondsWhenAndroidReportsBonded() = runTest {
        val r = async { Bonder(app, terminal).bond(60.seconds) }
        runCurrent()
        broadcast(terminal, BluetoothDevice.BOND_BONDING)
        broadcast(terminal, BluetoothDevice.BOND_BONDED)
        assertEquals(BondResult.BONDED, r.await())
    }

    @Test
    fun aCancelledOrWrongCodeIsAFailure() = runTest {
        val r = async { Bonder(app, terminal).bond(60.seconds) }
        runCurrent()
        broadcast(terminal, BluetoothDevice.BOND_BONDING)
        broadcast(terminal, BluetoothDevice.BOND_NONE)
        assertEquals(BondResult.FAILED, r.await())
    }

    @Test
    fun otherDevicesAreIgnoredUntilTheTimeout() = runTest {
        val r = async { Bonder(app, terminal).bond(60.seconds) }
        runCurrent()
        broadcast(other, BluetoothDevice.BOND_BONDED)
        advanceTimeBy(59_000)
        assertFalse(r.isCompleted)
        advanceTimeBy(2_000)
        assertEquals(BondResult.TIMED_OUT, r.await())
    }

    @Test
    fun anAlreadyBondedPhoneDoesNotPairAgain() = runTest {
        shadowOf(terminal).setBondState(BluetoothDevice.BOND_BONDED)
        shadowOf(terminal).setCreatedBond(false) // createBond would fail: it must not be called
        assertEquals(BondResult.BONDED, Bonder(app, terminal).bond(60.seconds))
    }

    @Test
    fun aBondThatCannotStartIsReported() = runTest {
        shadowOf(terminal).setCreatedBond(false)
        assertEquals(BondResult.NOT_STARTED, Bonder(app, terminal).bond(60.seconds))
    }

    @Test
    fun aBondAlreadyUnderWayIsAwaitedNotRestarted() = runTest {
        shadowOf(terminal).setBondState(BluetoothDevice.BOND_BONDING)
        shadowOf(terminal).setCreatedBond(false)
        val r = async { Bonder(app, terminal).bond(60.seconds) }
        runCurrent()
        broadcast(terminal, BluetoothDevice.BOND_BONDED)
        assertEquals(BondResult.BONDED, r.await())
    }

    @Test
    fun aDroppedLinkStopsTheWait() = runTest {
        val bonder = Bonder(app, terminal)
        val r = async { bonder.bond(60.seconds) }
        runCurrent()
        bonder.abort()
        assertEquals(BondResult.LINK_LOST, r.await())
    }

    @Test
    fun aBonderAbortedBeforeItStartsDoesNotPair() = runTest {
        shadowOf(terminal).setCreatedBond(false) // createBond would say NOT_STARTED: it must not be called
        val bonder = Bonder(app, terminal)
        bonder.abort()
        assertEquals(BondResult.LINK_LOST, bonder.bond(60.seconds))
    }

    @Test
    fun androidsReasonForNoneIsKept() = runTest {
        val bonder = Bonder(app, terminal)
        val r = async { bonder.bond(60.seconds) }
        runCurrent()
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, terminal)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                .putExtra("android.bluetooth.device.extra.REASON", 3),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BondResult.FAILED, r.await())
        assertEquals(3, bonder.failReason)
    }

    @Test
    fun failureReasonsReadForTheUser() {
        assertTrue(bondFailureMessage(3).contains("cancelled"))
        assertTrue(bondFailureMessage(6).contains("timed out"))
        assertTrue(bondFailureMessage(7).contains("try again in a minute"))
        assertTrue(bondFailureMessage(4).contains("move closer and tap Retry"))
        assertTrue(bondFailureMessage(4, dropStatus = 0x05).contains("try again in a minute"))
        assertTrue(bondFailureMessage(null).contains("wrong code"))
    }

    /** Only the terminal's lock-out refusal (0x05, term_ble.c) is blamed on the 3 wrong codes. */
    @Test
    fun aDropDuringPairingIsReadByItsStatus() {
        assertTrue(pairingDropMessage(0x05).contains("after 3 wrong codes"))
        for (status in listOf(0, 0x08, 0x13, 0x3E)) {
            val m = pairingDropMessage(status)
            assertTrue(m, m.contains("move closer and tap Retry"))
            assertFalse(m, m.contains("wrong codes"))
        }
    }

    @Test
    fun theReceiverIsUnregisteredAfterwards() = runTest {
        val r = async { Bonder(app, terminal).bond(60.seconds) }
        runCurrent()
        broadcast(terminal, BluetoothDevice.BOND_BONDED)
        r.await()
        val receivers = shadowOf(app).registeredReceivers.filter {
            it.intentFilter.hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        assertEquals(0, receivers.size)
    }
}
