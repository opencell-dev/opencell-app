package org.opencell.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.WriteResult
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.PhoneState
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Counters of the current call's voice, for the call screen and the bench. */
data class VoiceStats(
    /** Blocks the terminal took for its next UL slot. */
    val sent: Int = 0,
    /** Blocks refused with 0x80 (no grant, or the terminal's UL queue full): dropped, never retried. */
    val dropped: Int = 0,
    /** Blocks never written because the next one was ready first (a slow BLE write). */
    val late: Int = 0,
    /** Blocks whose write failed any other way. */
    val failed: Int = 0,
    /** DOWN payloads of the codec's size, handed to the jitter buffer. */
    val received: Int = 0,
    /** DOWN payloads of another size (a peer's test frames): ignored. */
    val notVoice: Int = 0,
    val jitter: JitterCounts = JitterCounts(),
)

sealed interface VoiceState {
    /** No connected call (or the link to the terminal is down). */
    data object Off : VoiceState

    /** The call connected with a codec this build doesn't have: no audio either way. */
    data class Unsupported(val codec: Int) : VoiceState

    /**
     * Audio is running. [mic] false: the microphone isn't open (not allowed yet, or
     * it failed) and silence goes out instead. [output] false: the audio output
     * couldn't be opened, so nothing is heard.
     */
    data class On(
        val codec: Int,
        val muted: Boolean = false,
        val mic: Boolean = false,
        val output: Boolean = false,
        val stats: VoiceStats = VoiceStats(),
        /** The microphone failed mid-call (the audio server restarted, say); it is being reopened. */
        val micFailed: Boolean = false,
        /** The audio output failed mid-call; it is being rebuilt. */
        val outputFailed: Boolean = false,
    ) : VoiceState

    /** Voice stopped for the rest of this call because something threw ([reason]); the call goes on. */
    data class Failed(val reason: String) : VoiceState
}

/**
 * Voice for the phone's calls (voice spec §5): runs while the call is CONNECTED
 * and the link is up, with the codec CONNECTED named ([CodecId.DEFAULT] if the
 * app only learned of the call from STATUS).
 *
 * - **Uplink.** The microphone's clock paces it: every 120 ms block is encoded
 *   into one app data frame and written once with [TerminalLink.writeUp]. A 0x80
 *   drops that block (a late voice frame is worthless); a block not yet written
 *   when the next is ready is dropped too, so a slow BLE write never turns into
 *   delay. Muted, or without a microphone ([micAllowed] false, or it failed to
 *   open), encoded silence goes out every 120 ms instead, so the far end keeps
 *   a steady stream. After a microphone stall only the newest buffered block
 *   goes out (voice spec §11 R-a).
 * - **Device failures.** A microphone or output that worked and then failed
 *   (the audio server restarted, say) is reopened after 1 s, 2 s, then every
 *   4 s; [VoiceState.On.micFailed] and [VoiceState.On.outputFailed] say so.
 *   Anything that throws ends voice as [VoiceState.Failed], never the app or
 *   the call. The output opens before the microphone: it begins the call's
 *   audio mode and route.
 * - **Downlink.** Payloads of the codec's size go into a [JitterBuffer]; the
 *   audio output's clock takes one block every 120 ms and plays it, conceals a
 *   missing one, or plays silence.
 *
 * The encoder and decoder are separate codec instances (one per direction),
 * made at the start of each call's voice and closed when it stops.
 */
class VoiceSession(
    private val link: TerminalLink,
    phone: StateFlow<PhoneState>,
    private val codecs: VoiceCodecFactory,
    private val audio: AudioIo,
    private val scope: CoroutineScope,
    private val micAllowed: StateFlow<Boolean> = MutableStateFlow(true),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val jitterTarget: Int = JitterBuffer.DEFAULT_TARGET,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Off)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    @Volatile
    private var muted = false
    private var job: Job? = null

    init {
        scope.launch {
            phone.map(::wanted).distinctUntilChanged().collect { want ->
                job?.cancelAndJoin()
                job = null
                _state.value = VoiceState.Off
                if (want != null) start(want.codec)
            }
        }
    }

    /** Mutes or unmutes the microphone for the rest of this call (encoded silence goes out). */
    fun setMuted(on: Boolean) {
        muted = on
        _state.update { if (it is VoiceState.On) it.copy(muted = on) else it }
    }

    private data class Want(val callId: Long?, val codec: Int)

    private fun wanted(s: PhoneState): Want? {
        val c = s.call ?: return null
        if (!s.linkUp || c.phase != CallPhase.CONNECTED) return null
        return Want(c.id, c.codec ?: CodecId.DEFAULT)
    }

    private fun start(codec: Int) {
        var enc: VoiceCodec? = null
        var dec: VoiceCodec? = null
        val up: BlockCodec
        val down: BlockCodec
        try {
            enc = codecs.create(codec)
            dec = codecs.create(codec)
            if (enc == null || dec == null) {
                enc?.close()
                dec?.close()
                _state.value = VoiceState.Unsupported(codec)
                return
            }
            up = BlockCodec(enc)
            down = BlockCodec(dec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // a codec library that won't load (UnsatisfiedLinkError), a codec that won't start
            enc?.close()
            dec?.close()
            fail(e)
            return
        }
        muted = false
        _state.value = VoiceState.On(codec)
        val jitter = JitterBuffer(jitterTarget)
        job = scope.launch {
            try {
                coroutineScope {
                    // The output first: opening it begins the call's audio mode and route, which
                    // some phones need before capture starts to pick the microphone and echo canceller.
                    launch(start = CoroutineStart.UNDISPATCHED) { playout(down, jitter) }
                    launch { uplink(up) }
                    launch { downlink(down, jitter) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // anything thrown in voice ends voice, never the app or the call
                fail(e)
            }
        }.also {
            it.invokeOnCompletion {
                enc.close()
                dec.close()
            }
        }
    }

    private fun fail(e: Throwable) {
        log.log(Level.WARNING, "voice stopped", e)
        _state.value = VoiceState.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun on(change: (VoiceState.On) -> VoiceState.On) {
        _state.update { if (it is VoiceState.On) change(it) else it }
    }

    private fun stats(change: (VoiceStats) -> VoiceStats) = on { it.copy(stats = change(it.stats)) }

    private fun late() = stats { it.copy(late = it.late + 1) }

    /** One encoded block; [afterStall]: the microphone had stalled before it, and may hand over a backlog. */
    private class Block(val payload: ByteArray, val afterStall: Boolean)

    private suspend fun uplink(codec: BlockCodec) = coroutineScope {
        val out = Channel<Block>(1, BufferOverflow.DROP_OLDEST) { late() }
        launch {
            for (first in out) {
                var block = first
                if (block.afterStall) {
                    // After a stall the microphone hands over what it buffered, back to back.
                    // Send only the newest: every extra block would sit in the terminal's
                    // uplink queue as lasting delay (voice spec §11 R-a).
                    delay(BlockCodec.BLOCK_MILLIS / 2)
                    out.tryReceive().getOrNull()?.let { newer ->
                        late()
                        block = newer
                    }
                }
                val r = link.writeUp(block.payload)
                stats {
                    when (r) {
                        WriteResult.Accepted -> it.copy(sent = it.sent + 1)
                        WriteResult.NotNow -> it.copy(dropped = it.dropped + 1)
                        else -> it.copy(failed = it.failed + 1)
                    }
                }
            }
        }
        val pcm = ShortArray(codec.blockSamples)
        var mic: MicInput? = null
        var tried = false // opening was refused while allowed: try again only after micAllowed goes false and back
        val retry = Backoff() // the microphone worked and then failed: reopen it, less and less often
        var lastRead: ComparableTimeMark? = null
        try {
            while (isActive) {
                if (!micAllowed.value) {
                    tried = false
                    retry.clear()
                    mic?.close()
                    mic = null
                } else if (mic == null && (if (retry.pending) retry.due() else !tried)) {
                    tried = true
                    mic = audio.openMic()
                    lastRead = null
                    if (mic != null) retry.clear() else if (retry.pending) retry.again()
                }
                on { it.copy(mic = mic != null, micFailed = retry.pending) }
                val heard = mic?.read(pcm) ?: false
                var stalled = false
                if (heard) {
                    val now = timeSource.markNow()
                    stalled = lastRead?.let { now - it >= STALL } ?: false
                    lastRead = now
                } else {
                    if (mic != null) { // it worked and stopped: the device failed (the audio server restarted, say)
                        mic.close()
                        mic = null
                        retry.start()
                        on { it.copy(mic = false, micFailed = true) }
                    }
                    delay(BlockCodec.BLOCK_MILLIS)
                }
                if (!heard || muted) pcm.fill(0)
                out.send(Block(codec.encode(pcm), stalled))
            }
        } finally {
            mic?.close()
            out.close()
        }
    }

    private suspend fun downlink(codec: BlockCodec, jitter: JitterBuffer) {
        val start = timeSource.markNow()
        link.downlink.collect { d ->
            if (d.payload.size == codec.payloadBytes) {
                jitter.offer(d.payload, (d.at - start).inWholeMilliseconds)
                stats { it.copy(received = it.received + 1) }
            } else {
                stats { it.copy(notVoice = it.notVoice + 1) }
            }
        }
    }

    private suspend fun playout(codec: BlockCodec, jitter: JitterBuffer) {
        var speaker: SpeakerOutput? = null
        val retry = Backoff() // the output worked and then failed: rebuild it, less and less often
        try {
            speaker = audio.openSpeaker()
            on { it.copy(output = speaker != null) }
            while (currentCoroutineContext().isActive) {
                if (speaker == null && retry.pending && retry.due()) {
                    speaker = audio.openSpeaker()
                    if (speaker != null) retry.clear() else retry.again()
                    on { it.copy(output = speaker != null, outputFailed = retry.pending) }
                }
                val pcm = when (val p = jitter.poll()) {
                    is Playout.Play -> codec.decode(p.payload)
                    is Playout.Conceal -> codec.decode(p.payload).also { scale(it, p.gain) }
                    Playout.Silence -> ShortArray(codec.blockSamples)
                }
                stats { it.copy(jitter = jitter.counts()) }
                val out = speaker
                if (out == null) {
                    delay(BlockCodec.BLOCK_MILLIS)
                } else if (!out.write(pcm)) {
                    out.close()
                    speaker = null
                    retry.start()
                    on { it.copy(output = false, outputFailed = true) }
                }
            }
        } finally {
            speaker?.close()
        }
    }

    /** When to try a failed audio device again: after 1 s, then 2 s, then every 4 s. */
    private inner class Backoff {
        private var at: ComparableTimeMark? = null
        private var wait = RETRY_FIRST

        val pending: Boolean get() = at != null

        fun due(): Boolean = at?.hasPassedNow() ?: false

        fun start() {
            wait = RETRY_FIRST
            at = timeSource.markNow() + wait
        }

        fun again() {
            wait = minOf(wait * 2, RETRY_MAX)
            at = timeSource.markNow() + wait
        }

        fun clear() {
            at = null
            wait = RETRY_FIRST
        }
    }

    private fun scale(pcm: ShortArray, gain: Float) {
        for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
    }

    private companion object {
        val log: Logger = Logger.getLogger(VoiceSession::class.java.name)
        val RETRY_FIRST = 1.seconds
        val RETRY_MAX = 4.seconds

        /** A microphone read this much later than the one before means the device stalled. */
        val STALL = (BlockCodec.BLOCK_MILLIS * 3 / 2).milliseconds
    }
}
