package org.opencell.app.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import kotlin.time.Duration.Companion.seconds

/** [Bonder] against Robolectric's BluetoothDevice and ACTION_BOND_STATE_CHANGED broadcasts. */
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
