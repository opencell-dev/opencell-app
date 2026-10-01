package org.opencell.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalEvent

/** Which calls the log hears about, and what it learns of each (dial-and-recents spec §4.1). */
class CallTrackerTest {
    private val me = "+883160655501234"
    private val peer = "+883160655500100"
    private val registered = PhoneState(linkUp = true, sig = SigState.REGISTERED, number = me, mode = RegMode.PART15)

    private fun ev(e: TerminalEvent) = PhoneInput.Event(e)

    /** Runs [inputs] through the reducer and one tracker; the n-th input happens at [t0] + n * [step]. */
    private fun track(vararg inputs: PhoneInput, start: PhoneState = registered, t0: Long = 10_000, step: Long = 100): List<FinishedCall> {
        val tracker = CallTracker()
        var s = start
        val out = mutableListOf<FinishedCall>()
        inputs.forEachIndexed { i, input ->
            val t = t0 + i * step
            val next = PhoneReducer.reduce(s, input, t)
            tracker.step(s, input, next, t)?.let(out::add)
            s = next
        }
        return out
    }

    @Test
    fun anOutgoingCallThatConnectsIsReportedOnceWhenItEnds() {
        val calls = track(
            PhoneInput.Dialled(peer), // 10_000
            ev(TerminalEvent.Ringing(4)),
            ev(TerminalEvent.Connected(4, 1)), // 10_200
            PhoneInput.Releasing,
            ev(TerminalEvent.Ended(4, EndCause.NORMAL.code)), // 10_400
            PhoneInput.DismissCall,
        )
        assertEquals(
            listOf(FinishedCall(Direction.OUTGOING, peer, me, 10_000, 10_200, 10_400, EndCause.NORMAL.code, 1, rejected = false)),
            calls,
        )
    }

    @Test
    fun anOutgoingCallThatIsBusyNeverConnected() {
        val c = track(PhoneInput.Dialled(peer), ev(TerminalEvent.Ended(0, EndCause.BUSY.code))).single()
        assertNull(c.connectedAt)
        assertEquals(EndCause.BUSY.code, c.causeCode)
        assertEquals(Direction.OUTGOING, c.direction)
    }

    @Test
    fun anIncomingCallThatIsAnsweredHasItsConnectedTime() {
        val c = track(
            ev(TerminalEvent.Incoming(9, peer)),
            PhoneInput.Answering,
            ev(TerminalEvent.Connected(9, 1)),
            ev(TerminalEvent.Ended(9, EndCause.NORMAL.code)),
        ).single()
        assertEquals(Direction.INCOMING, c.direction)
        assertEquals(10_000L, c.startedAt)
        assertEquals(10_200L, c.connectedAt)
        assertFalse(c.rejected)
    }

    @Test
    fun rejectingOnThisPhoneIsRemembered() {
        val c = track(ev(TerminalEvent.Incoming(9, peer)), PhoneInput.Releasing, ev(TerminalEvent.Ended(9, EndCause.REJECTED.code))).single()
        assertTrue(c.rejected)
        assertNull(c.connectedAt)
    }

    @Test
    fun theCallerGivingUpIsNotARejection() {
        val c = track(ev(TerminalEvent.Incoming(9, peer)), ev(TerminalEvent.Ended(9, EndCause.NORMAL.code))).single()
        assertFalse(c.rejected)
        assertNull(c.connectedAt)
    }

    /** D2 of the polish draft, kept: answered, but the caller hung up before CONNECTED: missed, not rejected. */
    @Test
    fun answeredButEndedBeforeConnectingIsNotARejection() {
        val c = track(ev(TerminalEvent.Incoming(9, peer)), PhoneInput.Answering, ev(TerminalEvent.Ended(9, EndCause.NORMAL.code))).single()
        assertFalse(c.rejected)
        assertNull(c.connectedAt)
    }

    @Test
    fun hangingUpAConnectedIncomingCallIsNotARejection() {
        val c = track(
            ev(TerminalEvent.Incoming(9, peer)),
            ev(TerminalEvent.Connected(9, 1)),
            PhoneInput.Releasing,
            ev(TerminalEvent.Ended(9, EndCause.NORMAL.code)),
        ).single()
        assertFalse(c.rejected)
    }

    @Test
    fun disconnectingDuringARingReportsItWithNoCause() {
        val c = track(ev(TerminalEvent.Incoming(9, peer)), PhoneInput.LinkClosed).single()
        assertNull(c.causeCode)
        assertEquals(peer, c.peer)
    }

    /** Close while the link is down reports the call; if it's still ringing on reconnect, that's a new call. */
    @Test
    fun closingWhileTheLinkIsDownReportsTheCallAndAReturningCallIsANewOne() {
        val calls = track(
            ev(TerminalEvent.Incoming(9, peer)), // 10_000
            PhoneInput.LinkDown,
            PhoneInput.DismissCall, // 10_200: reported, no cause
            PhoneInput.Resync(SigState.RINGING_IN), // 10_300: back, caller unknown
            ev(TerminalEvent.Ended(0, EndCause.NO_ANSWER.code)), // 10_400
        )
        assertEquals(2, calls.size)
        assertEquals(peer, calls[0].peer)
        assertNull(calls[0].causeCode)
        assertEquals(10_200L, calls[0].endedAt)
        assertNull(calls[1].peer)
        assertEquals(10_300L, calls[1].startedAt)
        assertEquals(EndCause.NO_ANSWER.code, calls[1].causeCode)
    }

    /** A resync that forgets the ringing call's caller is still the same ring: one report, from its first start. */
    @Test
    fun aResyncOfTheSameRingKeepsItsStart() {
        val calls = track(
            ev(TerminalEvent.Incoming(9, peer)),
            PhoneInput.LinkDown,
            PhoneInput.Resync(SigState.RINGING_IN),
            ev(TerminalEvent.Ended(9, EndCause.NORMAL.code)),
        )
        val c = calls.single()
        assertEquals(10_000L, c.startedAt)
        assertNull(c.peer) // the resync dropped it
    }

    /** STATUS showing a different call replaces the old one: the old one is reported (no cause), the new one tracked. */
    @Test
    fun aCallReplacedByStatusIsReported() {
        val calls = track(
            PhoneInput.Dialled(peer), // 10_000
            PhoneInput.Status(SigState.RINGING_IN), // 12_500: past the 2 s grace, CALLING can't become INCOMING
            ev(TerminalEvent.Ended(0, EndCause.NORMAL.code)), // 15_000
            step = 2_500,
        )
        assertEquals(2, calls.size)
        assertEquals(Direction.OUTGOING, calls[0].direction)
        assertNull(calls[0].causeCode)
        assertEquals(Direction.INCOMING, calls[1].direction)
        assertEquals(12_500L, calls[1].startedAt)
    }

    @Test
    fun closingAnEndedCallAddsNothing() {
        val calls = track(
            PhoneInput.Dialled(peer),
            ev(TerminalEvent.Ended(0, EndCause.BUSY.code)),
            PhoneInput.DismissCall,
            PhoneInput.DismissCall,
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun anEndedEventForACallTheAppNeverSawAddsNothing() {
        assertTrue(track(ev(TerminalEvent.Ended(7, EndCause.NORMAL.code))).isEmpty())
    }

    @Test
    fun rejectingOneCallDoesNotMarkTheNext() {
        val calls = track(
            ev(TerminalEvent.Incoming(9, peer)),
            PhoneInput.Releasing,
            ev(TerminalEvent.Ended(9, EndCause.REJECTED.code)),
            ev(TerminalEvent.Incoming(10, peer)),
            ev(TerminalEvent.Ended(10, EndCause.NORMAL.code)),
        )
        assertEquals(listOf(true, false), calls.map { it.rejected })
    }
}
