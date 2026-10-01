package org.opencell.core.voice

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeAudio
import org.opencell.core.fakes.FakeCodecs
import org.opencell.core.link.LinkTarget
import org.opencell.core.phone.CallPhase
import org.opencell.core.protocol.EndCause
import org.opencell.core.session.ConsoleKind
import org.opencell.core.session.TerminalSession
import org.opencell.core.sim.SimulatedTerminal

/** A call to the demo terminal's echo peer: what the microphone hears comes back out of the speaker. */
class VoiceEndToEndTest {
    @Test
    fun theEchoPeerPlaysTheMicrophoneBack() = runTest {
        val sim = SimulatedTerminal(backgroundScope, activatedNumber = SimulatedTerminal.DEMO_NUMBER)
        val audio = FakeAudio()
        val session = TerminalSession(
            sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime },
            codecs = FakeCodecs(), audio = audio,
        )
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(5_000)
        assertEquals(null, session.phone.dial(SimulatedTerminal.PEER))
        advanceTimeBy(5_000)
        assertEquals(CallPhase.CONNECTED, session.phone.state.value.call?.phase)
        val consoleBefore = session.console.entries.value.count { it.kind == ConsoleKind.DOWN }
        advanceTimeBy(3_000)

        val on = session.voice.state.value as VoiceState.On
        assertTrue("sent ${on.stats.sent}", on.stats.sent >= 23) // 3 s of 120 ms blocks, less the first
        assertEquals(0, on.stats.dropped)
        val heard = audio.speakers.last().firstSamples.filter { it != 0 } // the first output was the ringback's
        // The microphone's blocks 1000, 1001, … come back in order, none lost and none repeated.
        assertTrue("heard ${heard.size}", heard.size >= 18)
        assertEquals((1000 until 1000 + heard.size).toList(), heard)
        assertEquals(consoleBefore, session.console.entries.value.count { it.kind == ConsoleKind.DOWN })

        session.phone.hangup()
        advanceTimeBy(2_000)
        assertEquals(VoiceState.Off, session.voice.state.value)
        session.disconnect()
    }

    @Test
    fun ringbackThenVoiceOnTheSameOutput() = runTest {
        val sim = SimulatedTerminal(backgroundScope, activatedNumber = SimulatedTerminal.DEMO_NUMBER)
        val audio = FakeAudio()
        val session = TerminalSession(
            sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime },
            codecs = FakeCodecs(), audio = audio,
        )
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(5_000)
        session.phone.dial(SimulatedTerminal.PEER)
        advanceTimeBy(1_000)
        assertEquals(CallPhase.RINGING, session.phone.state.value.call?.phase)
        assertEquals(CallTone.RINGBACK, session.tones.playing.value)
        advanceTimeBy(4_000) // the demo peer answers after 3 s of ringing
        assertEquals(CallPhase.CONNECTED, session.phone.state.value.call?.phase)
        assertEquals(null, session.tones.playing.value)
        assertEquals(2, audio.speakers.size) // the ringback's output, closed, then voice's
        assertTrue(audio.speakers[0].closed)
        assertTrue(session.voice.state.value is VoiceState.On)

        session.phone.hangup()
        advanceTimeBy(2_000)
        session.phone.dismissCall()
        session.phone.dial(SimulatedTerminal.UNREACHABLE)
        advanceTimeBy(3_000)
        assertEquals(EndCause.UNREACHABLE, session.phone.state.value.call?.cause)
        assertEquals(8, audio.speakers.last().played.size) // the SIT, once
        assertEquals(null, session.tones.playing.value)
        session.disconnect()
    }
}
