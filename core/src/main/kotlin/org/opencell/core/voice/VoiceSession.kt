package org.opencell.core.voice

import kotlinx.coroutines.CoroutineScope
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
    ) : VoiceState
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
 *   a steady stream.
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
        val enc = codecs.create(codec)
        val dec = codecs.create(codec)
        if (enc == null || dec == null) {
            enc?.close()
            dec?.close()
            _state.value = VoiceState.Unsupported(codec)
            return
        }
        muted = false
        _state.value = VoiceState.On(codec)
        val up = BlockCodec(enc)
        val down = BlockCodec(dec)
        val jitter = JitterBuffer(jitterTarget)
        job = scope.launch {
            launch { uplink(up) }
            launch { downlink(down, jitter) }
            launch { playout(down, jitter) }
        }.also {
            it.invokeOnCompletion {
                enc.close()
                dec.close()
            }
        }
    }

    private fun on(change: (VoiceState.On) -> VoiceState.On) {
        _state.update { if (it is VoiceState.On) change(it) else it }
    }

    private fun stats(change: (VoiceStats) -> VoiceStats) = on { it.copy(stats = change(it.stats)) }

    private suspend fun uplink(codec: BlockCodec) = coroutineScope {
        val out = Channel<ByteArray>(1, BufferOverflow.DROP_OLDEST) { stats { s -> s.copy(late = s.late + 1) } }
        launch {
            for (payload in out) {
                val r = link.writeUp(payload)
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
        var tried = false // opening failed while allowed: try again only after micAllowed goes false and back
        try {
            while (isActive) {
                if (!micAllowed.value) {
                    tried = false
                    mic?.close()
                    mic = null
                } else if (mic == null && !tried) {
                    tried = true
                    mic = audio.openMic()
                }
                on { it.copy(mic = mic != null) }
                val heard = mic?.read(pcm) ?: false
                if (!heard) {
                    mic?.close()
                    mic = null
                    delay(BlockCodec.BLOCK_MILLIS)
                }
                if (!heard || muted) pcm.fill(0)
                out.send(codec.encode(pcm))
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
        val speaker = audio.openSpeaker()
        on { it.copy(output = speaker != null) }
        try {
            while (currentCoroutineContext().isActive) {
                val pcm = when (val p = jitter.poll()) {
                    is Playout.Play -> codec.decode(p.payload)
                    is Playout.Conceal -> codec.decode(p.payload).also { scale(it, p.gain) }
                    Playout.Silence -> ShortArray(codec.blockSamples)
                }
                stats { it.copy(jitter = jitter.counts()) }
                if (speaker != null) speaker.write(pcm) else delay(BlockCodec.BLOCK_MILLIS)
            }
        } finally {
            speaker?.close()
        }
    }

    private fun scale(pcm: ShortArray, gain: Float) {
        for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
    }
}
