package org.opencell.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** How [Bonder.bond] ended. */
internal enum class BondResult { BONDED, FAILED, TIMED_OUT, NOT_STARTED, LINK_LOST }

/**
 * Bonds with one device: `createBond()` (Android shows its passkey dialog;
 * the user types the code from the terminal's OLED), then waits for
 * [BluetoothDevice.ACTION_BOND_STATE_CHANGED] to say BONDED, or NONE when
 * the dialog was cancelled, the code was wrong or the terminal refused.
 * Used once per connect by [GattConnection].
 */
@SuppressLint("MissingPermission") // GattConnector checks BLUETOOTH_CONNECT first
internal class Bonder(private val context: Context, private val device: BluetoothDevice) {
    private val result = CompletableDeferred<BondResult>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (d?.address != device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> result.complete(BondResult.BONDED)
                BluetoothDevice.BOND_NONE -> result.complete(BondResult.FAILED)
            }
        }
    }

    /** The link dropped: stop waiting. */
    fun abort() {
        result.complete(BondResult.LINK_LOST)
    }

    suspend fun bond(timeout: Duration): BondResult {
        // Exported: the broadcast comes from the Bluetooth process, not system_server,
        // and only the system may send it (it is protected), so nothing else can fake it.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return BondResult.BONDED
                BluetoothDevice.BOND_BONDING -> Unit // already under way (Android started it itself)
                else -> if (!device.createBond()) return BondResult.NOT_STARTED
            }
            return withTimeoutOrNull(timeout) { result.await() } ?: BondResult.TIMED_OUT
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
