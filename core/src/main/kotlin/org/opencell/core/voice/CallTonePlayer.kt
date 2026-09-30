package org.opencell.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.EndCause

/**
 * A tone to play now: [tone] for at most [maxMillis] (null: until the call
 * moves on). [key] tells one occasion from the next (two busy calls in a row).
 */
data class ToneCue(val tone: CallTone, val maxMillis: Long?, val key: Long)

/**
 * Which call progress tone the phone should be playing (voice spec §6.3), from
 * the phone's state alone. Tones are local: nothing about them goes over the air.
 *
 * - Ringback while an outgoing call is RINGING (the far end got ALERTING) and the terminal is connected.
 * - After an outgoing call ends before it connected: busy for "busy" and
 *   "rejected", unobtainable for "unreachable", reorder for "no answer",
 *   "network failure", "link lost" and causes this app doesn't know; nothing
 *   for "normal" (the user hung up).
 * - After a connected call (either way) ends with "network failure" or "link
 *   lost": reorder (a dropped call). Nothing for the other causes.
 * - Nothing for incoming calls that never connected, nothing while the call is
 *   CALLING, CONNECTED or RELEASING, and nothing for a call whose end the app
 *   missed (no cause).
 */
fun toneFor(s: PhoneState): ToneCue? {
    val c = s.call ?: return null
    return when (c.phase) {
        CallPhase.RINGING -> if (s.linkUp && c.direction != Direction.INCOMING) ToneCue(CallTone.RINGBACK, null, c.id ?: 0) else null
        CallPhase.ENDED -> {
            val cause = c.causeCode ?: return null
            val tone = if (c.connectedAt == null) {
                if (c.direction != Direction.OUTGOING) return null
                when (cause) {
                    EndCause.NORMAL.code -> return null
                    EndCause.BUSY.code, EndCause.REJECTED.code -> CallTone.BUSY
                    EndCause.UNREACHABLE.code -> CallTone.UNOBTAINABLE
                    else -> CallTone.REORDER
                }
            } else {
                when (cause) {
                    EndCause.NETWORK_FAILURE.code, EndCause.LINK_LOST.code -> CallTone.REORDER
                    else -> return null
                }
            }
            ToneCue(tone, TIMEOUT_MILLIS.getValue(tone), s.callChangedAt)
        }
        else -> null
    }
}

/** How long each end-of-call tone plays before it stops by itself (the screen keeps the cause). */
val TIMEOUT_MILLIS = mapOf(
    CallTone.BUSY to 6_000L,
    CallTone.REORDER to 4_000L,
    CallTone.UNOBTAINABLE to 4_000L,
)

/**
 * Plays [toneFor]'s tone from [plan] on the call audio output ([AudioIo.openSpeaker],
 * the same path and route as voice), 120 ms at a time. A new cue, or none,
 * stops the current tone at once (CONNECTED, HANGUP, Close); a cue's
 * [ToneCue.maxMillis] stops it too. [playing] is the tone being heard, if any.
 */
class CallTonePlayer(
    phone: StateFlow<PhoneState>,
    plan: StateFlow<TonePlan>,
    private val audio: AudioIo,
    scope: CoroutineScope,
) {
    private val _playing = MutableStateFlow<CallTone?>(null)
    val playing: StateFlow<CallTone?> = _playing.asStateFlow()

    init {
        scope.launch {
            combine(phone, plan) { s, p -> toneFor(s)?.let { it to p } }
                .distinctUntilChanged()
                .collectLatest { cue -> if (cue != null) play(cue.first, cue.second) }
        }
    }

    private suspend fun play(cue: ToneCue, plan: TonePlan) {
        val out = audio.openSpeaker() ?: return
        val gen = ToneGenerator(plan[cue.tone])
        val block = ShortArray(BlockCodec.SAMPLES)
        var played = 0L
        _playing.value = cue.tone
        try {
            while (!gen.finished && played < (cue.maxMillis ?: Long.MAX_VALUE)) {
                gen.fill(block)
                out.write(block)
                played += BlockCodec.BLOCK_MILLIS
            }
        } finally {
            _playing.value = null
            out.close()
        }
    }
}
