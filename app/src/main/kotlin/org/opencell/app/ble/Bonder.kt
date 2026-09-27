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

// BluetoothDevice.EXTRA_REASON and its UNBOND_REASON_* values, hidden in the SDK: best effort.
private const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"
private const val UNBOND_REASON_AUTH_CANCELED = 3
private const val UNBOND_REASON_REMOTE_DEVICE_DOWN = 4
private const val UNBOND_REASON_AUTH_TIMEOUT = 6
private const val UNBOND_REASON_REPEATED_ATTEMPTS = 7

/** What to tell the user when a bond ended in NONE, given Android's [Bonder.failReason]. */
internal fun bondFailureMessage(reason: Int?): String = when (reason) {
    UNBOND_REASON_AUTH_CANCELED -> "pairing was cancelled"
    UNBOND_REASON_AUTH_TIMEOUT -> "pairing timed out"
    UNBOND_REASON_REPEATED_ATTEMPTS -> "the terminal is refusing pairing after 3 wrong codes: try again in a minute"
    UNBOND_REASON_REMOTE_DEVICE_DOWN -> "the link was lost during pairing"
    else -> "pairing failed or was cancelled (a wrong code also changes the terminal's code)"
}

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

    /** Android's reason when the bond ended in NONE (see [bondFailureMessage]), if it gave one. */
    @Volatile
    var failReason: Int? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (d?.address != device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> result.complete(BondResult.BONDED)
                BluetoothDevice.BOND_NONE -> {
                    if (intent.hasExtra(EXTRA_REASON)) failReason = intent.getIntExtra(EXTRA_REASON, 0)
                    result.complete(BondResult.FAILED)
                }
            }
        }
    }

    /** The link dropped: stop waiting. */
    fun abort() {
        result.complete(BondResult.LINK_LOST)
    }

    suspend fun bond(timeout: Duration): BondResult {
        // Aborted already: the link is gone, so don't start a system pairing on it.
        if (result.isCompleted) return result.await()
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
