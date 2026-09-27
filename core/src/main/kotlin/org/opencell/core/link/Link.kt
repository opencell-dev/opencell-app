package org.opencell.core.link

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.TerminalEvent
import org.opencell.core.protocol.TerminalStatus
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

/**
 * The seam between the transport (BLE GATT today) and everything above it:
 * the console, the loopback test, the phone (activation, registration, calls)
 * and, later, the voice codec.
 *
 * It carries app data frames of at most [org.opencell.core.protocol.GattContract.MAX_PAYLOAD]
 * bytes (one per radio frame each way), COMMAND writes and EVENT notifications.
 * Upper layers must not know about GATT.
 */
interface TerminalLink {
    val state: StateFlow<LinkState>

    /** Latest STATUS from the terminal (notification or read), or null before the first one. */
    val status: StateFlow<TerminalStatus?>

    /** Every DOWN payload, timestamped on arrival. Hot; late subscribers miss earlier payloads. */
    val downlink: SharedFlow<Downlink>

    /** Every EVENT notification, decoded. Hot; the terminal doesn't queue events while no phone is connected. */
    val events: SharedFlow<TerminalEvent>

    /**
     * Every EVENT and STATUS notification, in the order they arrived, for a consumer whose
     * state depends on both (the phone). STATUS reads ([refreshStatus]) aren't in it. Hot.
     */
    val inputs: SharedFlow<LinkInput>

    /** One write attempt to UP. No retries here: see [UplinkSender]. */
    suspend fun writeUp(payload: ByteArray): WriteResult

    /** One COMMAND write (with response). Never retried: see [org.opencell.core.protocol.Command]. */
    suspend fun writeCommand(payload: ByteArray): WriteResult

    /** Reads STATUS now. Returns null when not connected or the read failed. */
    suspend fun refreshStatus(): TerminalStatus?
}

/** One notification from the terminal, as it arrived: see [TerminalLink.inputs]. */
sealed interface LinkInput {
    data class Event(val event: TerminalEvent) : LinkInput

    data class Status(val status: TerminalStatus) : LinkInput
}

/** Which terminal to talk to. [address] is the BLE MAC (or a simulator id). */
data class LinkTarget(val address: String, val name: String?)

sealed interface LinkState {
    val target: LinkTarget?

    data object Disconnected : LinkState {
        override val target: LinkTarget? get() = null
    }

    /** Connecting (including service discovery and enabling notifications). [attempt] counts from 1. */
    data class Connecting(override val target: LinkTarget, val attempt: Int) : LinkState

    /** Connected and pairing: the system dialog asks for the code on the terminal's screen. */
    data class Pairing(override val target: LinkTarget) : LinkState

    /**
     * Pairing failed or the phone's bond is stale. No automatic retry (each
     * attempt costs one of the terminal's 3 tries a minute): connect again to retry.
     */
    data class PairingFailed(
        override val target: LinkTarget,
        val problem: PairingProblem,
        val reason: String,
    ) : LinkState

    data class Connected(override val target: LinkTarget, val mtu: Int) : LinkState

    /**
     * The link dropped or a connect failed; the next attempt starts after [delay].
     * [failures] counts consecutive failures since the last good connection.
     */
    data class WaitingToReconnect(
        override val target: LinkTarget,
        val failures: Int,
        val delay: Duration,
        val reason: String,
    ) : LinkState

    val isConnected: Boolean get() = this is Connected
}

/** One DOWN payload. [at] is a monotonic mark for latency; [wallMillis] is for display. */
class Downlink(val payload: ByteArray, val at: ComparableTimeMark, val wallMillis: Long)

/** Outcome of one write attempt to UP. */
sealed interface WriteResult {
    /** UP: the terminal queued the frame for its next UL slot. COMMAND: the terminal took the command. */
    data object Accepted : WriteResult

    /** ATT 0x80. UP: no grant, or the UL queue is full; retry later. COMMAND: not in the right state. */
    data object NotNow : WriteResult

    /** ATT 0x0D: UP longer than the terminal's limit, or a COMMAND of the wrong length. Permanent. */
    data object TooLong : WriteResult

    /** ATT 0x81: a malformed COMMAND argument (QR text, number, confirmation byte). Permanent. */
    data object BadArgument : WriteResult

    data object NotConnected : WriteResult

    /** Any other GATT failure: [code] is the GATT status or a local error code. */
    data class Failed(val code: Int, val message: String) : WriteResult

    companion object {
        /** Maps the GATT status of a write-with-response to a [WriteResult] per the terminal's contract. */
        fun fromGattStatus(status: Int): WriteResult = when (status) {
            0 -> Accepted
            GattContract.ATT_ERR_NOT_NOW -> NotNow
            GattContract.ATT_ERR_INVALID_LENGTH -> TooLong
            GattContract.ATT_ERR_BAD_ARG -> BadArgument
            else -> Failed(status, "GATT status 0x%02X".format(status))
        }
    }

    val label: String
        get() = when (this) {
            Accepted -> "accepted"
            NotNow -> "not now (0x80)"
            TooLong -> "too long (0x0D)"
            BadArgument -> "bad argument (0x81)"
            NotConnected -> "not connected"
            is Failed -> "failed: $message"
        }
}

/**
 * Receives what a [Connection] produces. Called from transport threads
 * (Binder threads on Android), so implementations must be thread-safe.
 */
interface ConnectionEvents {
    fun onDownlink(payload: ByteArray)
    fun onStatus(raw: ByteArray)
    fun onEvent(raw: ByteArray)

    /** The transport started pairing; the user is being asked for the terminal's code. */
    fun onPairing() {}

    /** The link dropped. Called at most once, and never after [Connection.close]. */
    fun onClosed(reason: String)
}

/** One live connection to a terminal, fully set up (services found, notifications on). */
interface Connection {
    val mtu: Int
    suspend fun write(payload: ByteArray): WriteResult
    suspend fun writeCommand(payload: ByteArray): WriteResult
    suspend fun readStatus(): ByteArray?

    /** Tears the connection down. Idempotent; no [ConnectionEvents.onClosed] follows. */
    fun close()
}

/**
 * Opens [Connection]s. Implemented by the Android GATT transport and by the simulator.
 * Pairing/bonding happens inside the GATT implementation of [connect]; it reports
 * [ConnectionEvents.onPairing] and throws [PairingException] when the user must act.
 */
fun interface Connector {
    /**
     * Connects and returns once the link is ready for writes.
     * Throws on failure (including timeouts); the caller decides whether to retry,
     * except after a [PairingException], which [LinkManager] never retries by itself.
     */
    suspend fun connect(target: LinkTarget, events: ConnectionEvents): Connection
}

class ConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)
