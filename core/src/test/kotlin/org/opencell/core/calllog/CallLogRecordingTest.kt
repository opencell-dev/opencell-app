package org.opencell.core.calllog

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeAudio
import org.opencell.core.fakes.FakeCodecs
import org.opencell.core.link.LinkTarget
import org.opencell.core.phone.CallPhase
import org.opencell.core.protocol.EndCause
import org.opencell.core.session.TerminalSession
import org.opencell.core.sim.SimulatedTerminal
import org.opencell.core.voice.CodecId

/** End to end on virtual time: calls through the demo terminal land in the session's call log (spec §4.1). */
class CallLogRecordingTest {
    private class Rig(val sim: SimulatedTerminal, val session: TerminalSession) {
        val log get() = session.callLog
    }

    private suspend fun TestScope.registered(): Rig {
        val sim = SimulatedTerminal(backgroundScope, activatedNumber = SimulatedTerminal.DEMO_NUMBER)
        val session = TerminalSession(
            sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime },
            codecs = FakeCodecs(), audio = FakeAudio(),
        )
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(5_000)
        return Rig(sim, session)
    }

    @Test
    fun anAnsweredOutgoingCallIsLoggedWithItsDurationCodecAndVoice() = runTest {
        val r = registered()
        val dialledAt = testScheduler.currentTime
        assertNull(r.session.phone.dial("606-555-0100"))
        advanceTimeBy(5_000) // 400 ms to ringing, answered 3 s later
        assertEquals(CallPhase.CONNECTED, r.session.phone.state.value.call?.phase)
        advanceTimeBy(3_000)
        r.session.phone.hangup()
        advanceTimeBy(2_000)
        val e = r.log.entries.value.single()
        assertEquals(CallKind.OUTGOING, e.kind)
        assertEquals(SimulatedTerminal.PEER, e.number)
        assertTrue("started ${e.startedAt}", e.startedAt - dialledAt in 0L..100L) // when the terminal accepted DIAL
        assertEquals(EndCause.NORMAL.code, e.causeCode)
        assertEquals(CodecId.CODEC2_1200, e.codec)
        assertTrue("duration ${e.durationMillis}", e.durationMillis!! in 3_000L..5_000L)
        assertTrue("sent ${e.voice?.sent}", e.voice!!.sent >= 23)
        assertTrue(e.seen)
        r.session.disconnect()
    }

    @Test
    fun aBusyCallIsLoggedWithItsCause() = runTest {
        val r = registered()
        r.session.phone.dial(SimulatedTerminal.DEMO_NUMBER) // your own number is busy
        advanceTimeBy(2_000)
        val e = r.log.entries.value.single()
        assertEquals(CallKind.OUTGOING, e.kind)
        assertEquals(EndCause.BUSY.code, e.causeCode)
        assertNull(e.durationMillis)
        assertNull(e.voice)
        r.session.disconnect()
    }

    @Test
    fun aRejectedCallIsRejectedAndAnUnansweredOneIsMissed() = runTest {
        val r = registered()
        r.sim.incomingCall(SimulatedTerminal.PEER)
        advanceTimeBy(1_000)
        r.session.phone.reject()
        advanceTimeBy(1_000)
        r.sim.incomingCall(SimulatedTerminal.PEER)
        advanceTimeBy(1_000)
        r.sim.peerHangup()
        advanceTimeBy(1_000)
        assertEquals(listOf(CallKind.MISSED, CallKind.REJECTED), r.log.entries.value.map { it.kind })
        assertEquals(1, r.log.unseenMissed.value)
        r.session.disconnect()
    }

    /** A link drop mid-call ends one voice run and the reconnect starts another: the log counts both. */
    @Test
    fun voiceCountersAddUpAcrossALinkDrop() = runTest {
        val r = registered()
        r.session.phone.dial(SimulatedTerminal.PEER)
        advanceTimeBy(5_000)
        advanceTimeBy(3_000)
        r.sim.dropLink()
        advanceTimeBy(6_000) // reconnects (1 s backoff), resyncs: still IN_CALL, voice runs again
        assertEquals(CallPhase.CONNECTED, r.session.phone.state.value.call?.phase)
        advanceTimeBy(3_000)
        r.session.phone.hangup()
        advanceTimeBy(2_000)
        val e = r.log.entries.value.single()
        assertTrue("sent ${e.voice?.sent}", e.voice!!.sent >= 46)
        r.session.disconnect()
    }
}
