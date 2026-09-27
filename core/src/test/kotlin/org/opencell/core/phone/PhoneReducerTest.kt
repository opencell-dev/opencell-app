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
    private val me = "+8836065551234"
    private val peer = "+8836065550100"
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
        val s = run(registered, PhoneInput.Activating("+8836065559999"), ev(TerminalEvent.ActivationFailed(2)))
        assertEquals(Activation.Failed(2), s.activation)
        assertEquals("This code has already been used (token used)", (s.activation as Activation.Failed).text)
        assertEquals(me, s.number)
        assertEquals("Activation failed (reason 77)", Activation.Failed(77).text)
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
        assertEquals(Call(4, Direction.OUTGOING, peer, CallPhase.CONNECTED, connectedAt = 10_100), s.call)
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
}
