package org.opencell.core.phone

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.link.LinkTarget
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.SigState
import org.opencell.core.session.ConsoleKind
import org.opencell.core.session.TerminalSession
import org.opencell.core.sim.SimulatedTerminal
import kotlin.time.Duration.Companion.hours

/**
 * The spec §1 "done" flows on virtual time, end to end:
 * PhoneSession -> LinkManager -> SimulatedTerminal (terminal and network).
 */
class CallFlowTest {
    private val me = SimulatedTerminal.DEMO_NUMBER
    private val peer = SimulatedTerminal.PEER

    private class Bench(val sim: SimulatedTerminal, val session: TerminalSession) {
        val phone get() = session.phone
        val state get() = session.phone.state.value
    }

    private fun TestScope.bench(activated: Boolean): Bench {
        val sim = SimulatedTerminal(backgroundScope, activatedNumber = if (activated) me else null)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        return Bench(sim, session)
    }

    private suspend fun TestScope.connect(b: Bench) {
        b.session.connect(LinkTarget(SimulatedTerminal.ADDRESS, b.sim.name))
        advanceTimeBy(4_000) // BLE connect, attach walk, registration
    }

    private fun qr(text: String) = (ActivationQr.parse(text) as QrParse.Ok).qr

    @Test
    fun codeActivatesThenRegistersAndTheSameCodeFailsAsUsed() = runTest {
        val b = bench(activated = false)
        connect(b)
        assertEquals(SigState.NOT_ACTIVATED, b.state.sig)
        assertTrue(b.state.linkUp)

        val code = qr(b.sim.demoQrText())
        b.phone.activate(code)
        advanceTimeBy(500)
        assertEquals(Activation.InProgress(me), b.state.activation)
        assertEquals(SigState.ACTIVATING, b.state.sig)
        advanceTimeBy(3_000)
        assertEquals(Activation.Succeeded(me), b.state.activation)
        assertEquals(SigState.REGISTERED, b.state.sig)
        assertEquals(me, b.state.number)
        assertEquals(RegMode.PART15, b.state.mode)

        b.phone.clearActivation()
        b.phone.activate(code)
        advanceTimeBy(4_000)
        assertEquals(Activation.Failed(2), b.state.activation)
        assertEquals(SigState.REGISTERED, b.state.sig) // still activated: it registers again
        assertEquals(me, b.state.number)
    }

    @Test
    fun expiredCodeFails() = runTest {
        val b = bench(activated = false)
        connect(b)
        b.phone.activate(qr(b.sim.demoQrText(validFor = (-1).hours)))
        advanceTimeBy(3_000)
        assertEquals(Activation.Failed(3), b.state.activation)
        assertEquals(SigState.NOT_ACTIVATED, b.state.sig)
    }

    @Test
    fun outgoingCallIsAnsweredCarriesDataAndIsHungUp() = runTest {
        val b = bench(activated = true)
        connect(b)
        assertEquals(SigState.REGISTERED, b.state.sig)
        assertNull(b.phone.dial("+883 606 555 0100"))
        advanceTimeBy(1_000)
        assertEquals(CallPhase.RINGING, b.state.call?.phase)
        assertEquals(peer, b.state.call?.peer)
        advanceTimeBy(3_000)
        assertEquals(CallPhase.CONNECTED, b.state.call?.phase)
        assertEquals(1L, b.state.call?.id)

        b.phone.sendTestFrames(count = 5)
        advanceTimeBy(3_000)
        assertEquals(5, b.phone.callData.value.sent)
        assertEquals(5, b.phone.callData.value.testFramesReceived) // the peer echoes

        b.phone.hangup()
        advanceTimeBy(1_000)
        assertEquals(CallPhase.ENDED, b.state.call?.phase)
        assertEquals(EndCause.NORMAL, b.state.call?.cause)
        assertEquals(SigState.REGISTERED, b.state.sig)
    }

    @Test
    fun ownNumberIsBusyAndUnreachableNumbersEnd() = runTest {
        val b = bench(activated = true)
        connect(b)
        b.phone.dial(me)
        advanceTimeBy(1_000)
        assertEquals(EndCause.BUSY, b.state.call?.cause)
        b.phone.dismissCall()
        b.phone.dial(SimulatedTerminal.UNREACHABLE)
        advanceTimeBy(1_000)
        assertEquals(EndCause.UNREACHABLE, b.state.call?.cause)
    }

    @Test
    fun incomingCallAnsweredThenFarEndHangsUp() = runTest {
        val b = bench(activated = true)
        connect(b)
        assertTrue(b.sim.incomingCall(peer))
        advanceTimeBy(100)
        assertEquals(Call(1, Direction.INCOMING, peer, CallPhase.INCOMING), b.state.call)
        b.phone.answer()
        advanceTimeBy(1_000)
        assertEquals(CallPhase.CONNECTED, b.state.call?.phase)
        assertTrue(b.sim.peerHangup())
        advanceTimeBy(100)
        assertEquals(CallPhase.ENDED, b.state.call?.phase)
        assertEquals(EndCause.NORMAL, b.state.call?.cause)
    }

    @Test
    fun incomingCallRejected() = runTest {
        val b = bench(activated = true)
        connect(b)
        b.sim.incomingCall(peer)
        advanceTimeBy(100)
        b.phone.reject()
        advanceTimeBy(1_000)
        assertEquals(EndCause.REJECTED, b.state.call?.cause)
    }

    @Test
    fun unansweredIncomingCallTimesOut() = runTest {
        val b = bench(activated = true)
        connect(b)
        b.sim.incomingCall(peer)
        advanceTimeBy(61_000)
        assertEquals(EndCause.NO_ANSWER, b.state.call?.cause)
    }

    /** Events are dropped while disconnected: the app finds the ringing call through STATUS and can still answer it. */
    @Test
    fun callThatStartedWhileDisconnectedIsFoundOnReconnect() = runTest {
        val b = bench(activated = true)
        connect(b)
        b.sim.dropLink()
        advanceTimeBy(10)
        assertFalse(b.state.linkUp)
        assertTrue(b.sim.incomingCall(peer)) // INCOMING is dropped: no phone connected
        advanceTimeBy(3_000) // reconnect backoff 1 s + connect 0.4 s
        assertTrue(b.state.linkUp)
        assertEquals(Call(null, Direction.INCOMING, null, CallPhase.INCOMING), b.state.call)
        b.phone.answer()
        advanceTimeBy(1_000)
        assertEquals(CallPhase.CONNECTED, b.state.call?.phase)
        assertEquals(1L, b.state.call?.id)
    }

    @Test
    fun commandsInTheWrongStateAreRefusedWithAReason() = runTest {
        val b = bench(activated = false)
        connect(b)
        b.phone.dial(peer)
        advanceTimeBy(500)
        assertEquals("The terminal can't place a call now (it must be registered and not in a call)", b.state.notice)
        assertNull(b.state.call)
        b.phone.answer()
        advanceTimeBy(500)
        assertEquals("There is no incoming call to answer", b.state.notice)
        assertTrue(b.session.console.entries.value.any { it.kind == ConsoleKind.ERROR && it.text == "COMMAND ANSWER: not now (0x80)" })
    }

    @Test
    fun deactivateIsRefusedInACallAndWipesOtherwise() = runTest {
        val b = bench(activated = true)
        connect(b)
        b.sim.incomingCall(peer)
        advanceTimeBy(100)
        b.phone.deactivate()
        advanceTimeBy(500)
        assertEquals("The terminal can't deactivate during a call", b.state.notice)
        b.phone.reject()
        advanceTimeBy(1_000)
        b.phone.deactivate()
        advanceTimeBy(500)
        assertEquals(SigState.NOT_ACTIVATED, b.state.sig)
        assertNull(b.state.number)
        assertNull(b.state.call)
        b.session.disconnect()
        advanceUntilIdle()
    }
}
