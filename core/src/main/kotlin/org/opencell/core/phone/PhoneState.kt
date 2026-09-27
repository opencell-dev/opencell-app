package org.opencell.core.phone

import org.opencell.core.protocol.ActFailReason
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.RegFailReason
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalEvent

enum class CallPhase(val label: String) {
    CALLING("Calling…"),
    RINGING("Ringing…"),
    INCOMING("Incoming call"),
    CONNECTED("Connected"),
    RELEASING("Ending…"),
    ENDED("Call ended");

    val active: Boolean get() = this != ENDED
}

enum class Direction { OUTGOING, INCOMING }

/**
 * The current or last call as the phone knows it. [id], [direction] and [peer]
 * are null when the app learned about the call from STATUS alone: the terminal
 * doesn't queue EVENTs while no phone is connected, so after a reconnect an
 * incoming call can be ringing with no caller or call id known. ANSWER, REJECT
 * and HANGUP take no arguments, so such a call can still be handled.
 */
data class Call(
    val id: Long?,
    val direction: Direction?,
    val peer: String?,
    val phase: CallPhase,
    /** ENDED's raw cause; null while active, or when the end was missed while disconnected. */
    val causeCode: Int? = null,
    /** ANSWER was accepted; CONNECTED hasn't come yet. */
    val answering: Boolean = false,
    /** Wall-clock millis of CONNECTED, for the call display. */
    val connectedAt: Long? = null,
) {
    val cause: EndCause? get() = causeCode?.let { EndCause.fromCode(it) }

    /** What the ended screen says. */
    val endText: String
        get() = when {
            causeCode == null -> "Call ended (the phone was disconnected from the terminal)"
            else -> cause?.text ?: "Call ended (cause $causeCode)"
        }
}

sealed interface Activation {
    data object Idle : Activation

    /** ACTIVATE accepted; waiting for ACTIVATED or ACT_FAILED. [number] is the QR's, null after a resync. */
    data class InProgress(val number: String?) : Activation

    data class Succeeded(val number: String) : Activation

    data class Failed(val reasonCode: Int) : Activation {
        val reason: ActFailReason? get() = ActFailReason.fromCode(reasonCode)
        val text: String get() = reason?.text ?: "Activation failed (reason $reasonCode)"
    }

    /** The link dropped during activation and the result event was lost; STATUS shows where the terminal is. */
    data object Interrupted : Activation
}

/** Everything the phone screens show, driven by EVENTs, STATUS byte 3 and the app's own accepted commands. */
data class PhoneState(
    val linkUp: Boolean = false,
    /** Last known signalling state; null until the first STATUS after connecting. */
    val sig: SigState? = null,
    val number: String? = null,
    val mode: RegMode? = null,
    val activation: Activation = Activation.Idle,
    val regFailureCode: Int? = null,
    val call: Call? = null,
    /**
     * Monotonic millis of the last local change to [call] (event or accepted command), for the
     * reconcile grace; [NEVER] until the first. Not wall-clock time: a clock step must not
     * stretch or skip the grace.
     */
    val callChangedAt: Long = NEVER,
    /** A one-line message for the user: a refused command, a bad number. */
    val notice: String? = null,
) {
    val regFailure: RegFailReason? get() = regFailureCode?.let { RegFailReason.fromCode(it) }
    val activeCall: Call? get() = call?.takeIf { it.phase.active }
    val canDial: Boolean get() = linkUp && sig == SigState.REGISTERED && activeCall == null

    companion object {
        /** [callChangedAt] before any call change: long enough ago that no grace applies. */
        const val NEVER = Long.MIN_VALUE / 2
    }
}

/** A number and mode remembered from an earlier REGISTERED (display only; the terminal holds the keys). */
data class Remembered(val number: String, val mode: RegMode?)

sealed interface PhoneInput {
    data class Event(val event: TerminalEvent) : PhoneInput

    /** A STATUS notification. */
    data class Status(val sig: SigState) : PhoneInput

    /** The first STATUS read after (re)connecting: events may have been lost, so reconcile everything. */
    data class Resync(val sig: SigState, val remembered: Remembered? = null) : PhoneInput

    /**
     * A clock tick with no event of its own. The firmware only notifies STATUS on
     * change and can drop EVENTs, so a STATUS held back by [PhoneReducer.RECONCILE_GRACE_MS]
     * (it disagreed with a call the app had just changed locally) would otherwise never get
     * re-examined if nothing else happens. [PhoneReducer.reduce] reconciles against the last
     * known STATUS after every input, including this one, once the grace has elapsed;
     * [PhoneReducer.reconcileDueAt] tells a caller when to schedule the next one.
     */
    data class Tick(val now: Long) : PhoneInput

    /** The link dropped; the link manager is retrying, and the next [Resync] catches up. The call stays. */
    data object LinkDown : PhoneInput

    /** The link came up but STATUS couldn't be read: only [PhoneState.linkUp] changes; the next STATUS catches up. */
    data object LinkUp : PhoneInput

    /**
     * The link was closed for good (the user's Disconnect, or the service stopping): no
     * reconnect follows, so nothing will ever update an active call again. It ends with an
     * unknown cause, and the stored signalling state (from a link that's gone) is forgotten.
     */
    data object LinkClosed : PhoneInput

    /** The terminal accepted ACTIVATE for a code for [number]. */
    data class Activating(val number: String) : PhoneInput

    /** The terminal accepted DIAL. */
    data class Dialled(val number: String) : PhoneInput

    /** The terminal accepted ANSWER. */
    data object Answering : PhoneInput

    /** The terminal accepted HANGUP or REJECT. */
    data object Releasing : PhoneInput

    data class Notice(val text: String?) : PhoneInput

    /**
     * The user closed the call screen: an ENDED call, or (while the link is down, when the
     * call's buttons can't reach the terminal) any call. A call that is really still up in
     * the terminal comes back with the next [Resync].
     */
    data object DismissCall : PhoneInput

    /** The user acknowledged an activation result. */
    data object ClearActivation : PhoneInput
}

/**
 * The phone's state machine as a pure function, so every transition is a unit test.
 *
 * EVENTs are the detailed source (numbers, call ids, causes); STATUS byte 3
 * is the authoritative state. A STATUS notification can be older than an
 * accepted command (it may have been sent just before the terminal took the
 * command), so ordinary STATUS updates only correct the call after
 * [RECONCILE_GRACE_MS] without local changes; [reduce] re-checks this after
 * every input (not just STATUS), so a [PhoneInput.Tick] can carry a held
 * correction through even if the firmware sends no further STATUS (it only
 * notifies on change, and can drop EVENTs). The first STATUS after a
 * reconnect ([PhoneInput.Resync]) corrects everything at once, including a
 * still-ringing call whose caller can no longer be trusted to be the same one.
 */
object PhoneReducer {
    const val RECONCILE_GRACE_MS = 2_000L

    /**
     * [now] is monotonic millis (the grace, [PhoneState.callChangedAt], [PhoneInput.Tick]);
     * [wallNow] is wall-clock millis, used only for what's displayed ([Call.connectedAt]).
     */
    fun reduce(s: PhoneState, input: PhoneInput, now: Long, wallNow: Long = now): PhoneState =
        reconcileIfDue(apply(s, input, now, wallNow), now, wallNow)

    /**
     * When a STATUS held back by [RECONCILE_GRACE_MS] will next need reconciling
     * (so a caller can schedule a [PhoneInput.Tick] then), or null if none is pending.
     */
    fun reconcileDueAt(s: PhoneState): Long? {
        if (!s.linkUp) return null
        val sig = s.sig ?: return null
        return if (disagrees(s.activeCall, sig)) s.callChangedAt + RECONCILE_GRACE_MS else null
    }

    private fun apply(s: PhoneState, input: PhoneInput, now: Long, wallNow: Long): PhoneState = when (input) {
        is PhoneInput.Event -> event(s, input.event, now, wallNow)
        is PhoneInput.Status -> s.copy(
            sig = input.sig,
            regFailureCode = if (input.sig == SigState.REGISTERING) s.regFailureCode else null,
        )
        is PhoneInput.Resync -> resync(s, input, now, wallNow)
        is PhoneInput.Tick -> s
        PhoneInput.LinkDown -> s.copy(linkUp = false)
        PhoneInput.LinkUp -> s.copy(linkUp = true)
        PhoneInput.LinkClosed -> {
            val c = s.activeCall
            s.copy(
                linkUp = false,
                sig = null,
                call = c?.copy(phase = CallPhase.ENDED, causeCode = null, answering = false) ?: s.call,
                callChangedAt = if (c != null) now else s.callChangedAt,
            )
        }
        is PhoneInput.Activating -> s.copy(sig = SigState.ACTIVATING, activation = Activation.InProgress(input.number), notice = null)
        is PhoneInput.Dialled -> s.copy(
            sig = SigState.CALLING,
            call = Call(null, Direction.OUTGOING, input.number, CallPhase.CALLING),
            callChangedAt = now,
            notice = null,
        )
        PhoneInput.Answering -> s.activeCall?.takeIf { it.phase == CallPhase.INCOMING }
            ?.let { s.copy(call = it.copy(answering = true), callChangedAt = now) } ?: s
        PhoneInput.Releasing -> s.activeCall?.let {
            s.copy(sig = SigState.RELEASING, call = it.copy(phase = CallPhase.RELEASING), callChangedAt = now)
        } ?: s
        is PhoneInput.Notice -> s.copy(notice = input.text)
        PhoneInput.DismissCall -> if (s.call?.phase == CallPhase.ENDED || !s.linkUp) s.copy(call = null) else s
        PhoneInput.ClearActivation -> if (s.activation is Activation.InProgress) s else s.copy(activation = Activation.Idle)
    }

    private fun event(s: PhoneState, e: TerminalEvent, now: Long, wallNow: Long): PhoneState = when (e) {
        is TerminalEvent.Activated -> s.copy(
            number = e.number,
            activation = Activation.Succeeded(e.number),
            sig = SigState.REGISTERING,
            regFailureCode = null,
        )
        is TerminalEvent.ActivationFailed -> s.copy(activation = Activation.Failed(e.reasonCode))
        is TerminalEvent.Registered -> s.copy(
            number = e.number,
            mode = e.mode,
            sig = SigState.REGISTERED,
            regFailureCode = null,
            activation = if (s.activation is Activation.InProgress) Activation.Succeeded(e.number) else s.activation,
        )
        is TerminalEvent.RegistrationFailed -> s.copy(regFailureCode = e.reasonCode)
        is TerminalEvent.Incoming -> s.copy(
            sig = SigState.RINGING_IN,
            call = Call(e.callId, Direction.INCOMING, e.caller, CallPhase.INCOMING),
            callChangedAt = now,
        )
        is TerminalEvent.Ringing -> {
            val c = s.activeCall?.takeIf { it.direction != Direction.INCOMING }
            s.copy(
                sig = SigState.RINGING_OUT,
                call = c?.copy(id = e.callId, phase = CallPhase.RINGING) ?: Call(e.callId, Direction.OUTGOING, null, CallPhase.RINGING),
                callChangedAt = now,
            )
        }
        is TerminalEvent.Connected -> {
            val c = s.activeCall
            s.copy(
                sig = SigState.IN_CALL,
                call = c?.copy(id = e.callId, phase = CallPhase.CONNECTED, answering = false, connectedAt = wallNow)
                    ?: Call(e.callId, null, null, CallPhase.CONNECTED, connectedAt = wallNow),
                callChangedAt = now,
            )
        }
        is TerminalEvent.Ended -> {
            val c = s.call
            if (c != null && c.phase.active && c.id != null && e.callId != 0L && c.id != e.callId) {
                s // a late ENDED for an earlier call
            } else {
                val base = c ?: Call(e.callId, null, null, CallPhase.ENDED)
                s.copy(
                    sig = SigState.REGISTERED,
                    call = base.copy(
                        id = base.id ?: e.callId.takeIf { it != 0L },
                        phase = CallPhase.ENDED,
                        causeCode = e.causeCode,
                        answering = false,
                    ),
                    callChangedAt = now,
                )
            }
        }
        TerminalEvent.Deactivated -> PhoneState(linkUp = s.linkUp, sig = SigState.NOT_ACTIVATED)
        is TerminalEvent.Unknown -> s
    }

    private fun resync(s: PhoneState, r: PhoneInput.Resync, now: Long, wallNow: Long): PhoneState {
        var t = s.copy(
            linkUp = true,
            sig = r.sig,
            regFailureCode = if (r.sig == SigState.REGISTERING) s.regFailureCode else null,
        )
        if (r.sig == SigState.NOT_ACTIVATED) {
            t = t.copy(number = null, mode = null)
        } else if (t.number == null && r.remembered != null) {
            t = t.copy(number = r.remembered.number, mode = r.remembered.mode)
        }
        t = when {
            r.sig == SigState.ACTIVATING && t.activation !is Activation.InProgress -> t.copy(activation = Activation.InProgress(null))
            r.sig != SigState.ACTIVATING && t.activation is Activation.InProgress -> t.copy(activation = Activation.Interrupted)
            else -> t
        }
        val reconciled = reconcileCall(t, r.sig, now, wallNow)
        // The terminal doesn't queue EVENTs while no phone is connected: a call still ringing-in
        // across a resync might not be the same one the app saw before, so its id and caller
        // (if any) can no longer be trusted. Dropping them also lets a later ENDED for a call the
        // app never saw the INCOMING for be accepted instead of ignored as "for an earlier call".
        val call = reconciled.call
        return if (call != null && call.phase == CallPhase.INCOMING && (call.id != null || call.peer != null)) {
            reconciled.copy(call = call.copy(id = null, peer = null), callChangedAt = now)
        } else {
            reconciled
        }
    }

    /** If a STATUS was held back by [RECONCILE_GRACE_MS] and that grace has now elapsed, reconcile the call against it. */
    private fun reconcileIfDue(s: PhoneState, now: Long, wallNow: Long): PhoneState {
        // While the link is down the stored STATUS is from a link that's gone: the Resync on reconnect corrects everything.
        if (!s.linkUp) return s
        val sig = s.sig ?: return s
        if (now - s.callChangedAt < RECONCILE_GRACE_MS) return s
        return reconcileCall(s, sig, now, wallNow)
    }

    private fun reconcileCall(s: PhoneState, sig: SigState, now: Long, wallNow: Long): PhoneState {
        val c = s.activeCall
        if (!disagrees(c, sig)) return s
        if (!sig.hasCall) {
            return s.copy(call = c!!.copy(phase = CallPhase.ENDED, causeCode = null, answering = false), callChangedAt = now)
        }
        val phase = phaseOf(sig)
        return if (c == null || !continues(c.phase, phase)) {
            s.copy(
                call = Call(
                    id = null,
                    direction = when (sig) {
                        SigState.RINGING_IN -> Direction.INCOMING
                        SigState.CALLING, SigState.RINGING_OUT -> Direction.OUTGOING
                        else -> null
                    },
                    peer = null,
                    phase = phase,
                    connectedAt = if (phase == CallPhase.CONNECTED) wallNow else null,
                ),
                callChangedAt = now,
            )
        } else {
            s.copy(
                call = c.copy(
                    phase = phase,
                    answering = c.answering && phase == CallPhase.INCOMING,
                    connectedAt = c.connectedAt ?: if (phase == CallPhase.CONNECTED) wallNow else null,
                ),
                callChangedAt = now,
            )
        }
    }

    /** Whether [sig] disagrees with the current active call [c] enough that reconciling would change it. */
    private fun disagrees(c: Call?, sig: SigState): Boolean {
        if (!sig.hasCall) return c != null
        val phase = phaseOf(sig)
        return c == null || !continues(c.phase, phase) || c.phase != phase
    }

    /** Whether [next] can follow [from] within one call; otherwise STATUS shows a different, newer call. */
    private fun continues(from: CallPhase, next: CallPhase): Boolean = when (from) {
        CallPhase.CALLING -> next != CallPhase.INCOMING
        CallPhase.RINGING -> next != CallPhase.INCOMING && next != CallPhase.CALLING
        CallPhase.INCOMING -> next == CallPhase.INCOMING || next == CallPhase.CONNECTED || next == CallPhase.RELEASING
        CallPhase.CONNECTED -> next == CallPhase.CONNECTED || next == CallPhase.RELEASING
        CallPhase.RELEASING -> next == CallPhase.RELEASING
        CallPhase.ENDED -> false
    }

    private fun phaseOf(sig: SigState): CallPhase = when (sig) {
        SigState.CALLING -> CallPhase.CALLING
        SigState.RINGING_OUT -> CallPhase.RINGING
        SigState.RINGING_IN -> CallPhase.INCOMING
        SigState.IN_CALL -> CallPhase.CONNECTED
        else -> CallPhase.RELEASING
    }
}
