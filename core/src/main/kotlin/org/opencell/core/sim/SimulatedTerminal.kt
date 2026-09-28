package org.opencell.core.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.Connector
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingException
import org.opencell.core.link.PairingProblem
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.ActFailReason
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.ChannelGrid
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.GattContract
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.RegFailReason
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.ScanEntry
import org.opencell.core.protocol.ScanList
import org.opencell.core.protocol.ScanSource
import org.opencell.core.protocol.ScanTail
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalEvent
import org.opencell.core.protocol.TerminalState
import org.opencell.core.protocol.TerminalStatus
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long the simulated terminal and network take for each step. */
data class SimTiming(
    val activation: Duration = 1500.milliseconds,
    val registration: Duration = 800.milliseconds,
    /** DIAL to RINGING (CALL_SETUP, CALL_PROC, ALERTING). */
    val setup: Duration = 400.milliseconds,
    /** DIAL to CALL_SETUP going out on the air (the next UL frame); a HANGUP before this just drops it. Less than [setup]. */
    val setupSent: Duration = 120.milliseconds,
    /** The simulated peer answers after ringing this long, like `ocbench net`'s peer (3 s). */
    val peerAnswers: Duration = 3.seconds,
    /** ANSWER to CONNECTED. */
    val answer: Duration = 300.milliseconds,
    /** HANGUP or REJECT to ENDED. */
    val release: Duration = 300.milliseconds,
    val ringTimeout: Duration = 60.seconds,
    /** REG_FAILED to the next registration attempt (the firmware's first backoff). */
    val regRetry: Duration = 30.seconds,
)

/**
 * A software terminal plus network, for trying the app without hardware
 * ("Demo terminal") and for tests. As far as the phone can tell it behaves like
 * the firmware (contract v3, numbering v2) against `ocbench net`:
 * - the radio walks SEARCH -> SYNCED -> ATTACHING -> GRANTED over ~2 s of frames
 *   and then stays granted;
 * - UP takes app data frames into a 4-deep queue (0x80 when full or not granted,
 *   0x0D over 18 bytes), sends one per 120 ms frame, and the cell or the call's
 *   peer echoes each on DOWN after [echoDelay];
 * - COMMANDs are checked like `oc_sig_term_command` (0x0D length, 0x81 argument,
 *   0x80 state), and EVENTs and STATUS byte 3 follow like the firmware's;
 * - STATUS is notified only with an EVENT or a radio state change, like the
 *   firmware (term_app.c): not on DIAL, ANSWER, HANGUP, REJECT, ACTIVATE or a
 *   call timer, and not periodically;
 * - activation accepts any valid code whose token wasn't used here and hasn't
 *   expired; outgoing calls ring and are answered after [SimTiming.peerAnswers];
 *   DIAL takes any dialled form and completes it from the terminal's own
 *   number (`oc_sig_number_normalize`); dialling your own number is busy,
 *   [UNREACHABLE] is unreachable;
 * - EVENTs are dropped while no phone is connected, like the firmware's;
 * - it keeps a scan list (contract v4): its cell's anchor ([ANCHOR_HZ]) becomes the last
 *   serving entry once attached, COMMAND SCAN edits the user entries and fallback like
 *   `oc_term_gatt_scan_command`, and STATUS carries the scan tail.
 *
 * Signalling state lives here, not in the connection, so a test can drop the
 * BLE link ([dropLink]) and reconnect to a terminal that carried on without it.
 */
class SimulatedTerminal(
    private val scope: CoroutineScope,
    val tmid: Long = 0x76AD0488L,
    private val echoDelay: Duration = 300.milliseconds,
    private val connectDelay: Duration = 400.milliseconds,
    private val bleDelay: Duration = 15.milliseconds,
    seed: Int = 1,
    /** Start activated with this number (registers once attached); null starts not activated. */
    activatedNumber: String? = null,
    private val timing: SimTiming = SimTiming(),
    private val mode: RegMode = RegMode.PART15,
    private val unixSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : Connector {
    val name: String get() = GattContract.NAME_PREFIX + "%08X".format(tmid)

    private val lock = Any()
    private val random = Random(seed)
    private var frames = 0
    private var radio = TerminalState.SEARCH
    private var rssi = -52
    private var current: Sim? = null

    private var activated = activatedNumber != null
    private var number: String? = activatedNumber
    private var sig = if (activatedNumber != null) SigState.REGISTERING else SigState.NOT_ACTIVATED
    private val usedTokens = mutableSetOf<String>()
    private var callId = 0L
    private var nextCallId = 1L
    private var answered = false
    private var pending: Job? = null

    /** An outgoing call's CALL_SETUP has gone out (so a HANGUP must wait for CALL_PROC's call id). */
    private var setupSent = false

    /** HANGUP came while CALLING before CALL_PROC: release once the call id arrives (the firmware's hangup_pending). */
    private var hangupPending = false

    // --- the scan list (oc_term_scan), all under the lock ---
    private val userChannels = mutableListOf<ScanEntry>()
    private var fallbackAfter = 2
    private var fallbackChunk = 13
    private var lastServing: Long? = null
    private val learned = mutableListOf<Long>()

    /** Makes the next activation fail with this reason (the network's ACT_NAK), then clears itself. */
    @Volatile
    var failNextActivation: ActFailReason? = null

    /** Makes the next registration fail with this reason (REG_FAILED), then clears itself; it retries after [SimTiming.regRetry]. */
    @Volatile
    var failNextRegistration: RegFailReason? = null

    /** Makes the next connect fail pairing with this problem (the phone's side of the BLE link), then clears itself. */
    @Volatile
    var failNextConnect: PairingProblem? = null

    /** False makes this behave like v3 firmware with no SCAN characteristic, for tests. */
    @Volatile
    var scanSupported: Boolean = true

    /** The signalling state (STATUS byte 3), for tests. */
    val sigState: SigState get() = synchronized(lock) { sig }

    override suspend fun connect(target: LinkTarget, events: ConnectionEvents): Connection {
        delay(connectDelay)
        failNextConnect?.let {
            failNextConnect = null
            throw PairingException(it, "pairing failed (simulated)")
        }
        val sim = Sim(events)
        synchronized(lock) { current = sim }
        sim.start()
        return sim
    }

    /** A fresh one-time code for the demo, like `ocbench mkqr --number`. Each call makes a new token. */
    fun demoQrText(number: String = DEMO_NUMBER, validFor: Duration = 24.hours): String = synchronized(lock) {
        ActivationQr.format(
            keyId = 1,
            networkKey = ByteArray(32) { random.nextInt().toByte() },
            tokenId = ByteArray(8) { random.nextInt().toByte() },
            tokenSecret = ByteArray(16) { random.nextInt().toByte() },
            number = number,
            expiryUnix = unixSeconds() + validFor.inWholeSeconds,
        )
    }

    /** Someone calls this terminal (the stand-in's `--call-in`). False unless it is registered and idle. */
    fun incomingCall(caller: String = PEER): Boolean = synchronized(lock) {
        if (sig != SigState.REGISTERED) return false
        callId = nextCallId++
        answered = false
        sig = SigState.RINGING_IN
        emit(TerminalEvent.Incoming(callId, caller))
        later(timing.ringTimeout) {
            // Like the firmware (oc_sig_term.c): an unanswered incoming call goes
            // RINGING_IN -> RELEASING (no EVENT, so no STATUS notification either) and
            // only ends once the release completes, same as a local HANGUP/REJECT.
            sig = SigState.RELEASING
            later(timing.release) { end(EndCause.NO_ANSWER) }
        }
        true
    }

    /** The far end hangs up (the stand-in's `--peer-hangup`). False if there is no call. */
    fun peerHangup(): Boolean = synchronized(lock) {
        if (!sig.hasCall) return false
        end(EndCause.NORMAL)
        true
    }

    /** The BLE link drops (supervision timeout); the terminal carries on without the phone. */
    fun dropLink() {
        val sim = synchronized(lock) { current.also { current = null } } ?: return
        sim.drop("simulated link loss")
    }

    // --- signalling, all called with the lock held ---

    /** Runs [step] after [d], replacing any step still pending (a call has one timer at a time, like the firmware). */
    private fun later(d: Duration, step: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(d)
            val me = coroutineContext[Job]
            synchronized(lock) {
                if (pending !== me) return@launch // replaced or cancelled after the delay ended
                pending = null
                step()
            }
        }
    }

    private fun emit(e: TerminalEvent) {
        val sim = current ?: return // not queued while no phone is connected
        sim.deliverEvent(e.encode())
        sim.deliverStatus(status())
    }

    /**
     * For tests: a STATUS notification now, as if the radio state had changed. With [staleSig] it
     * carries that signalling state instead of the current one: a notification the terminal
     * put together just before it took a command, which the phone handles after the command.
     */
    fun notifyStatus(staleSig: SigState? = null) = synchronized(lock) {
        val st = status()
        current?.deliverStatus(if (staleSig != null) st.copy(sigCode = staleSig.code) else st)
    }

    private fun startRegistration() {
        sig = SigState.REGISTERING
        if (radio == TerminalState.GRANTED) {
            later(timing.registration) {
                val fail = failNextRegistration?.also { failNextRegistration = null }
                if (fail != null) {
                    emit(TerminalEvent.RegistrationFailed(fail.code)) // still REGISTERING
                    later(timing.regRetry) { startRegistration() }
                } else {
                    sig = SigState.REGISTERED
                    emit(TerminalEvent.Registered(number!!, mode.code))
                }
            }
        }
    }

    private fun finishActivation(qr: ActivationQr) {
        val fail = failNextActivation?.also { failNextActivation = null } ?: when {
            qr.tokenId in usedTokens -> ActFailReason.TOKEN_USED
            qr.isExpired(unixSeconds()) -> ActFailReason.TOKEN_EXPIRED
            else -> null
        }
        if (fail != null) {
            if (activated) sig = SigState.REGISTERING else sig = SigState.NOT_ACTIVATED
            emit(TerminalEvent.ActivationFailed(fail.code))
            if (activated) startRegistration()
            return
        }
        usedTokens += qr.tokenId
        activated = true
        number = qr.number
        sig = SigState.REGISTERING
        emit(TerminalEvent.Activated(qr.number))
        startRegistration()
    }

    private fun startOutgoing(called: String) {
        sig = SigState.CALLING
        callId = 0
        setupSent = false
        hangupPending = false
        later(timing.setupSent) {
            setupSent = true
            later(timing.setup - timing.setupSent) { callProceeding(called) }
        }
    }

    /** CALL_PROC (the call id), then ALERTING or the network's release, all in one step here. */
    private fun callProceeding(called: String) {
        callId = nextCallId++
        if (hangupPending) {
            // As oc_sig_term.c: the HANGUP that came before the call id is sent as RELEASE now.
            hangupPending = false
            later(timing.release) { end(EndCause.NORMAL) }
            return
        }
        when (called) {
            number -> end(EndCause.BUSY)
            UNREACHABLE -> end(EndCause.UNREACHABLE)
            else -> {
                sig = SigState.RINGING_OUT
                emit(TerminalEvent.Ringing(callId))
                later(timing.peerAnswers) { connectCall() }
            }
        }
    }

    private fun connectCall() {
        sig = SigState.IN_CALL
        emit(TerminalEvent.Connected(callId, 1))
    }

    private fun end(cause: EndCause) {
        pending?.cancel()
        pending = null
        val id = callId
        sig = SigState.REGISTERED
        callId = 0
        answered = false
        setupSent = false
        hangupPending = false
        emit(TerminalEvent.Ended(id, cause.code))
    }

    private fun command(p: ByteArray): WriteResult = synchronized(lock) {
        if (p.isEmpty()) return WriteResult.TooLong
        val a = p.copyOfRange(1, p.size)
        when (p[0].toInt() and 0xFF) {
            Command.ACTIVATE -> {
                // As term_ble.c: length and code first (oc_sig_term_act_prepare), then state.
                if (a.isEmpty() || a.size > GattContract.QR_TEXT_MAX) return WriteResult.TooLong
                val qr = (ActivationQr.parse(a.decodeToString()) as? QrParse.Ok)?.qr ?: return WriteResult.BadArgument
                if (sig == SigState.ACTIVATING || sig.hasCall) return WriteResult.NotNow
                sig = SigState.ACTIVATING
                later(timing.activation) { finishActivation(qr) }
            }
            Command.DIAL -> {
                if (a.isEmpty() || a.size > GattContract.DIAL_MAX) return WriteResult.TooLong
                if (sig != SigState.REGISTERED) return WriteResult.NotNow
                val called = PhoneNumber.normalize(a.decodeToString(), number) ?: return WriteResult.BadArgument
                startOutgoing(called)
            }
            Command.ANSWER -> {
                if (a.isNotEmpty()) return WriteResult.TooLong
                if (sig != SigState.RINGING_IN || answered) return WriteResult.NotNow
                answered = true
                later(timing.answer) { connectCall() }
            }
            Command.REJECT, Command.HANGUP -> {
                if (a.isNotEmpty()) return WriteResult.TooLong
                val ringingIn = sig == SigState.RINGING_IN
                val ok = if (p[0].toInt() == Command.REJECT) {
                    ringingIn
                } else {
                    ringingIn || sig == SigState.CALLING || sig == SigState.RINGING_OUT || sig == SigState.IN_CALL
                }
                if (!ok) return WriteResult.NotNow
                val cause = if (ringingIn) EndCause.REJECTED else EndCause.NORMAL
                if (sig == SigState.CALLING && callId == 0L) {
                    // As oc_sig_term.c: before CALL_PROC there's no call id to RELEASE.
                    if (!setupSent) {
                        end(EndCause.NORMAL) // CALL_SETUP never went out: drop it and end at once
                        return WriteResult.Accepted
                    }
                    hangupPending = true // RELEASE once CALL_PROC gives the call id (its timer keeps running)
                    sig = SigState.RELEASING
                    return WriteResult.Accepted
                }
                sig = SigState.RELEASING
                later(timing.release) { end(cause) }
            }
            Command.SCAN -> return scanCommand(a)
            Command.DEACTIVATE -> {
                if (a.size != 1) return WriteResult.TooLong
                if (a[0] != GattContract.DEACTIVATE_CONFIRM.toByte()) return WriteResult.BadArgument
                if (sig.hasCall) return WriteResult.NotNow // the firmware refuses it during a call
                pending?.cancel()
                pending = null
                activated = false
                number = null
                sig = SigState.NOT_ACTIVATED
                emit(TerminalEvent.Deactivated)
            }
            else -> return WriteResult.BadArgument
        }
        WriteResult.Accepted
    }

    /** COMMAND SCAN, checked like `oc_term_gatt_scan_command` ([a] is after the op byte). */
    private fun scanCommand(a: ByteArray): WriteResult {
        if (a.isEmpty()) return WriteResult.TooLong
        when (a[0].toInt()) {
            Command.SCAN_SET_USER -> {
                if (a.size < 2) return WriteResult.TooLong
                val count = a[1].toInt() and 0xFF
                if (count > ScanList.MAX_USER) return WriteResult.BadArgument
                if (a.size != 2 + 5 * count) return WriteResult.TooLong
                val entries = (0 until count).map { i ->
                    val at = 2 + 5 * i
                    val hz = (0 until 4).fold(0L) { v, k -> v or ((a[at + k].toLong() and 0xFF) shl (8 * k)) }
                    ScanEntry(hz, fixed = a[at + 4].toInt() and 1 != 0, sourceCode = ScanSource.USER.code, active = true)
                }
                if (entries.any { ChannelGrid.channelOf(it.freqHz) == null }) return WriteResult.BadArgument
                userChannels.clear()
                userChannels += entries
            }
            Command.SCAN_SET_FALLBACK -> {
                if (a.size != 3) return WriteResult.TooLong
                val after = a[1].toInt() and 0xFF
                val chunk = a[2].toInt() and 0xFF
                if (after > ScanList.NEVER || chunk !in 1..ChannelGrid.COUNT) return WriteResult.BadArgument
                fallbackAfter = after
                fallbackChunk = chunk
            }
            Command.SCAN_FORGET_LEARNED -> {
                if (a.size != 1) return WriteResult.TooLong
                learned.clear()
            }
            else -> return WriteResult.BadArgument
        }
        return WriteResult.Accepted
    }

    /** The assembled list, as `oc_term_scan_list`: last, user, learned, the six defaults; FIXED only in Part 97. */
    private fun scanList(): ScanList {
        val out = mutableListOf<ScanEntry>()
        fun add(hz: Long, fixed: Boolean, source: ScanSource) {
            val active = !fixed || mode == RegMode.PART97
            if (active && out.any { it.active && it.freqHz == hz && it.fixed == fixed }) return
            out += ScanEntry(hz, fixed, source.code, active)
        }
        lastServing?.let { add(it, false, ScanSource.LAST) }
        userChannels.forEach { add(it.freqHz, it.fixed, ScanSource.USER) }
        learned.forEach { add(it, false, ScanSource.LEARNED) }
        (0 until 6).forEach { add(ChannelGrid.freqHz(it), false, ScanSource.DEFAULT) }
        return ScanList(mode.code, fallbackAfter, fallbackChunk, netVer = 0, entries = out)
    }

    /** STATUS bytes 20-26: searching, the first active entry; on the cell, its anchor. */
    private fun scanTail(): ScanTail {
        if (radio != TerminalState.SEARCH) return ScanTail(0, 0, 0, ANCHOR_HZ / 1000)
        val active = scanList().entries.filter { it.active }
        val first = active.first()
        return ScanTail(1, active.size, first.sourceCode, first.freqHz / 1000)
    }

    private fun status() = TerminalStatus(
        stateCode = radio.code,
        bandCode = 0,
        tierCode = 2,
        rssiDbm = rssi,
        snrQuarterDb = 50,
        tmid = tmid,
        frame = 1000L + frames,
        cellSeed = 0x5EED1234L,
        sigCode = sig.code,
        scan = scanTail(),
    )

    /**
     * One radio frame: the attach walk, a jittered RSSI every 8 frames (shown on the next
     * STATUS; RSSI alone notifies nothing, like the firmware), one queued UP frame sent.
     */
    private fun tick(sim: Sim): ByteArray? = synchronized(lock) {
        frames++
        val next = when {
            radio == TerminalState.GRANTED -> TerminalState.GRANTED
            frames < 5 -> TerminalState.SEARCH
            frames < 9 -> TerminalState.SYNCED
            frames < 13 -> TerminalState.ATTACHING
            else -> TerminalState.GRANTED
        }
        val changed = next != radio
        radio = next
        if (changed && radio == TerminalState.GRANTED && lastServing != ANCHOR_HZ) {
            lastServing?.let { learned.add(0, it) } // oc_term_scan_serving
            lastServing = ANCHOR_HZ
        }
        if (frames % 8 == 0) rssi = -52 + random.nextInt(-3, 4)
        if (changed && radio == TerminalState.GRANTED && sig == SigState.REGISTERING && pending == null) startRegistration()
        if (changed) sim.deliverStatus(status())
        if (radio == TerminalState.GRANTED) sim.queue.removeFirstOrNull() else null
    }

    private inner class Sim(private val events: ConnectionEvents) : Connection {
        val queue = ArrayDeque<ByteArray>()
        private var closed = false
        private var job: Job? = null

        override val mtu: Int = 247

        fun start() {
            job = scope.launch {
                while (isActive) {
                    delay(GattContract.FRAME_MILLIS)
                    tick(this@Sim)?.let { echo(it) }
                }
            }
        }

        private fun echo(payload: ByteArray) {
            scope.launch {
                delay(echoDelay)
                val open = synchronized(lock) { !closed }
                if (open) events.onDownlink(payload)
            }
        }

        fun deliverEvent(raw: ByteArray) {
            if (!closed) events.onEvent(raw)
        }

        fun deliverStatus(st: TerminalStatus) {
            if (!closed) events.onStatus(st.encode())
        }

        fun drop(reason: String) {
            synchronized(lock) { closed = true }
            job?.cancel()
            events.onClosed(reason)
        }

        override suspend fun write(payload: ByteArray): WriteResult {
            delay(bleDelay)
            synchronized(lock) {
                return when {
                    closed -> WriteResult.NotConnected
                    payload.size > GattContract.MAX_PAYLOAD -> WriteResult.TooLong
                    radio != TerminalState.GRANTED -> WriteResult.NotNow
                    queue.size >= 4 -> WriteResult.NotNow
                    else -> {
                        queue.addLast(payload.copyOf())
                        WriteResult.Accepted
                    }
                }
            }
        }

        override suspend fun writeCommand(payload: ByteArray): WriteResult {
            delay(bleDelay)
            if (synchronized(lock) { closed }) return WriteResult.NotConnected
            if (payload.size > GattContract.COMMAND_MAX) return WriteResult.TooLong
            return command(payload)
        }

        override suspend fun readStatus(): ByteArray? {
            delay(bleDelay)
            return synchronized(lock) { if (closed) null else status().encode() }
        }

        override suspend fun readScan(): ByteArray? {
            delay(bleDelay)
            return synchronized(lock) { if (closed || !scanSupported) null else scanList().encode() }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                if (current === this) current = null
            }
            job?.cancel()
        }
    }

    companion object {
        /** The address the app uses for the simulated terminal. */
        const val ADDRESS = "SIMULATED"

        /** The number the demo code activates. */
        const val DEMO_NUMBER = "+883160655501234"

        /** A peer that answers, like `ocbench net`'s simulated peer: the echo service, 00100. */
        const val PEER = "+883160655500100"

        /** Calls to this number end "unreachable". */
        const val UNREACHABLE = "+883160655509999"

        /** The simulated cell's anchor: 903.25 MHz (grid channel 2). */
        val ANCHOR_HZ: Long = ChannelGrid.freqHz(2)
    }
}
