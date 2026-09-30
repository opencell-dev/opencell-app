package org.opencell.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeAudio
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.SigState

class CallTonePlayerTest {
    private val peer = "+883160655500100"
    private val idle = PhoneState(linkUp = true, sig = SigState.REGISTERED)

    private fun state(phase: CallPhase, dir: Direction? = Direction.OUTGOING, cause: Int? = null, connectedAt: Long? = null, at: Long = 1) =
        idle.copy(call = Call(5, dir, peer, phase, causeCode = cause, connectedAt = connectedAt), callChangedAt = at)

    private fun ended(cause: EndCause?, dir: Direction? = Direction.OUTGOING, connected: Boolean = false) =
        toneFor(state(CallPhase.ENDED, dir, cause?.code, if (connected) 1_000L else null))?.tone

    @Test
    fun ringbackOnlyWhileAnOutgoingCallRings() {
        assertEquals(CallTone.RINGBACK, toneFor(state(CallPhase.RINGING))?.tone)
        assertNull(toneFor(state(CallPhase.RINGING))?.maxMillis) // until the call moves on
        assertNull(toneFor(state(CallPhase.CALLING)))
        assertNull(toneFor(state(CallPhase.CONNECTED)))
        assertNull(toneFor(state(CallPhase.RELEASING)))
        assertNull(toneFor(state(CallPhase.INCOMING, Direction.INCOMING)))
        assertNull(toneFor(idle))
    }

    @Test
    fun everyCauseOfAnUnansweredOutgoingCallHasItsTone() {
        assertNull(ended(EndCause.NORMAL)) // the user hung up
        assertEquals(CallTone.BUSY, ended(EndCause.REJECTED))
        assertEquals(CallTone.BUSY, ended(EndCause.BUSY))
        assertEquals(CallTone.REORDER, ended(EndCause.NO_ANSWER))
        assertEquals(CallTone.UNOBTAINABLE, ended(EndCause.UNREACHABLE))
        assertEquals(CallTone.REORDER, ended(EndCause.NETWORK_FAILURE))
        assertEquals(CallTone.REORDER, ended(EndCause.LINK_LOST))
        assertEquals(CallTone.REORDER, toneFor(state(CallPhase.ENDED, cause = 42))?.tone) // a cause this app doesn't know
        assertNull(ended(null)) // the end was missed while disconnected
    }

    @Test
    fun aDroppedCallGetsReorderAndAnOrdinaryEndNothing() {
        for (dir in listOf(Direction.OUTGOING, Direction.INCOMING)) {
            assertEquals(CallTone.REORDER, ended(EndCause.NETWORK_FAILURE, dir, connected = true))
            assertEquals(CallTone.REORDER, ended(EndCause.LINK_LOST, dir, connected = true))
            assertNull(ended(EndCause.NORMAL, dir, connected = true))
            assertNull(ended(EndCause.BUSY, dir, connected = true))
        }
        assertNull(ended(EndCause.BUSY, Direction.INCOMING)) // an incoming call that never connected
    }

    @Test
    fun theEndTonesStopByThemselves() {
        assertEquals(6_000L, toneFor(state(CallPhase.ENDED, cause = EndCause.BUSY.code))?.maxMillis)
        assertEquals(4_000L, toneFor(state(CallPhase.ENDED, cause = EndCause.NETWORK_FAILURE.code))?.maxMillis)
        assertEquals(4_000L, toneFor(state(CallPhase.ENDED, cause = EndCause.UNREACHABLE.code))?.maxMillis)
    }

    private fun kotlinx.coroutines.test.TestScope.player(plan: TonePlan = TonePlans.NORTH_AMERICA): Triple<MutableStateFlow<PhoneState>, FakeAudio, CallTonePlayer> {
        val phone = MutableStateFlow(idle)
        val audio = FakeAudio()
        val p = CallTonePlayer(phone, MutableStateFlow(plan), audio, backgroundScope)
        runCurrent()
        return Triple(phone, audio, p)
    }

    @Test
    fun ringbackPlaysUntilConnectedAndStopsAtOnce() = runTest {
        val (phone, audio, p) = player()
        phone.value = state(CallPhase.RINGING)
        runCurrent()
        assertEquals(CallTone.RINGBACK, p.playing.value)
        advanceTimeBy(7_000)
        val out = audio.speakers.single()
        // 2 s of tone, 4 s of silence, then tone again: in 120 ms blocks.
        val loud = out.played.map { b -> b.any { it != 0.toShort() } }
        assertTrue(loud.subList(0, 16).all { it }) // 0-1.92 s
        assertTrue(loud.subList(17, 50).none { it }) // 2.04-6.0 s
        assertTrue(loud[50]) // 6.0 s: the next burst
        phone.value = state(CallPhase.CONNECTED, connectedAt = 7_000)
        runCurrent()
        assertNull(p.playing.value)
        assertTrue(out.closed)
    }

    @Test
    fun busyPlaysSixSecondsThenStops() = runTest {
        val (phone, audio, p) = player()
        phone.value = state(CallPhase.ENDED, cause = EndCause.BUSY.code)
        runCurrent()
        assertEquals(CallTone.BUSY, p.playing.value)
        advanceTimeBy(10_000)
        assertNull(p.playing.value)
        assertEquals(50, audio.speakers.single().played.size) // 6 s in 120 ms blocks
        assertTrue(audio.speakers.single().closed)
    }

    @Test
    fun closingTheEndedCallStopsTheTone() = runTest {
        val (phone, audio, p) = player()
        phone.value = state(CallPhase.ENDED, cause = EndCause.NETWORK_FAILURE.code)
        advanceTimeBy(500)
        phone.value = idle // Close
        runCurrent()
        assertNull(p.playing.value)
        assertTrue(audio.speakers.single().closed)
    }

    @Test
    fun theSitPlaysOnceAndTheUkPlanPlaysNumberUnobtainable() = runTest {
        val (phone, audio, _) = player()
        phone.value = state(CallPhase.ENDED, cause = EndCause.UNREACHABLE.code)
        advanceTimeBy(5_000)
        assertEquals(8, audio.speakers.single().played.size) // the SIT, 928 ms, once

        val (ukPhone, ukAudio, _) = player(TonePlans.UK)
        ukPhone.value = state(CallPhase.ENDED, cause = EndCause.UNREACHABLE.code)
        advanceTimeBy(5_000)
        assertEquals(34, ukAudio.speakers.single().played.size) // continuous 400 Hz for 4 s
    }

    @Test
    fun aSecondBusyCallPlaysAgain() = runTest {
        val (phone, audio, _) = player()
        phone.value = state(CallPhase.ENDED, cause = EndCause.BUSY.code, at = 1)
        advanceTimeBy(7_000)
        phone.value = state(CallPhase.ENDED, cause = EndCause.BUSY.code, at = 9)
        advanceTimeBy(1_000)
        assertEquals(2, audio.speakers.size)
    }
}
