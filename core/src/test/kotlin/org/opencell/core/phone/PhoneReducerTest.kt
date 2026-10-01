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

class PhoneReducerTest {
    private val me = "+883160655501234"
    private val peer = "+883160655500100"
    private val registered = PhoneState(linkUp = true, sig = SigState.REGISTERED, number = me, mode = RegMode.PART15)

    /** Applies inputs in order; the n-th input happens at [t0] + n * [step] ms. */
    private fun run(s: PhoneState, vararg inputs: PhoneInput, t0: Long = 10_000, step: Long = 100): PhoneState =
        inputs.foldIndexed(s) { i, acc, input -> PhoneReducer.reduce(acc, input, t0 + i * step) }

    private fun ev(e: TerminalEvent) = PhoneInput.Event(e)

    @Test
    fun activationThenRegistration() {
        val start = PhoneState(linkUp = true, sig = SigState.NOT_ACTIVATED)
        val s1 = run(start, PhoneInput.Activating(me))
        assertEquals(Activation.InProgress(me), s1.activation)
        val s2 = run(s1, ev(TerminalEvent.Activated(me)))
        assertEquals(Activation.Succeeded(me), s2.activation)
        assertEquals(SigState.REGISTERING, s2.sig)
        val s3 = run(s2, ev(TerminalEvent.Registered(me, 2)))
        assertEquals(SigState.REGISTERED, s3.sig)
        assertEquals(RegMode.PART97, s3.mode)
        assertEquals(me, s3.number)
        assertTrue(s3.canDial)
        assertEquals(Activation.Idle, run(s3, PhoneInput.ClearActivation).activation)
    }

    @Test
    fun failedActivationOfAnActivatedTerminalKeepsItsNumber() {
        val s = run(registered, PhoneInput.Activating("+883160655509999"), ev(TerminalEvent.ActivationFailed(2)))
        assertEquals(Activation.Failed(2), s.activation)
        assertEquals("This code has already been used (token used)", (s.activation as Activation.Failed).text)
        assertEquals(me, s.number)
        assertEquals("Activation failed (reason 77)", Activation.Failed(77).text)
    }

    /** M2: ACTIVATED/REGISTERED with an invalid number ([TerminalEvent] decodes it as null) isn't remembered. */
    @Test
    fun invalidActivatedOrRegisteredNumberIsNotRemembered() {
        val start = PhoneState(linkUp = true, sig = SigState.NOT_ACTIVATED)
        val s1 = run(start, PhoneInput.Activating(me), ev(TerminalEvent.Activated(null)))
        assertNull(s1.number)
        assertEquals(Activation.Succeeded(null), s1.activation)
        assertEquals(SigState.REGISTERING, s1.sig)
        // A terminal that was already registered keeps its known number: the invalid one isn't adopted.
        val s2 = run(registered, ev(TerminalEvent.Registered(null, 1)))
        assertEquals(me, s2.number)
        assertEquals(SigState.REGISTERED, s2.sig)
    }

    /** M2: an INCOMING call whose number isn't valid BCD shows as an unknown caller, not garbled digits. */
    @Test
    fun invalidIncomingNumberIsAnUnknownCaller() {
        val s = run(registered, ev(TerminalEvent.Incoming(9, null)))
        assertEquals(Call(9, Direction.INCOMING, null, CallPhase.INCOMING), s.call)
    }

    @Test
    fun outgoingCallRingsConnectsAndEnds() {
        val calling = run(registered, PhoneInput.Dialled(peer))
        assertEquals(Call(null, Direction.OUTGOING, peer, CallPhase.CALLING), calling.call)
        assertFalse(calling.canDial)
        val ringing = run(calling, ev(TerminalEvent.Ringing(1)))
        assertEquals(CallPhase.RINGING, ringing.call?.phase)
        assertEquals(1L, ringing.call?.id)
        assertEquals(peer, ringing.call?.peer)
        val connected = PhoneReducer.reduce(ringing, ev(TerminalEvent.Connected(1, 1)), 20_000)
        assertEquals(CallPhase.CONNECTED, connected.call?.phase)
        assertEquals(20_000L, connected.call?.connectedAt)
        val releasing = run(connected, PhoneInput.Releasing)
        assertEquals(CallPhase.RELEASING, releasing.call?.phase)
        val ended = run(releasing, ev(TerminalEvent.Ended(1, 0)))
        assertEquals(CallPhase.ENDED, ended.call?.phase)
        assertEquals(EndCause.NORMAL, ended.call?.cause)
        assertEquals(SigState.REGISTERED, ended.sig)
        assertTrue(ended.canDial)
        assertNull(run(ended, PhoneInput.DismissCall).call)
    }

    @Test
    fun connectMaySkipRinging() {
        val s = run(registered, PhoneInput.Dialled(peer), ev(TerminalEvent.Connected(4, 1)))
        assertEquals(Call(4, Direction.OUTGOING, peer, CallPhase.CONNECTED, connectedAt = 10_100, codec = 1), s.call)
    }

    @Test
    fun connectedKeepsTheCodecAndAStatusOnlyCallHasNone() {
        val s = run(registered, ev(TerminalEvent.Incoming(5, peer)), ev(TerminalEvent.Connected(5, 7)))
        assertEquals(7, s.call?.codec)
        val fromStatus = run(registered, PhoneInput.Resync(SigState.IN_CALL))
        assertEquals(CallPhase.CONNECTED, fromStatus.call?.phase)
        assertNull(fromStatus.call?.codec)
    }

    @Test
    fun incomingCallAnsweredThenEndedByTheFarEnd() {
        val ringing = run(registered, ev(TerminalEvent.Incoming(9, peer)))
        assertEquals(Call(9, Direction.INCOMING, peer, CallPhase.INCOMING), ringing.call)
        assertEquals(SigState.RINGING_IN, ringing.sig)
        val answering = run(ringing, PhoneInput.Answering)
        assertTrue(answering.call!!.answering)
        val connected = run(answering, ev(TerminalEvent.Connected(9, 1)))
        assertFalse(connected.call!!.answering)
        assertEquals(CallPhase.CONNECTED, connected.call?.phase)
        val ended = run(connected, ev(TerminalEvent.Ended(9, 0)))
        assertEquals("Call ended", ended.call?.endText)
    }

    @Test
    fun rejectedIncomingCall() {
        val s = run(registered, ev(TerminalEvent.Incoming(3, peer)), PhoneInput.Releasing, ev(TerminalEvent.Ended(3, 1)))
        assertEquals(EndCause.REJECTED, s.call?.cause)
        assertEquals("Call rejected", s.call?.endText)
    }

    @Test
    fun staleStatusRightAfterDialDoesNotEndTheCall() {
        val calling = PhoneReducer.reduce(registered, PhoneInput.Dialled(peer), 10_000)
        // A STATUS sent just before the terminal took DIAL, processed after it.
        val stale = PhoneReducer.reduce(calling, PhoneInput.Status(SigState.REGISTERED), 10_050)
        assertEquals(CallPhase.CALLING, stale.call?.phase)
        assertEquals(SigState.REGISTERED, stale.sig)
        // Much later, STATUS still says no call: the call is gone.
        val later = PhoneReducer.reduce(stale, PhoneInput.Status(SigState.REGISTERED), 10_000 + PhoneReducer.RECONCILE_GRACE_MS)
        assertEquals(CallPhase.ENDED, later.call?.phase)
        assertNull(later.call?.causeCode)
    }

    @Test
    fun statusCatchesUpAMissedConnect() {
        val ringing = PhoneReducer.reduce(registered, ev(TerminalEvent.Incoming(2, peer)), 0)
        val s = PhoneReducer.reduce(ringing, PhoneInput.Status(SigState.IN_CALL), 5_000)
        assertEquals(Call(2, Direction.INCOMING, peer, CallPhase.CONNECTED, connectedAt = 5_000), s.call)
    }

    /** Events are dropped while no phone is connected: an incoming call can show up with no caller and no id. */
    @Test
    fun resyncIntoARingingCallWithoutCallerInfo() {
        val s = run(registered.copy(linkUp = false), PhoneInput.Resync(SigState.RINGING_IN))
        assertTrue(s.linkUp)
        assertEquals(Call(null, Direction.INCOMING, null, CallPhase.INCOMING), s.call)
        // ANSWER still works (no arguments); CONNECTED then fills in the id.
        val c = run(s, PhoneInput.Answering, ev(TerminalEvent.Connected(12, 1)))
        assertEquals(12L, c.call?.id)
        assertEquals(CallPhase.CONNECTED, c.call?.phase)
        assertNull(c.call?.peer)
    }

    @Test
    fun resyncEndsACallThatEndedWhileDisconnected() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)), PhoneInput.LinkDown)
        assertFalse(inCall.linkUp)
        assertEquals(CallPhase.CONNECTED, inCall.call?.phase)
        val s = run(inCall, PhoneInput.Resync(SigState.REGISTERED))
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertEquals("Call ended (the phone was disconnected from the terminal)", s.call?.endText)
    }

    @Test
    fun resyncReplacesAnOldCallWithTheNewOne() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)), PhoneInput.LinkDown)
        // While away the call ended and another one started ringing.
        val s = run(inCall, PhoneInput.Resync(SigState.RINGING_IN))
        assertEquals(Call(null, Direction.INCOMING, null, CallPhase.INCOMING), s.call)
    }

    @Test
    fun resyncDuringAndAfterActivation() {
        val start = PhoneState(sig = SigState.NOT_ACTIVATED)
        val activating = run(start, PhoneInput.Resync(SigState.ACTIVATING))
        assertEquals(Activation.InProgress(null), activating.activation)
        val lost = run(activating, PhoneInput.LinkDown, PhoneInput.Resync(SigState.REGISTERED))
        assertEquals(Activation.Interrupted, lost.activation)
        assertEquals(Activation.Idle, run(lost, PhoneInput.ClearActivation).activation)
    }

    @Test
    fun resyncUsesTheRememberedNumberAndForgetsItWhenNotActivated() {
        val s = run(PhoneState(), PhoneInput.Resync(SigState.REGISTERED, Remembered(me, RegMode.PART15)))
        assertEquals(me, s.number)
        assertEquals(RegMode.PART15, s.mode)
        val wiped = run(s, PhoneInput.LinkDown, PhoneInput.Resync(SigState.NOT_ACTIVATED, Remembered(me, RegMode.PART15)))
        assertNull(wiped.number)
        assertNull(wiped.mode)
    }

    @Test
    fun deactivatedClearsEverything() {
        val s = run(registered.copy(regFailureCode = 4), ev(TerminalEvent.Deactivated))
        assertEquals(PhoneState(linkUp = true, sig = SigState.NOT_ACTIVATED), s)
    }

    /** Numbering v2 §6.3: an event from pre-v2 firmware is explained, and changes nothing else. */
    @Test
    fun oldFirmwareEventIsExplainedNotApplied() {
        val s = run(registered, ev(TerminalEvent.OldFirmware(TerminalEvent.REGISTERED, "03 88 36 06 55 51 23 4f 01")))
        assertEquals(registered.copy(notice = "Terminal firmware uses old numbers: update it"), s)
    }

    @Test
    fun lateEndedForAnEarlierCallIsIgnored() {
        val s = run(registered, ev(TerminalEvent.Incoming(5, peer)), ev(TerminalEvent.Ended(4, 0)))
        assertEquals(CallPhase.INCOMING, s.call?.phase)
        assertEquals(5L, s.call?.id)
    }

    @Test
    fun registrationFailureIsShownUntilRegistered() {
        val s = run(registered.copy(sig = SigState.REGISTERING), ev(TerminalEvent.RegistrationFailed(4)))
        assertEquals("No answer from the network", s.regFailure?.text)
        assertNull(run(s, ev(TerminalEvent.Registered(me, 1))).regFailure)
    }

    /**
     * The firmware only notifies STATUS on change and can drop EVENTs: if the ENDED for a call
     * is lost and the only STATUS to arrive lands inside the 2 s grace, nothing else corrects the
     * call unless something ticks the reducer again.
     */
    @Test
    fun heldStatusIsReconciledByALaterTick() {
        val calling = PhoneReducer.reduce(registered, PhoneInput.Dialled(peer), 10_000)
        val held = PhoneReducer.reduce(calling, PhoneInput.Status(SigState.REGISTERED), 10_500)
        assertEquals(CallPhase.CALLING, held.call?.phase)
        val ticked = PhoneReducer.reduce(held, PhoneInput.Tick(10_000 + PhoneReducer.RECONCILE_GRACE_MS + 100), 10_000 + PhoneReducer.RECONCILE_GRACE_MS + 100)
        assertEquals(CallPhase.ENDED, ticked.call?.phase)
        assertNull(ticked.call?.causeCode)
    }

    @Test
    fun tickWithAnAgreeingStatusChangesNothing() {
        val calling = PhoneReducer.reduce(registered, PhoneInput.Dialled(peer), 10_000)
        val agreeing = PhoneReducer.reduce(calling, PhoneInput.Status(SigState.CALLING), 10_050)
        val ticked = PhoneReducer.reduce(agreeing, PhoneInput.Tick(20_000), 20_000)
        assertEquals(agreeing.call, ticked.call)
    }

    @Test
    fun resyncClearsARegistrationFailure() {
        val failing = run(registered.copy(sig = SigState.REGISTERING), ev(TerminalEvent.RegistrationFailed(4)))
        assertEquals(4, failing.regFailureCode)
        val s = run(failing, PhoneInput.LinkDown, PhoneInput.Resync(SigState.REGISTERED))
        assertNull(s.regFailure)
    }

    /**
     * A call still ringing-in across a resync might not be the one the app saw before: the
     * terminal doesn't queue EVENTs while disconnected, so a new call could have arrived and
     * replaced it without the app ever getting an INCOMING for it.
     */
    @Test
    fun resyncIntoAStillRingingCallForgetsTheOldCallerSoALaterEndedIsAccepted() {
        val ringing = run(registered, ev(TerminalEvent.Incoming(5, peer)))
        assertEquals(Call(5, Direction.INCOMING, peer, CallPhase.INCOMING), ringing.call)
        val s = run(ringing, PhoneInput.LinkDown, PhoneInput.Resync(SigState.RINGING_IN))
        assertEquals(Call(null, Direction.INCOMING, null, CallPhase.INCOMING), s.call)
        val ended = run(s, ev(TerminalEvent.Ended(6, 2)))
        assertEquals(CallPhase.ENDED, ended.call?.phase)
        assertEquals(EndCause.BUSY, ended.call?.cause)
    }

    /** C1: the link was closed for good (the user's Disconnect): nothing will follow the call any more. */
    @Test
    fun linkClosedEndsAnActiveCallWithAnUnknownCause() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)))
        val s = run(inCall, PhoneInput.LinkClosed)
        assertFalse(s.linkUp)
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertNull(s.call?.causeCode)
        assertEquals("Call ended (the phone was disconnected from the terminal)", s.call?.endText)
        // The stored STATUS (IN_CALL) is from a link that's gone: no reconcile re-creates the call.
        assertNull(PhoneReducer.reconcileDueAt(s))
        assertEquals(CallPhase.ENDED, PhoneReducer.reduce(s, PhoneInput.Tick(99_000), 99_000).call?.phase)
    }

    @Test
    fun linkClosedWithoutACallOnlyTakesTheLinkDown() {
        val s = run(registered, PhoneInput.LinkClosed)
        assertFalse(s.linkUp)
        assertNull(s.call)
    }

    /** C1: while the link is down, closing the call screen drops the local call; a later Resync brings it back if it's still up. */
    @Test
    fun dismissWhileTheLinkIsDownDropsAnActiveCall() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)))
        assertEquals(CallPhase.CONNECTED, run(inCall, PhoneInput.DismissCall).call?.phase) // link up: only ENDED is dismissed
        val down = run(inCall, PhoneInput.LinkDown)
        val dropped = run(down, PhoneInput.DismissCall)
        assertNull(dropped.call)
        val back = run(dropped, PhoneInput.Resync(SigState.IN_CALL))
        assertEquals(CallPhase.CONNECTED, back.call?.phase)
    }

    /** Triage: re-activating an activated terminal: DIAL would be refused (0x80) until it's done. */
    @Test
    fun activatingSetsTheSignallingStateSoDialIsNotOffered() {
        val s = run(registered, PhoneInput.Activating("+883160655509999"))
        assertEquals(SigState.ACTIVATING, s.sig)
        assertFalse(s.canDial)
    }

    /** Triage: STATUS moving an answered call off INCOMING clears "Answering…". */
    @Test
    fun statusMovingACallOffIncomingClearsAnswering() {
        val answering = PhoneReducer.reduce(run(registered, ev(TerminalEvent.Incoming(2, peer))), PhoneInput.Answering, 10_000)
        assertTrue(answering.call!!.answering)
        val late = 10_000 + PhoneReducer.RECONCILE_GRACE_MS
        val connected = PhoneReducer.reduce(answering, PhoneInput.Status(SigState.IN_CALL), late)
        assertEquals(CallPhase.CONNECTED, connected.call?.phase)
        assertFalse(connected.call!!.answering)
        val releasing = PhoneReducer.reduce(answering, PhoneInput.Status(SigState.RELEASING), late)
        assertEquals(CallPhase.RELEASING, releasing.call?.phase)
        assertFalse(releasing.call!!.answering)
    }

    @Test
    fun endedWithCallIdZeroEndsACallThatHasNoIdYet() {
        val s = run(registered, PhoneInput.Dialled(peer), PhoneInput.Releasing, ev(TerminalEvent.Ended(0, 0)))
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertEquals(EndCause.NORMAL, s.call?.cause)
        assertNull(s.call?.id)
    }

    @Test
    fun busyOrUnreachableBeforeACallIdEndsTheCallAndKeepsTheId() {
        val busy = run(registered, PhoneInput.Dialled(peer), ev(TerminalEvent.Ended(0, 2)))
        assertEquals(EndCause.BUSY, busy.call?.cause)
        assertEquals(peer, busy.call?.peer)
        val unreachable = run(registered, PhoneInput.Dialled(peer), ev(TerminalEvent.Ended(5, 4)))
        assertEquals(EndCause.UNREACHABLE, unreachable.call?.cause)
        assertEquals(5L, unreachable.call?.id)
    }

    @Test
    fun actFailedTokenUsedThenRegisteredKeepsTheFailureShown() {
        val s = run(registered, PhoneInput.Activating("+883160655509999"), ev(TerminalEvent.ActivationFailed(2)), ev(TerminalEvent.Registered(me, 1)))
        assertEquals(Activation.Failed(2), s.activation)
        assertEquals(SigState.REGISTERED, s.sig)
        assertEquals(me, s.number)
    }

    /** The terminal rebooted mid-call: it comes back registering, with no call. */
    @Test
    fun resyncRegisteringAfterARebootMidCallEndsTheCall() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)), PhoneInput.LinkDown)
        val s = run(inCall, PhoneInput.Resync(SigState.REGISTERING))
        assertEquals(SigState.REGISTERING, s.sig)
        assertEquals(CallPhase.ENDED, s.call?.phase)
        assertNull(s.call?.causeCode)
        assertFalse(s.canDial)
    }

    /** A STATUS showing a call the app has no event for (the INCOMING was dropped) creates it once no grace applies. */
    @Test
    fun statusCreatesACallTheAppHadNoEventFor() {
        val s = PhoneReducer.reduce(registered, PhoneInput.Status(SigState.RINGING_IN), 50_000)
        assertEquals(Call(null, Direction.INCOMING, null, CallPhase.INCOMING), s.call)
        val out = PhoneReducer.reduce(registered, PhoneInput.Status(SigState.IN_CALL), 50_000, wallNow = 1_700_000_000_000)
        assertEquals(Call(null, null, null, CallPhase.CONNECTED, connectedAt = 1_700_000_000_000), out.call)
    }

    /** STATUS ended the call first (cause unknown); the ENDED arriving late fills in the cause. */
    @Test
    fun aLateEndedBackFillsTheCauseOfACallStatusAlreadyEnded() {
        val inCall = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)))
        val byStatus = PhoneReducer.reduce(inCall, PhoneInput.Status(SigState.REGISTERED), 50_000)
        assertEquals(CallPhase.ENDED, byStatus.call?.phase)
        assertNull(byStatus.call?.causeCode)
        val filled = PhoneReducer.reduce(byStatus, ev(TerminalEvent.Ended(2, 3)), 50_100)
        assertEquals(EndCause.NO_ANSWER, filled.call?.cause)
        assertEquals(2L, filled.call?.id)
    }

    @Test
    fun releasingAndAnsweringWithoutAMatchingCallChangeNothing() {
        assertEquals(registered, run(registered, PhoneInput.Releasing))
        assertEquals(registered, run(registered, PhoneInput.Answering))
        val connected = run(registered, ev(TerminalEvent.Incoming(2, peer)), ev(TerminalEvent.Connected(2, 1)))
        assertEquals(connected, PhoneReducer.reduce(connected, PhoneInput.Answering, 10_200))
    }

    @Test
    fun statusKeepsARegistrationFailureOnlyWhileRegistering() {
        val failing = run(registered.copy(sig = SigState.REGISTERING), ev(TerminalEvent.RegistrationFailed(4)))
        assertEquals(4, run(failing, PhoneInput.Status(SigState.REGISTERING)).regFailureCode)
        assertNull(run(failing, PhoneInput.Status(SigState.REGISTERED)).regFailureCode)
    }

    @Test
    fun reconcileDueAtIsTheEndOfTheGraceOnlyWhileAStatusDisagrees() {
        assertNull(PhoneReducer.reconcileDueAt(registered))
        assertNull(PhoneReducer.reconcileDueAt(registered.copy(sig = null)))
        val calling = PhoneReducer.reduce(registered, PhoneInput.Dialled(peer), 10_000)
        assertNull(PhoneReducer.reconcileDueAt(calling)) // Dialled set sig = CALLING: agrees
        val held = PhoneReducer.reduce(calling, PhoneInput.Status(SigState.REGISTERED), 10_500)
        assertEquals(10_000 + PhoneReducer.RECONCILE_GRACE_MS, PhoneReducer.reconcileDueAt(held))
        assertNull(PhoneReducer.reconcileDueAt(held.copy(linkUp = false)))
    }
}
