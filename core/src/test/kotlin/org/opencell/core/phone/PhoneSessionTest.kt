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
        val phone = PhoneSession(
            link, UplinkSender(link), backgroundScope, memory, { k, t -> log += "$k $t" },
            clock = { testScheduler.currentTime }, monotonic = { testScheduler.currentTime },
        )
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
        // Only the fresh read (not the ambient STATUS notification, which never
        // changes from harness()'s REGISTERED) reflects this: proves the refusal
        // actually triggered a re-read rather than just showing the notice.
        h.link.refreshOverride = status(SigState.RINGING_OUT)
        h.phone.answer()
        runCurrent()
        assertEquals("There is no incoming call to answer", h.phone.state.value.notice)
        // No local call changed recently, so no grace holds it back: the terminal's outgoing call shows up.
        // (This used to assert no call, which only held because the test clock starts at 0 = the old callChangedAt.)
        assertEquals(Call(null, Direction.OUTGOING, null, CallPhase.RINGING), h.phone.state.value.call)
        assertEquals(SigState.RINGING_OUT, h.phone.state.value.sig)
        assertTrue(h.log.contains("ERROR COMMAND ANSWER: not now (0x80)"))
        h.phone.clearNotice()
        assertNull(h.phone.state.value.notice)
    }

    @Test
    fun aRefusedAnswerReReadsStatusInsteadOfResyncingSoTheIncomingCallSurvives() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(7, peer))
        runCurrent()
        h.link.commandResults += WriteResult.NotNow
        // The fresh read still shows RINGING_IN: a plain re-read must not erase
        // the call's id/caller the way a full Resync would.
        h.link.refreshOverride = status(SigState.RINGING_IN)
        h.phone.answer()
        runCurrent()
        val call = h.phone.state.value.call
        assertEquals(7L, call?.id)
        assertEquals(peer, call?.peer)
        assertEquals(CallPhase.INCOMING, call?.phase)
    }

    @Test
    fun duplicateInFlightCommandIsIgnored() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(7, peer))
        runCurrent()
        h.phone.answer()
        h.phone.answer() // a second ANSWER while the first hasn't been written yet
        runCurrent()
        assertEquals(1, h.link.commands.size)
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

    /** C1: the user's (or the system's) final close ends a call the phone can no longer follow. */
    @Test
    fun disconnectingDuringAConnectedCallEndsIt() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        assertEquals(CallPhase.CONNECTED, h.phone.state.value.call?.phase)
        h.link.stateFlow.value = LinkState.Disconnected
        runCurrent()
        val s = h.phone.state.value
        assertFalse(s.linkUp)
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertNull(s.call?.causeCode) // unknown: the phone lost the terminal
    }

    /** C1: a drop the link manager was retrying, then the user gives up (Disconnect), also ends the call. */
    @Test
    fun disconnectingWhileWaitingToReconnectEndsTheCall() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        runCurrent()
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        assertEquals(CallPhase.INCOMING, h.phone.state.value.call?.phase) // a transient drop keeps the call
        h.link.stateFlow.value = LinkState.Disconnected
        runCurrent()
        assertEquals(CallPhase.ENDED, h.phone.state.value.call?.phase)
    }

    @Test
    fun aStatusHeldBackByTheGraceEndsTheCallOnceItElapsesWithNoFurtherInput() = runTest {
        val h = harness()
        assertNull(h.phone.dial("+883 606 555 0100"))
        runCurrent()
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase)
        // A STATUS(REGISTERED) arrives inside the grace: it disagrees with the
        // just-dialled call, so it's held back rather than reconciled right away.
        // A fresh read when the grace ends still says REGISTERED (the call really is gone).
        h.link.notifyStatus(status(SigState.REGISTERED).copy(frame = 1001))
        runCurrent()
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase)
        // Advancing time past the grace, with no further EVENT or STATUS, still ends the call.
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        runCurrent()
        assertEquals(CallPhase.ENDED, h.phone.state.value.call?.phase)
    }

    /**
     * I2: the firmware notifies no STATUS on DIAL, so a STATUS sent just before the terminal
     * took DIAL (and handled after it) may be the last one for a while. When the grace ends the
     * session reads STATUS afresh instead of trusting the stored one.
     */
    @Test
    fun aStaleStatusAfterDialIsCheckedWithAFreshReadBeforeTheCallIsEnded() = runTest {
        val h = harness()
        assertNull(h.phone.dial("+883 606 555 0100"))
        runCurrent()
        h.link.notifyStatus(status(SigState.REGISTERED).copy(frame = 1001)) // stale: read before DIAL was taken
        h.link.refreshOverride = status(SigState.CALLING) // what the terminal really says now
        runCurrent()
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        runCurrent()
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase)
        assertEquals(SigState.CALLING, h.phone.state.value.sig)
        advanceTimeBy(10_000)
        assertEquals(CallPhase.CALLING, h.phone.state.value.call?.phase) // no further tick ends it
    }

    /** I2: if that fresh read fails, the held STATUS is reconciled as before. */
    @Test
    fun whenTheFreshReadFailsTheHeldStatusStillEndsTheCall() = runTest {
        val h = harness()
        assertNull(h.phone.dial("+883 606 555 0100"))
        runCurrent()
        h.link.notifyStatus(status(SigState.REGISTERED).copy(frame = 1001))
        h.link.refreshFails = true
        runCurrent()
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        runCurrent()
        assertEquals(CallPhase.ENDED, h.phone.state.value.call?.phase)
    }

    /** I2: the grace runs on a monotonic clock; the wall clock (stepped back here) only times the call display. */
    @Test
    fun theGraceIgnoresWallClockSteps() = runTest {
        var wallOffset = 0L
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.statusFlow.value = status(SigState.REGISTERED)
        val phone = PhoneSession(
            link, UplinkSender(link), backgroundScope,
            clock = { 1_700_000_000_000L + testScheduler.currentTime + wallOffset },
            monotonic = { testScheduler.currentTime },
        )
        runCurrent()
        assertNull(phone.dial("+883 606 555 0100"))
        runCurrent()
        wallOffset = -3_600_000L // NTP steps the wall clock back an hour
        link.notifyStatus(status(SigState.REGISTERED).copy(frame = 1001))
        link.refreshFails = true
        runCurrent()
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        runCurrent()
        assertEquals(CallPhase.ENDED, phone.state.value.call?.phase)
    }

    /** M6: EVENTs and STATUS notifications are applied in the order they arrived. */
    @Test
    fun eventsAndStatusNotificationsAreAppliedInArrivalOrder() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        runCurrent()
        advanceTimeBy(PhoneReducer.RECONCILE_GRACE_MS + 1)
        // CONNECTED, the STATUS that follows it (IN_CALL), then ENDED, all in one burst.
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        h.link.notifyStatus(status(SigState.IN_CALL))
        h.link.emitEvent(TerminalEvent.Ended(3, 0))
        runCurrent()
        val s = h.phone.state.value
        assertEquals(CallPhase.ENDED, s.call?.phase)
        // Applied in order, IN_CALL came before ENDED: nothing disagrees and nothing is held.
        assertEquals(SigState.REGISTERED, s.sig)
        assertNull(PhoneReducer.reconcileDueAt(s))
    }

    /** The failed-refresh path on reconnect goes through the reducer, so a held STATUS is still reconciled. */
    @Test
    fun aFailedReadOnReconnectStillLetsAHeldStatusBeReconciled() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        runCurrent()
        h.link.notifyStatus(status(SigState.REGISTERED)) // held: inside the grace
        runCurrent()
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        h.link.refreshFails = true
        h.link.stateFlow.value = LinkState.Connected(LinkTarget(address, null), 247)
        runCurrent()
        assertTrue(h.phone.state.value.linkUp)
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

    @Test
    fun endingTheCallStopsTestFramesImmediatelyWithNoMoreUpWrites() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        // The first frame is refused (0x80): retrying it (as UplinkSender would)
        // means a write is still pending, backed off, when the call ends.
        h.link.results += WriteResult.NotNow
        h.phone.sendTestFrames(count = 5, interval = 200.milliseconds)
        runCurrent()
        assertEquals(1, h.link.writes.size) // the one (refused) attempt at frame 0
        h.link.emitEvent(TerminalEvent.Ended(3, 0))
        runCurrent()
        advanceTimeBy(5_000) // well past any retry backoff and the per-frame interval
        assertEquals(1, h.link.writes.size) // no retried or further frame goes out after ENDED
    }

    @Test
    fun aRefusedTestFrameIsDroppedNotRetried() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        h.link.results += WriteResult.NotNow
        h.phone.sendTestFrames(count = 3, interval = 10.milliseconds)
        advanceTimeBy(1_000)
        assertEquals(3, h.link.writes.size) // one write attempt per frame, no retry for the refused one
        assertEquals(CallData(sent = 2, failed = 1), h.phone.callData.value)
    }

    @Test
    fun linkGoingDownCancelsAnOutstandingTestFrameSend() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        h.phone.sendTestFrames(count = 5, interval = 200.milliseconds)
        runCurrent()
        assertEquals(1, h.link.writes.size)
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(1, h.link.writes.size)
    }

    @Test
    fun aSecondSendTestFramesWhileOneIsRunningIsIgnored() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        val first = h.phone.sendTestFrames(count = 5, interval = 200.milliseconds)
        runCurrent()
        val second = h.phone.sendTestFrames(count = 5, interval = 200.milliseconds)
        assertNull(second)
        assertEquals("Test frames are already going out", h.phone.state.value.notice)
        assertTrue(first!!.isActive)
    }

    @Test
    fun closeCancelsAnOutstandingTestFrameSend() = runTest {
        val h = harness()
        h.link.emitEvent(TerminalEvent.Incoming(3, peer))
        h.link.emitEvent(TerminalEvent.Connected(3, 1))
        runCurrent()
        h.phone.sendTestFrames(count = 5, interval = 200.milliseconds)
        runCurrent()
        assertEquals(1, h.link.writes.size)
        h.phone.close()
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(1, h.link.writes.size)
    }

    @Test
    fun connectingToADifferentTerminalResetsPhoneState() = runTest {
        val h = harness(SigState.REGISTERED)
        h.link.emitEvent(TerminalEvent.Registered(me, 1))
        runCurrent()
        assertEquals(me, h.phone.state.value.number)
        h.link.stateFlow.value = LinkState.WaitingToReconnect(LinkTarget(address, null), 1, 1000.milliseconds, "supervision timeout")
        runCurrent()
        assertFalse(h.phone.state.value.linkUp)
        // Reconnects, but to a different terminal (a different BLE address): terminal A's
        // number must not show up as if it belonged to B.
        h.link.stateFlow.value = LinkState.Connected(LinkTarget("CC:DD", null), 247)
        runCurrent()
        val s = h.phone.state.value
        assertTrue(s.linkUp)
        assertEquals(SigState.REGISTERED, s.sig)
        assertNull(s.number)
    }

    private companion object {
        const val GOLDEN = "opencell:1:AQEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyCgoaKjpKWmp7CxsrO0tba3uLm6u7y9vr-INgZVUSNPeFY0EoZ3"
    }
}
