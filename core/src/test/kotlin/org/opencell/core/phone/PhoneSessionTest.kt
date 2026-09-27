package org.opencell.core.phone

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeLink
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.UplinkSender
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalEvent
import org.opencell.core.protocol.TerminalStatus
import org.opencell.core.session.ConsoleKind
import kotlin.time.Duration.Companion.milliseconds

class PhoneSessionTest {
    private val me = "+8836065551234"
    private val peer = "+8836065550100"
    private val address = "AA:BB"

    private fun status(sig: SigState) = TerminalStatus(4, 0, 2, -52, 50, 0x76AD0488, 1000, 7, sig.code)

    private class Harness(val link: FakeLink, val phone: PhoneSession, val memory: PhoneMemory, val log: MutableList<String>)

    private fun TestScope.harness(sig: SigState = SigState.REGISTERED, memory: PhoneMemory = PhoneMemory.inMemory()): Harness {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.statusFlow.value = status(sig)
        val log = mutableListOf<String>()
        val phone = PhoneSession(link, UplinkSender(link), backgroundScope, memory, { k, t -> log += "$k $t" }, { testScheduler.currentTime })
        runCurrent()
        return Harness(link, phone, memory, log)
    }

    private fun hex(b: ByteArray) = Hex.format(b)

    @Test
    fun resyncsFromStatusWhenTheLinkIsUpAndUsesTheRememberedNumber() = runTest {
        val memory = PhoneMemory.inMemory().apply { save(address, Remembered(me, RegMode.PART15)) }
        val h = harness(SigState.REGISTERED, memory)
        val s = h.phone.state.value
        assertTrue(s.linkUp)
        assertEquals(SigState.REGISTERED, s.sig)
        assertEquals(me, s.number)
        assertTrue(s.canDial)
    }

    @Test
    fun dialValidatesThenSendsTheCanonicalNumber() = runTest {
        val h = harness()
        assertEquals(PhoneSession.BAD_NUMBER, h.phone.dial("+49 176 1234"))
        runCurrent()
        assertTrue(h.link.commands.isEmpty())
        assertNull(h.phone.dial("+883 606 555 0100"))
        runCurrent()
        assertEquals("02 2b 38 38 33 36 30 36 35 35 35 30 31 30 30", hex(h.link.commands.single()))
        assertEquals(Call(null, Direction.OUTGOING, peer, CallPhase.CALLING), h.phone.state.value.call)
        assertTrue(h.log.contains("INFO COMMAND DIAL $peer: accepted"))
    }

    @Test
    fun eventsDriveACallAndAreLogged() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(7, peer))
        runCurrent()
        assertEquals(CallPhase.INCOMING, h.phone.state.value.call?.phase)
        h.phone.answer()
        runCurrent()
        assertEquals("03", hex(h.link.commands.single()))
        assertTrue(h.phone.state.value.call!!.answering)
        h.link.emitEvent(TerminalEvent.Connected(7, 1))
        runCurrent()
        assertEquals(CallPhase.CONNECTED, h.phone.state.value.call?.phase)
        h.phone.hangup()
        runCurrent()
        assertEquals(CallPhase.RELEASING, h.phone.state.value.call?.phase)
        h.link.emitEvent(TerminalEvent.Ended(7, 0))
        runCurrent()
        assertEquals(CallPhase.ENDED, h.phone.state.value.call?.phase)
        assertTrue(h.log.contains("INFO EVENT incoming call 7 from +883 606 555 0100"))
        h.phone.dismissCall()
        assertNull(h.phone.state.value.call)
    }

    @Test
    fun refusedCommandShowsWhyAndRereadsStatus() = runTest {
        val h = harness()
        h.link.commandResults += WriteResult.NotNow
        h.link.statusFlow.value = status(SigState.REGISTERED)
        h.phone.answer()
        runCurrent()
        assertEquals("There is no incoming call to answer", h.phone.state.value.notice)
        assertNull(h.phone.state.value.call)
        assertTrue(h.log.contains("ERROR COMMAND ANSWER: not now (0x80)"))
        h.phone.clearNotice()
        assertNull(h.phone.state.value.notice)
    }

    @Test
    fun activationCodeRefusedByTheTerminal() = runTest {
        val h = harness(SigState.NOT_ACTIVATED)
        val qr = (ActivationQr.parse(GOLDEN) as QrParse.Ok).qr
        h.link.commandResults += WriteResult.BadArgument
        h.phone.activate(qr)
        runCurrent()
        assertEquals(108, h.link.commands.single().size)
        assertEquals("The terminal refused this activation code (damaged, or an invalid network key)", h.phone.state.value.notice)
        assertEquals(Activation.Idle, h.phone.state.value.activation)
    }

    @Test
    fun activationAcceptedThenEventsRememberTheNumber() = runTest {
        val h = harness(SigState.NOT_ACTIVATED)
        val qr = (ActivationQr.parse(GOLDEN) as QrParse.Ok).qr
        h.phone.activate(qr)
        runCurrent()
        assertEquals(Activation.InProgress(me), h.phone.state.value.activation)
        h.link.emitEvent(TerminalEvent.Activated(me))
        h.link.emitEvent(TerminalEvent.Registered(me, 1))
        runCurrent()
        assertEquals(Remembered(me, RegMode.PART15), h.memory.load(address))
        h.phone.deactivate()
        runCurrent()
        assertEquals("06 a5", hex(h.link.commands.last()))
        h.link.emitEvent(TerminalEvent.Deactivated)
        runCurrent()
        assertNull(h.memory.load(address))
        assertNull(h.phone.state.value.number)
    }

    @Test
    fun linkDropAndReconnectResyncs() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        runCurrent()
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        assertFalse(h.phone.state.value.linkUp)
        // While disconnected the call was rejected elsewhere and the ENDED event was lost.
        h.link.statusFlow.value = status(SigState.REGISTERED)
        advanceTimeBy(1)
        h.link.stateFlow.value = LinkState.Connected(LinkTarget(address, null), 247)
        runCurrent()
        val s = h.phone.state.value
        assertTrue(s.linkUp)
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertNull(s.call?.causeCode)
    }

    @Test
    fun refreshFailingAfterReconnectDoesNotResyncFromStaleStatus() = runTest {
        // Nothing has changed since the initial REGISTERED status, so an incoming
        // call event is what actually moved the phone's own state to INCOMING;
        // the cached STATUS the link held before disconnecting still says
        // REGISTERED (no call), and must not be used as if it were fresh.
        val h = harness(SigState.REGISTERED)
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        runCurrent()
        assertEquals(CallPhase.INCOMING, h.phone.state.value.call?.phase)
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        assertFalse(h.phone.state.value.linkUp)
        // The fresh STATUS read on reconnect fails; the cached (pre-disconnect) STATUS is still sitting there.
        h.link.refreshFails = true
        h.link.stateFlow.value = LinkState.Connected(LinkTarget(address, null), 247)
        runCurrent()
        val s = h.phone.state.value
        assertTrue(s.linkUp)
        // No Resync happened from the stale pre-disconnect STATUS: the call the
        // app already knew about (from the event) is left exactly as it was.
        assertEquals(CallPhase.INCOMING, s.call?.phase)
        assertNull(s.notice)
    }

    @Test
    fun aStatusHeldBackByTheGraceEndsTheCallOnceItElapsesWithNoFurtherInput() = runTest {
        val h = harness()
        assertNull(h.phone.dial("+883 606 555 0100"))
        runCurrent()
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase)
        // A STATUS(REGISTERED) arrives inside the grace: it disagrees with the
        // just-dialled call, so it's held back rather than reconciled right away.
        h.link.statusFlow.value = status(SigState.REGISTERED).copy(frame = 1001)
        runCurrent()
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase)
        // Advancing time past the grace, with no further EVENT or STATUS, still ends the call.
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        runCurrent()
        assertEquals(CallPhase.ENDED, h.phone.state.value.call?.phase)
    }

    @Test
    fun testFramesGoOutOnlyInAConnectedCallAndEchoesAreCounted() = runTest {
        val h = harness()
        assertNull(h.phone.sendTestFrames())
        assertEquals("Test frames only go out during a connected call", h.phone.state.value.notice)
        assertTrue(h.link.writes.isEmpty())

        h.link.echoDelay = { 300.milliseconds }
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        h.phone.sendTestFrames(count = 3)
        advanceTimeBy(2_000)
        assertEquals(listOf("b0 00 6f 63 2d 73 65 6e 64", "b0 01 6f 63 2d 73 65 6e 64", "b0 02 6f 63 2d 73 65 6e 64"), h.link.writes.map { hex(it) })
        assertEquals(CallData(sent = 3, received = 3, testFramesReceived = 3, lastReceivedHex = "b0 02 6f 63 2d 73 65 6e 64"), h.phone.callData.value)
        assertTrue(h.log.any { it.startsWith("UP UP test frame 0: sent") })
        assertEquals(ConsoleKind.UP.name, h.log.last { it.contains("test frame 2") }.substringBefore(' '))
    }

    private companion object {
        const val GOLDEN = "opencell:1:AQEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyCgoaKjpKWmp7CxsrO0tba3uLm6u7y9vr-INgZVUSNPeFY0EoZ3"
    }
}
