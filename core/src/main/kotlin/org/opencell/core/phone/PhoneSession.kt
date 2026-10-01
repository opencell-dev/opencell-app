package org.opencell.core.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencell.core.link.LinkInput
import org.opencell.core.link.LinkState
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.UplinkSender
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.DialCheck
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
 * link comes up (for a new terminal address, from a blank [PhoneState]: this
 * one [TerminalSession][org.opencell.core.session.TerminalSession] can serve
 * any scanned device in turn, and nothing here may carry over between them),
 * because the terminal drops EVENTs while no phone is connected.
 *
 * Commands are never retried: ATT 0x80 means the terminal is in another state
 * than the app thought. It re-reads STATUS and applies it as an ordinary
 * [PhoneInput.Status] (not a full [PhoneInput.Resync]: that would erase an
 * INCOMING call's id/caller and interrupt an in-progress activation, which a
 * mere command refusal hasn't earned); if that read fails, nothing changes.
 * A command identical to one already in flight is ignored rather than queued.
 *
 * EVENTs and STATUS notifications are applied from one ordered stream
 * ([TerminalLink.inputs]), in the order they arrived.
 *
 * The firmware notifies STATUS only with an EVENT or a radio state change (not
 * on DIAL, ANSWER, HANGUP or REJECT) and can drop EVENTs, so [PhoneReducer]
 * holds a disagreeing STATUS back for [PhoneReducer.RECONCILE_GRACE_MS] rather
 * than act on it immediately. After every reduce this schedules a single
 * pending check at [PhoneReducer.reconcileDueAt] (cancelling and replacing any
 * earlier one). When it's due it reads STATUS afresh and applies that — the
 * stored one may be a stale notification sent just before an accepted command
 * — and only if the read fails reconciles against the stored one ([PhoneInput.Tick]).
 *
 * [monotonic] times the grace; [clock] (wall time) only what's displayed.
 */
class PhoneSession(
    private val link: TerminalLink,
    private val sender: UplinkSender,
    private val scope: CoroutineScope,
    private val memory: PhoneMemory = PhoneMemory.inMemory(),
    private val log: (ConsoleKind, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val commandLock = Mutex()

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val _callData = MutableStateFlow(CallData())
    val callData: StateFlow<CallData> = _callData.asStateFlow()

    private val tracker = CallTracker()
    private val _finishedCalls = Channel<FinishedCall>(Channel.UNLIMITED)

    /**
     * Each call once, when it stops being active ([CallTracker]): the call log's
     * source. Buffered without limit until collected; meant for one collector
     * ([org.opencell.core.session.TerminalSession]).
     */
    val finishedCalls: Flow<FinishedCall> = _finishedCalls.receiveAsFlow()

    /** The single scheduled [PhoneInput.Tick] for a STATUS held back by the grace, if any. */
    private var pendingTick: Job? = null

    /** The running [sendTestFrames] job, if any: at most one at a time (see [sendTestFrames]). */
    private var dataJob: Job? = null

    /** Commands currently being written, so a duplicate is ignored rather than queued behind it. */
    private val inFlight = mutableSetOf<Command>()

    /** The BLE address this session last resynced against, so a different one triggers a reset. */
    private var lastAddress: String? = null

    init {
        scope.launch {
            // One collector for both, so an EVENT and the STATUS around it are applied in arrival order.
            link.inputs.collect { input ->
                when (input) {
                    is LinkInput.Event -> {
                        log(ConsoleKind.INFO, "EVENT ${input.event.label}")
                        apply(PhoneInput.Event(input.event))
                    }
                    is LinkInput.Status -> input.status.sig?.let { apply(PhoneInput.Status(it)) }
                }
            }
        }
        scope.launch {
            link.state.collect { s ->
                when {
                    s is LinkState.Connected -> onConnected(s.target.address)
                    // Closed for good (Disconnect): no reconnect will update the call, so end it.
                    s is LinkState.Disconnected -> if (_state.value.linkUp || _state.value.activeCall != null) apply(PhoneInput.LinkClosed)
                    _state.value.linkUp -> apply(PhoneInput.LinkDown)
                }
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

    /**
     * Sends DIAL: the full form of [input], completed from this terminal's own
     * number (numbering v2). Returns why [input] isn't a number, or null once
     * the command is on its way.
     */
    fun dial(input: String): String? {
        val sent = when (val c = PhoneNumber.check(input, _state.value.number)) {
            is DialCheck.Number -> c.full
            is DialCheck.National -> c.digits // own number not known yet: the terminal completes it
            DialCheck.Emergency -> return EMERGENCY
            DialCheck.Empty, DialCheck.NotANumber -> return BAD_NUMBER
        }
        command(Command.Dial(sent)) { apply(PhoneInput.Dialled(sent)) }
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
     *
     * Each frame is written once directly (no [UplinkSender] retries: a late
     * test frame is as worthless as a late voice frame would be) and only
     * after checking the call is still connected; a 0x80 just drops that one
     * frame. At most one send runs at a time: calling this again while one is
     * already running is ignored (a notice is shown) rather than starting a
     * second, overlapping one. The running job is also cancelled the moment
     * the call leaves CONNECTED, on [PhoneInput.LinkDown], or on [close].
     */
    fun sendTestFrames(count: Int = 5, interval: Duration = 200.milliseconds): Job? {
        if (_state.value.call?.phase != CallPhase.CONNECTED) {
            apply(PhoneInput.Notice("Test frames only go out during a connected call"))
            return null
        }
        synchronized(lock) {
            if (dataJob?.isActive == true) {
                apply(PhoneInput.Notice("Test frames are already going out"))
                return null
            }
            val job = scope.launch {
                for (i in 0 until count) {
                    if (_state.value.call?.phase != CallPhase.CONNECTED) break
                    val frame = testFrame(i)
                    val out = link.writeUp(frame)
                    val sent = out == WriteResult.Accepted
                    _callData.update { if (sent) it.copy(sent = it.sent + 1) else it.copy(failed = it.failed + 1) }
                    log(if (sent) ConsoleKind.UP else ConsoleKind.ERROR, "UP test frame $i: ${if (sent) "sent" else out.label}")
                    if (i < count - 1) delay(interval)
                }
            }
            dataJob = job
            job.invokeOnCompletion { synchronized(lock) { if (dataJob === job) dataJob = null } }
            return job
        }
    }

    /** Cancels an outstanding [sendTestFrames] run, if any. Call when tearing this session down. */
    fun close() {
        synchronized(lock) {
            dataJob?.cancel()
            dataJob = null
            pendingTick?.cancel()
            pendingTick = null
        }
    }

    private fun command(cmd: Command, onAccepted: () -> Unit): Job {
        val started = synchronized(lock) { inFlight.add(cmd) }
        if (!started) return Job().apply { complete() }
        return scope.launch {
            try {
                commandLock.withLock {
                    val r = link.writeCommand(cmd.encode())
                    log(if (r == WriteResult.Accepted) ConsoleKind.INFO else ConsoleKind.ERROR, "COMMAND ${cmd.label}: ${r.label}")
                    if (r == WriteResult.Accepted) {
                        onAccepted()
                    } else {
                        apply(PhoneInput.Notice(refusal(cmd, r)))
                        if (r == WriteResult.NotNow) rereadStatus()
                    }
                }
            } finally {
                synchronized(lock) { inFlight.remove(cmd) }
            }
        }
    }

    /**
     * After a 0x80 refusal, re-reads STATUS and applies it as an ordinary
     * [PhoneInput.Status]: the command's target may just be a step behind, not
     * lost entirely, so this must not erase an INCOMING call's id/caller or
     * interrupt an in-progress activation the way [resync] (a full [PhoneInput.Resync])
     * would. If the read fails, nothing changes.
     */
    private suspend fun rereadStatus() {
        val sig = link.refreshStatus()?.sig ?: return
        apply(PhoneInput.Status(sig))
    }

    /**
     * The link came up for [address]. If it's a different terminal than the
     * one this session last talked to, everything here (state and in-call
     * data) is reset first: nothing about terminal A may carry over to B. A
     * call still active with A is closed first, like [PhoneInput.LinkClosed].
     * Either way, [resync] then reads STATUS fresh for whichever terminal it is.
     */
    private suspend fun onConnected(address: String) {
        if (lastAddress != null && lastAddress != address) {
            // A call with the old terminal is over as far as this phone can tell (nothing about it
            // will arrive again): end it through the reducer, so the call log gets it once, with
            // its own voice counters, before everything is forgotten.
            if (_state.value.activeCall != null) apply(PhoneInput.LinkClosed)
            synchronized(lock) {
                dataJob?.cancel()
                dataJob = null
                pendingTick?.cancel()
                pendingTick = null
                _state.value = PhoneState()
                _callData.value = CallData()
            }
        }
        lastAddress = address
        resync()
    }

    /**
     * Reads STATUS and reconciles with it (after connecting). If the fresh
     * read fails, this does NOT fall back to the link's last (possibly stale,
     * pre-disconnect) STATUS: it only marks the link up ([PhoneInput.LinkUp],
     * through the reducer like everything else) and leaves the rest as it is,
     * trusting the next STATUS notification or the next connect's resync to catch up.
     */
    private suspend fun resync() {
        val sig = link.refreshStatus()?.sig
        if (sig == null) {
            if (link.state.value.isConnected) apply(PhoneInput.LinkUp)
            return
        }
        apply(PhoneInput.Resync(sig, address()?.let(memory::load)))
    }

    private fun address(): String? = link.state.value.target?.address

    private fun apply(input: PhoneInput) {
        synchronized(lock) {
            val before = _state.value
            val wall = clock()
            val after = PhoneReducer.reduce(before, input, monotonic(), wall)
            _state.value = after
            tracker.step(before, input, after, wall)?.let { _finishedCalls.trySend(it) }
            if (before.call?.phase != CallPhase.CONNECTED && after.call?.phase == CallPhase.CONNECTED) _callData.value = CallData()
            if (dataJob != null && (after.call?.phase != CallPhase.CONNECTED || !after.linkUp)) {
                dataJob?.cancel()
                dataJob = null
            }
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
     * Schedules (or cancels) the single pending reconcile check for [state],
     * per [PhoneReducer.reconcileDueAt]: replaces any previously pending one,
     * and runs immediately if the due time has already passed. The check reads
     * STATUS afresh and applies it as a [PhoneInput.Status] (the grace is over,
     * so the reducer reconciles against it); only if that read fails does it
     * fall back to a [PhoneInput.Tick] against the stored STATUS.
     */
    private fun scheduleReconcile(state: PhoneState) {
        val dueAt = PhoneReducer.reconcileDueAt(state)
        pendingTick?.cancel()
        pendingTick = dueAt?.let { at ->
            scope.launch {
                val wait = at - monotonic()
                if (wait > 0) delay(wait)
                val fresh = link.refreshStatus()?.sig
                apply(if (fresh != null) PhoneInput.Status(fresh) else PhoneInput.Tick(monotonic()))
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
            is Command.ScanSetUser, is Command.ScanSetFallback, Command.ScanForgetLearned ->
                "The terminal can't change its scan list now" // never sent by the phone session
        }
        // A v2-firmware terminal refuses v3 DIAL/ACTIVATE arguments with this same ATT error and emits
        // no event on connect, so the app can't otherwise tell an old terminal from a bad argument.
        WriteResult.BadArgument -> when (cmd) {
            is Command.Activate ->
                "The terminal refused this activation code (damaged, an invalid network key, " +
                    "or the terminal's firmware is older than numbering v2: update it)"
            is Command.Dial -> "The terminal refused this number (or the terminal's firmware is older than numbering v2: update it)"
            else -> "The terminal refused ${cmd.label} (malformed command)"
        }
        WriteResult.TooLong -> "The terminal refused ${cmd.label} (wrong length)"
        WriteResult.NotConnected -> "The terminal isn't connected"
        else -> "${cmd.label} failed: ${r.label}"
    }

    companion object {
        const val BAD_NUMBER = "Not an OpenCell number. Dial 606-555-01234, or +883-1-606-555-01234 from another country."
        const val EMERGENCY = "OpenCell cannot make emergency calls. Use a regular phone."

        /**
         * How a call's peer should read. An outgoing call dialled as a national form while this
         * terminal's own number wasn't known yet (see [dial]) stores raw digits as its peer; once
         * [home] is known (a later REGISTERED, or the call itself connecting), this completes them
         * into the international display, exactly as [dial] would have sent them at the time. A
         * peer that still doesn't complete against [home] (or is already a full number) is shown
         * as-is: this never invents a country code.
         */
        fun peerLabel(peer: String, home: String?): String =
            (PhoneNumber.check(peer, home) as? DialCheck.Number)?.full?.let(PhoneNumber::display) ?: PhoneNumber.display(peer)

        /** The line under the dial field as the user types: what DIAL will send, or why it isn't a number. */
        fun dialHint(input: String, home: String?): String = when (val c = PhoneNumber.check(input, home)) {
            DialCheck.Empty -> ""
            is DialCheck.Number -> "Dials ${PhoneNumber.display(c.full)}"
            is DialCheck.National -> "Dials ${c.digits}: the terminal adds the country code"
            DialCheck.Emergency -> EMERGENCY
            DialCheck.NotANumber -> BAD_NUMBER
        }

        /** `0xB0, seq, "oc-send"`: the frames `tools/ble/oc_ble.py send` sends and `recv` counts. */
        fun testFrame(seq: Int): ByteArray = byteArrayOf(0xB0.toByte(), seq.toByte()) + "oc-send".encodeToByteArray()

        fun isTestFrame(b: ByteArray): Boolean =
            b.size == 9 && b[0] == 0xB0.toByte() && b.copyOfRange(2, 9).decodeToString() == "oc-send"
    }
}
