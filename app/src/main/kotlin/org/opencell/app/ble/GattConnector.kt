package org.opencell.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.opencell.core.link.ConnectException
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.Connector
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.GattContract
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "OpenCellGatt"

/**
 * Opens [GattConnection]s to terminals by MAC address. This is the one place
 * the app connects over GATT: BLE pairing/bonding, if the terminal starts to
 * require it, goes here and in [GattConnection.open].
 *
 * It follows the Bluetooth adapter for as long as it lives (the process):
 * Android cleans up GATT clients without any callback when Bluetooth turns
 * off, so on TURNING_OFF ([adapterChange]) the live connection is dropped as
 * if the terminal had disconnected, and [onBluetoothOn] runs once it is back
 * on, to reconnect without waiting out the backoff.
 */
class GattConnector(
    private val context: Context,
    private val onBluetoothOn: () -> Unit = {},
) : Connector {
    /** The connection opened last; dropping one that is already closed does nothing. */
    private val live = AtomicReference<GattConnection?>(null)

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d(TAG, "adapter state $state")
            when (adapterChange(state)) {
                AdapterChange.LINK_LOST -> live.get()?.dropped("Bluetooth turned off")
                AdapterChange.AVAILABLE -> onBluetoothOn()
                null -> Unit
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context.applicationContext,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // system broadcasts still arrive
        )
    }

    override suspend fun connect(target: LinkTarget, events: ConnectionEvents): Connection {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ConnectException("This phone has no Bluetooth")
        if (!BlePermissions.hasConnect(context)) throw ConnectException("Nearby devices permission not granted")
        if (!adapter.isEnabled) throw ConnectException("Bluetooth is off")
        val device = try {
            adapter.getRemoteDevice(target.address)
        } catch (e: IllegalArgumentException) {
            throw ConnectException("Invalid address ${target.address}", e)
        }
        val connection = GattConnection(context, device, events)
        live.set(connection)
        try {
            connection.open()
            return connection
        } catch (e: Throwable) {
            connection.close()
            throw if (e is GattException) ConnectException(e.message ?: "GATT error", e) else e
        }
    }
}

internal class GattException(message: String) : Exception(message)

/**
 * One GATT client connection to a terminal.
 *
 * Android allows one outstanding GATT operation per connection and reports
 * each result on a callback, so every operation goes through [op]: a mutex
 * serializes them and a [CompletableDeferred] carries the callback's result
 * back to the suspended caller. An operation that never completes is treated
 * as a dead link: the connection is closed and reported as dropped.
 *
 * Both callback APIs are handled: Android 13+ passes values to
 * `onCharacteristicChanged/Read(..., value)` and takes values in
 * `writeCharacteristic/writeDescriptor(..., value)`; Android 12 uses the
 * characteristic's mutable `value` field.
 */
@SuppressLint("MissingPermission") // GattConnector checks BLUETOOTH_CONNECT first
internal class GattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val events: ConnectionEvents,
) : Connection {
    private enum class Kind { MTU, DISCOVER, DESCRIPTOR_WRITE, WRITE, READ }

    private class Result(val status: Int, val value: ByteArray? = null, val mtu: Int = 0)

    private class Pending(val kind: Kind, val result: CompletableDeferred<Result> = CompletableDeferred())

    private val opLock = Mutex()
    private val pending = AtomicReference<Pending?>(null)
    private val connected = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    @Volatile
    private var gatt: BluetoothGatt? = null
    private lateinit var up: BluetoothGattCharacteristic
    private lateinit var status: BluetoothGattCharacteristic
    private lateinit var command: BluetoothGattCharacteristic

    @Volatile
    override var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            Log.d(TAG, "connection state $newState status $statusCode")
            when {
                newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS ->
                    connected.complete(Unit)
                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    dropped("disconnected (${gattStatusName(statusCode)})")
                statusCode != BluetoothGatt.GATT_SUCCESS ->
                    dropped("connection error (${gattStatusName(statusCode)})")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) =
            complete(Kind.MTU, Result(statusCode, mtu = mtu))

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) =
            complete(Kind.DISCOVER, Result(statusCode))

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) =
            complete(Kind.DESCRIPTOR_WRITE, Result(statusCode))

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) =
            complete(Kind.WRITE, Result(statusCode))

        // Android 13+
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            statusCode: Int,
        ) = complete(Kind.READ, Result(statusCode, value.copyOf()))

        // Android 12
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) =
            complete(Kind.READ, Result(statusCode, c.value?.copyOf()))

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            notified(c.uuid, value)

        // Android 12: the value lives in the shared characteristic object; copy it at once.
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { notified(c.uuid, it) }
        }
    }

    /** Connects, requests a larger MTU, discovers services and enables DOWN, STATUS and EVENT notifications. */
    suspend fun open() {
        // Deprecated in API 37 in favour of connectGatt(BluetoothGattConnectionSettings, ...),
        // which the Fold 7 (API 36) doesn't have.
        @Suppress("DEPRECATION")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connectGatt failed")
        gatt = g
        // Dropped (Bluetooth turning off) before [gatt] was set: dropped() had no client to
        // close, and close() won't run twice, so close it here or it keeps the terminal's
        // single connection.
        if (closed.get()) {
            g.close()
            throw GattException("link dropped during setup")
        }
        withTimeoutOrNull(CONNECT_TIMEOUT) { connected.await() }
            ?: throw GattException("connect timed out")

        // Lets ACTIVATE (up to 121 bytes) go in one write; with the default MTU of 23
        // Android falls back to a long (prepared) write, which the terminal also takes.
        // Optional: if it can't start or never answers, carry on with 23.
        val m = try {
            op(Kind.MTU, MTU_TIMEOUT, timeoutDropsLink = false) { it.requestMtu(REQUESTED_MTU) }
        } catch (e: GattException) {
            if (closed.get()) throw e
            Log.w(TAG, "MTU request: ${e.message}")
            null
        }
        if (m?.status == BluetoothGatt.GATT_SUCCESS) mtu = m.mtu

        val d = op(Kind.DISCOVER, DISCOVERY_TIMEOUT) { it.discoverServices() }
        if (d.status != BluetoothGatt.GATT_SUCCESS) throw GattException("service discovery failed (${gattStatusName(d.status)})")
        val service = gatt?.getService(GattContract.SERVICE) ?: throw GattException("not an OpenCell terminal (service missing)")
        up = service.getCharacteristic(GattContract.UP) ?: throw GattException("UP characteristic missing")
        status = service.getCharacteristic(GattContract.STATUS) ?: throw GattException("STATUS characteristic missing")
        val down = service.getCharacteristic(GattContract.DOWN) ?: throw GattException("DOWN characteristic missing")
        command = service.getCharacteristic(GattContract.COMMAND)
            ?: throw GattException("COMMAND characteristic missing (terminal firmware older than contract v2)")
        val event = service.getCharacteristic(GattContract.EVENT) ?: throw GattException("EVENT characteristic missing")
        enableNotifications(down)
        enableNotifications(status)
        enableNotifications(event)

        ready.set(true)
        if (closed.get()) throw GattException("link dropped during setup")
        Log.i(TAG, "ready: ${device.address} mtu $mtu")
    }

    /**
     * Writes with response: only a write request gets the terminal's ATT
     * error back (0x80 not now, 0x0D too long). A write command
     * (without response) would be dropped silently on those errors.
     */
    override suspend fun write(payload: ByteArray): WriteResult = writeWithResponse(up, payload)

    /** COMMAND is write-with-response only; ATT 0x80, 0x0D and 0x81 come back as [WriteResult]s. */
    override suspend fun writeCommand(payload: ByteArray): WriteResult = writeWithResponse(command, payload)

    /**
     * A write on a connection that has dropped (or drops during it) is
     * [WriteResult.NotConnected], like one with no connection at all. So is
     * a write that can't start because Bluetooth is off or its service
     * unbound, and that also drops the link: the client is dead.
     */
    private suspend fun writeWithResponse(c: BluetoothGattCharacteristic, payload: ByteArray): WriteResult {
        var startCode = 0
        val r = try {
            op(Kind.WRITE) { g ->
                startCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    c.value = payload
                    @Suppress("DEPRECATION")
                    if (g.writeCharacteristic(c)) BluetoothStatusCodes.SUCCESS else LEGACY_START_FAILED
                }
                startCode == BluetoothStatusCodes.SUCCESS
            }
        } catch (e: GattException) {
            if (closed.get()) return WriteResult.NotConnected
            throw e
        }
        when (startCode) {
            BluetoothStatusCodes.SUCCESS -> Unit
            in STACK_GONE -> {
                dropped("Bluetooth is off (${startError(startCode)})")
                return WriteResult.NotConnected
            }
            else -> return WriteResult.Failed(startCode, "write not started (${startError(startCode)})")
        }
        return when (val result = WriteResult.fromGattStatus(r.status)) {
            is WriteResult.Failed -> WriteResult.Failed(r.status, gattStatusName(r.status))
            else -> result
        }
    }

    override suspend fun readStatus(): ByteArray? {
        val r = op(Kind.READ) { it.readCharacteristic(status) }
        return if (r.status == BluetoothGatt.GATT_SUCCESS) r.value else null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            failPending("closed")
            gatt?.let {
                it.disconnect()
                it.close()
            }
        }
    }

    private suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        val g = gatt ?: throw GattException("closed")
        if (!g.setCharacteristicNotification(c, true)) throw GattException("can't enable notifications")
        val cccd = c.getDescriptor(GattContract.CCCD) ?: throw GattException("CCCD missing on ${c.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val r = op(Kind.DESCRIPTOR_WRITE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                it.writeDescriptor(cccd)
            }
        }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw GattException("enabling notifications failed (${gattStatusName(r.status)})")
    }

    /**
     * Runs one GATT operation: [start] kicks it off (false = couldn't start),
     * the matching callback completes it. Serialized by [opLock]. By default
     * a timeout means the link is dead and drops it.
     */
    private suspend fun op(
        kind: Kind,
        timeout: Duration = OP_TIMEOUT,
        timeoutDropsLink: Boolean = true,
        start: (BluetoothGatt) -> Boolean,
    ): Result =
        opLock.withLock {
            val g = gatt?.takeUnless { closed.get() } ?: throw GattException("not connected")
            val p = Pending(kind)
            pending.set(p)
            try {
                if (!start(g)) {
                    if (kind == Kind.WRITE) return@withLock Result(-1) // caller inspects the start code
                    throw GattException("$kind could not start")
                }
                withTimeoutOrNull(timeout) { p.result.await() } ?: run {
                    if (timeoutDropsLink) dropped("$kind timed out")
                    throw GattException("$kind timed out")
                }
            } finally {
                pending.compareAndSet(p, null)
            }
        }

    private fun complete(kind: Kind, r: Result) {
        val p = pending.get()
        if (p != null && p.kind == kind) p.result.complete(r) else Log.w(TAG, "unexpected $kind callback")
    }

    private fun failPending(reason: String) {
        pending.getAndSet(null)?.result?.completeExceptionally(GattException(reason))
    }

    private fun notified(uuid: UUID, value: ByteArray) {
        if (closed.get()) return
        when (uuid) {
            GattContract.DOWN -> events.onDownlink(value.copyOf())
            GattContract.STATUS -> events.onStatus(value.copyOf())
            GattContract.EVENT -> events.onEvent(value.copyOf())
        }
    }

    /**
     * The link went away (or an op hung, or Bluetooth turned off: [GattConnector]
     * calls this then): release the GATT client and report it once.
     */
    fun dropped(reason: String) {
        Log.i(TAG, "dropped: $reason")
        connected.completeExceptionally(GattException(reason))
        if (closed.compareAndSet(false, true)) {
            failPending(reason)
            gatt?.close()
            if (ready.get()) events.onClosed(reason)
        }
    }

    companion object {
        private const val REQUESTED_MTU = 247
        private const val LEGACY_START_FAILED = -2
        private val CONNECT_TIMEOUT = 15.seconds
        private val DISCOVERY_TIMEOUT = 10.seconds
        private val OP_TIMEOUT = 5.seconds
        private val MTU_TIMEOUT = 3.seconds

        /** Write start codes meaning the Bluetooth stack no longer serves this client. */
        private val STACK_GONE = setOf(
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND,
        )

        private fun startError(code: Int) = when (code) {
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "busy"
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> "no permission"
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "Bluetooth service not bound"
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> "Bluetooth not enabled"
            LEGACY_START_FAILED -> "refused"
            else -> "code $code"
        }

        fun gattStatusName(status: Int) = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "success"
            0x08 -> "0x08 supervision timeout"
            0x13 -> "0x13 remote closed"
            0x16 -> "0x16 local host closed"
            0x3E -> "0x3E failed to establish"
            GattContract.ATT_ERR_INVALID_LENGTH -> "0x0D invalid length"
            GattContract.ATT_ERR_NOT_NOW -> "0x80 not now"
            GattContract.ATT_ERR_BAD_ARG -> "0x81 bad argument"
            133 -> "133 GATT_ERROR"
            else -> "0x%02X".format(status)
        }
    }
}
