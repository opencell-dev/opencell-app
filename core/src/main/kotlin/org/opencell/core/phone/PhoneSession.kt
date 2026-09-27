package org.opencell.core.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencell.core.link.LinkState
import org.opencell.core.link.SendOutcome
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.UplinkSender
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.SigState
import org.opencell.core.session.ConsoleKind
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Where the app keeps each terminal's number between runs, keyed by address. Display only: no secrets. */
interface PhoneMemory {
    fun load(address: String): Remembered?
    fun save(address: String, remembered: Remembered)
    fun clear(address: String)

    companion object {
        fun inMemory(): PhoneMemory = object : PhoneMemory {
            private val map = ConcurrentHashMap<String, Remembered>()
            override fun load(address: String) = map[address]
            override fun save(address: String, remembered: Remembered) {
                map[address] = remembered
            }

            override fun clear(address: String) {
                map.remove(address)
            }
        }
    }
}

/** App data counters of the current call (voice is not in this step: the in-call screen sends test frames). */
data class CallData(
    val sent: Int = 0,
    val failed: Int = 0,
    val received: Int = 0,
    /** Received frames that are test frames ([PhoneSession.testFrame]), from the echoing peer or another terminal. */
    val testFramesReceived: Int = 0,
    val lastReceivedHex: String? = null,
)

/**
 * The phone side of the terminal: activation, registration and calls.
 * The terminal runs all signalling; this sends COMMANDs, follows EVENTs and
 * STATUS byte 3 through [PhoneReducer], and resyncs from STATUS every time the
 * link comes up, because the terminal drops EVENTs while no phone is connected.
 *
 * Commands are never retried: ATT 0x80 means the terminal is in another state
 * than the app thought, so the app shows why and reads STATUS again.
 *
 * The firmware only notifies STATUS on change and can drop EVENTs, so
 * [PhoneReducer] can hold a disagreeing STATUS back for
 * [PhoneReducer.RECONCILE_GRACE_MS] rather than act on it immediately. After
 * every reduce this schedules a single pending [PhoneInput.Tick] at
 * [PhoneReducer.reconcileDueAt] (cancelling and replacing any earlier one) so
 * that a held-back correction is still carried through even if nothing else
 * happens.
 */
class PhoneSession(
    private val link: TerminalLink,
    private val sender: UplinkSender,
    private val scope: CoroutineScope,
    private val memory: PhoneMemory = PhoneMemory.inMemory(),
    private val log: (ConsoleKind, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val commandLock = Mutex()

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val _callData = MutableStateFlow(CallData())
    val callData: StateFlow<CallData> = _callData.asStateFlow()

    /** The single scheduled [PhoneInput.Tick] for a STATUS held back by the grace, if any. */
    private var pendingTick: Job? = null

    init {
        scope.launch {
            link.events.collect { e ->
                log(ConsoleKind.INFO, "EVENT ${e.label}")
                apply(PhoneInput.Event(e))
            }
        }
        scope.launch {
            link.status.collect { st -> st?.sig?.let { apply(PhoneInput.Status(it)) } }
        }
        scope.launch {
            link.state.collect { s ->
                if (s is LinkState.Connected) resync() else if (_state.value.linkUp) apply(PhoneInput.LinkDown)
            }
        }
        scope.launch {
            link.downlink.collect { d ->
                if (_state.value.call?.phase == CallPhase.CONNECTED) {
                    _callData.update {
                        it.copy(
                            received = it.received + 1,
                            testFramesReceived = it.testFramesReceived + if (isTestFrame(d.payload)) 1 else 0,
                            lastReceivedHex = Hex.format(d.payload),
                        )
                    }
                }
            }
        }
    }

    /** Sends ACTIVATE with a code the app has already checked. */
    fun activate(qr: ActivationQr): Job = command(Command.Activate(qr.text)) { apply(PhoneInput.Activating(qr.number)) }

    /** Sends DIAL. Returns why [input] isn't a number the terminal takes, or null once the command is on its way. */
    fun dial(input: String): String? {
        val number = PhoneNumber.parse(input) ?: return BAD_NUMBER
        command(Command.Dial(number)) { apply(PhoneInput.Dialled(number)) }
        return null
    }

    fun answer(): Job = command(Command.Answer) { apply(PhoneInput.Answering) }
    fun reject(): Job = command(Command.Reject) { apply(PhoneInput.Releasing) }
    fun hangup(): Job = command(Command.Hangup) { apply(PhoneInput.Releasing) }

    /** DEACTIVATE (with its 0xA5 confirmation): the terminal wipes its keys and sends DEACTIVATED. */
    fun deactivate(): Job = command(Command.Deactivate) {}

    fun dismissCall() = apply(PhoneInput.DismissCall)
    fun clearActivation() = apply(PhoneInput.ClearActivation)
    fun clearNotice() = apply(PhoneInput.Notice(null))

    /**
     * Sends [count] test frames ([testFrame]) [interval] apart, like the laptop
     * client's `send` step. App data goes out only in a connected call: outside
     * one it would be refused (no grant) and would travel unencrypted.
     */
    fun sendTestFrames(count: Int = 5, interval: Duration = 200.milliseconds): Job? {
        if (_state.value.call?.phase != CallPhase.CONNECTED) {
            apply(PhoneInput.Notice("Test frames only go out during a connected call"))
            return null
        }
        return scope.launch {
            for (i in 0 until count) {
                if (_state.value.call?.phase != CallPhase.CONNECTED) break
                val frame = testFrame(i)
                val out = sender.send(frame)
                _callData.update { if (out is SendOutcome.Sent) it.copy(sent = it.sent + 1) else it.copy(failed = it.failed + 1) }
                log(if (out is SendOutcome.Sent) ConsoleKind.UP else ConsoleKind.ERROR, "UP test frame $i: ${out.label}")
                if (i < count - 1) delay(interval)
            }
        }
    }

    private fun command(cmd: Command, onAccepted: () -> Unit): Job = scope.launch {
        commandLock.withLock {
            val r = link.writeCommand(cmd.encode())
            log(if (r == WriteResult.Accepted) ConsoleKind.INFO else ConsoleKind.ERROR, "COMMAND ${cmd.label}: ${r.label}")
            if (r == WriteResult.Accepted) {
                onAccepted()
            } else {
                apply(PhoneInput.Notice(refusal(cmd, r)))
                if (r == WriteResult.NotNow) resync()
            }
        }
    }

    /**
     * Reads STATUS and reconciles with it (after connecting, and after a 0x80
     * refusal). If the fresh read fails, this does NOT fall back to the link's
     * last (possibly stale, pre-disconnect) STATUS: it only updates [PhoneState.linkUp]
     * and leaves the rest as it is, trusting the next STATUS notification or
     * the next connect's resync to catch up.
     */
    private suspend fun resync() {
        val sig = link.refreshStatus()?.sig
        if (sig == null) {
            synchronized(lock) { _state.update { it.copy(linkUp = link.state.value.isConnected) } }
            return
        }
        apply(PhoneInput.Resync(sig, address()?.let(memory::load)))
    }

    private fun address(): String? = link.state.value.target?.address

    private fun apply(input: PhoneInput) {
        synchronized(lock) {
            val before = _state.value
            val after = PhoneReducer.reduce(before, input, clock())
            _state.value = after
            if (before.call?.phase != CallPhase.CONNECTED && after.call?.phase == CallPhase.CONNECTED) _callData.value = CallData()
            scheduleReconcile(after)
            val address = address() ?: return
            if (after.sig == SigState.NOT_ACTIVATED) {
                if (before.sig != SigState.NOT_ACTIVATED) memory.clear(address)
            } else if (after.number != null && (after.number != before.number || after.mode != before.mode)) {
                memory.save(address, Remembered(after.number, after.mode))
            }
        }
    }

    /**
     * Schedules (or cancels) the single pending [PhoneInput.Tick] for [state],
     * per [PhoneReducer.reconcileDueAt]: replaces any previously pending one,
     * and fires immediately if the due time has already passed.
     */
    private fun scheduleReconcile(state: PhoneState) {
        val dueAt = PhoneReducer.reconcileDueAt(state)
        pendingTick?.cancel()
        pendingTick = dueAt?.let { at ->
            scope.launch {
                val wait = at - clock()
                if (wait > 0) delay(wait)
                apply(PhoneInput.Tick(clock()))
            }
        }
    }

    private fun refusal(cmd: Command, r: WriteResult): String = when (r) {
        WriteResult.NotNow -> when (cmd) {
            is Command.Activate -> "The terminal can't activate now (it is activating or in a call)"
            is Command.Dial -> "The terminal can't place a call now (it must be registered and not in a call)"
            Command.Answer -> "There is no incoming call to answer"
            Command.Reject -> "There is no incoming call to reject"
            Command.Hangup -> "There is no call to hang up"
            Command.Deactivate -> "The terminal can't deactivate during a call"
        }
        WriteResult.BadArgument -> when (cmd) {
            is Command.Activate -> "The terminal refused this activation code (damaged, or an invalid network key)"
            is Command.Dial -> "The terminal refused this number"
            else -> "The terminal refused ${cmd.label} (malformed command)"
        }
        WriteResult.TooLong -> "The terminal refused ${cmd.label} (wrong length)"
        WriteResult.NotConnected -> "The terminal isn't connected"
        else -> "${cmd.label} failed: ${r.label}"
    }

    companion object {
        const val BAD_NUMBER = "OpenCell numbers are +883 and 10 digits, like +883 606 555 1234"

        /** `0xB0, seq, "oc-send"`: the frames `tools/ble/oc_ble.py send` sends and `recv` counts. */
        fun testFrame(seq: Int): ByteArray = byteArrayOf(0xB0.toByte(), seq.toByte()) + "oc-send".encodeToByteArray()

        fun isTestFrame(b: ByteArray): Boolean =
            b.size == 9 && b[0] == 0xB0.toByte() && b.copyOfRange(2, 9).decodeToString() == "oc-send"
    }
}
