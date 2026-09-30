package org.opencell.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencell.core.link.Backoff
import org.opencell.core.link.Connector
import org.opencell.core.link.LinkManager
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.RetryPolicy
import org.opencell.core.link.SendOutcome
import org.opencell.core.link.UplinkSender
import org.opencell.core.loopback.LoopbackConfig
import org.opencell.core.loopback.LoopbackReport
import org.opencell.core.loopback.LoopbackRunner
import org.opencell.core.phone.PhoneMemory
import org.opencell.core.phone.PhoneSession
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalState
import org.opencell.core.voice.AudioIo
import org.opencell.core.voice.VoiceCodecFactory
import org.opencell.core.voice.VoiceSession
import org.opencell.core.voice.VoiceState
import kotlin.time.TimeSource

/**
 * Everything the app does with one terminal, independent of Android: the
 * link (with reconnects), UP sending with retries, the phone (activation,
 * registration, calls), voice in connected calls, the console log and the
 * loopback test. The Android layer only supplies a [Connector], a long-lived
 * [scope], a [PhoneMemory] and, for voice, the codecs, the audio devices and
 * whether the microphone may be used now ([micAllowed]).
 */
class TerminalSession(
    connector: Connector,
    private val scope: CoroutineScope,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    wallClock: () -> Long = System::currentTimeMillis,
    retryPolicy: RetryPolicy = RetryPolicy(),
    reconnect: Backoff = Backoff.RECONNECT,
    phoneMemory: PhoneMemory = PhoneMemory.inMemory(),
    codecs: VoiceCodecFactory = VoiceCodecFactory.NONE,
    audio: AudioIo = AudioIo.NONE,
    micAllowed: StateFlow<Boolean> = MutableStateFlow(true),
) {
    val link = LinkManager(connector, scope, reconnect, timeSource, wallClock)
    val sender = UplinkSender(link, retryPolicy)
    val console = ConsoleLog(wallClock = wallClock)
    private val started = timeSource.markNow()
    val phone = PhoneSession(
        link, sender, scope, phoneMemory, { kind, text -> console.add(kind, text) }, wallClock,
        monotonic = { started.elapsedNow().inWholeMilliseconds },
    )
    val voice = VoiceSession(link, phone.state, codecs, audio, scope, micAllowed, timeSource)
    private val runner = LoopbackRunner(link, sender, timeSource)

    private val _loopback = MutableStateFlow<LoopbackReport?>(null)

    /** The running or last finished loopback test. */
    val loopback: StateFlow<LoopbackReport?> = _loopback.asStateFlow()
    private var loopbackJob: Job? = null

    init {
        scope.launch {
            link.downlink.collect {
                // Voice is eight payloads a second: the console would hold nothing else.
                if (voice.state.value !is VoiceState.On) console.add(ConsoleKind.DOWN, "DOWN ${it.payload.size} B", it.payload, it.wallMillis)
            }
        }
        scope.launch {
            link.state.drop(1).collect { console.add(ConsoleKind.INFO, describe(it)) }
        }
        scope.launch {
            link.status.map { it?.stateCode }.distinctUntilChanged().collect { code ->
                if (code != null) {
                    val name = TerminalState.fromCode(code)?.label ?: "unknown ($code)"
                    console.add(ConsoleKind.INFO, "Terminal state: $name")
                }
            }
        }
        scope.launch {
            link.status.map { it?.sigCode }.distinctUntilChanged().collect { code ->
                if (code != null) {
                    val name = SigState.fromCode(code)?.label ?: "unknown ($code)"
                    console.add(ConsoleKind.INFO, "Signalling state: $name")
                }
            }
        }
    }

    suspend fun connect(target: LinkTarget) = link.connect(target)

    suspend fun disconnect() {
        loopbackJob?.cancelAndJoin()
        link.disconnect()
    }

    /** Sends one console payload with the retry policy and logs the outcome. */
    fun send(payload: ByteArray, enforceLimit: Boolean = true): Job = scope.launch {
        val outcome = sender.send(payload, enforceLimit)
        val kind = if (outcome is SendOutcome.Sent) ConsoleKind.UP else ConsoleKind.ERROR
        console.add(kind, "UP ${payload.size} B: ${outcome.label}", payload)
    }

    fun refreshStatus(): Job = scope.launch {
        if (link.refreshStatus() == null) console.add(ConsoleKind.ERROR, "STATUS read failed")
    }

    val loopbackRunning: Boolean get() = loopbackJob?.isActive == true

    /** Starts a loopback test. Returns a problem description instead if it can't start. */
    fun startLoopback(config: LoopbackConfig): String? {
        config.problem()?.let { return it }
        if (loopbackRunning) return "A loopback test is already running"
        if (!link.state.value.isConnected) return "Not connected"
        loopbackJob = scope.launch {
            console.add(ConsoleKind.INFO, "Loopback: ${config.count} x ${config.payload.size + if (config.tagSequence) LoopbackConfig.TAG_LEN else 0} B every ${config.interval}")
            try {
                val r = runner.run(config) { _loopback.value = it }
                val s = r.stats
                console.add(
                    ConsoleKind.INFO,
                    "Loopback done: ${s.received}/${s.sent} echoed, mean ${s.mean ?: "-"}, max ${s.max ?: "-"}, " +
                        "${s.withinThreshold} within ${s.threshold}",
                )
            } finally {
                _loopback.update { it?.copy(running = false) }
            }
        }
        return null
    }

    fun stopLoopback() {
        loopbackJob?.cancel()
    }

    private fun describe(s: LinkState): String = when (s) {
        LinkState.Disconnected -> "Disconnected"
        is LinkState.Connecting -> "Connecting to ${s.target.label()}" + if (s.attempt > 1) " (attempt ${s.attempt})" else ""
        is LinkState.Pairing -> "Pairing with ${s.target.label()}: enter the code shown on the terminal"
        is LinkState.PairingFailed -> "Pairing with ${s.target.label()} failed: ${s.reason}"
        is LinkState.Connected -> "Connected to ${s.target.label()}, MTU ${s.mtu}"
        is LinkState.WaitingToReconnect -> "Link lost (${s.reason}); retrying in ${s.delay}"
    }

    private fun LinkTarget.label() = name ?: address
}
