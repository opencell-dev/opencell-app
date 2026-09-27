package org.opencell.app.service

import org.opencell.core.phone.CallPhase

/** What [LinkService] should be doing about a possibly-ringing call. */
data class RingPlan(val ring: Boolean, val notify: Boolean)

/**
 * [LinkService]'s decision, from the phone's state alone (a pure function so
 * it's unit-testable on its own). It rings ([CallRinger]: ringtone plus
 * vibration) whenever a call is INCOMING, not yet answered, and the link is
 * up — regardless of which screen is visible, since neither the in-app call
 * screen nor [org.opencell.app.ui.CallActivity] make any sound themselves.
 * [answering] stops the ring immediately on an accepted ANSWER, even though
 * the call's phase itself doesn't leave INCOMING until CONNECTED arrives.
 * Losing the link stops both, the same as answering, rejecting (which does
 * move the phase away from INCOMING immediately) or the call ending.
 *
 * The call notification only needs to be showing for
 * [org.opencell.app.ui.MainActivity] to bring the call screen back up if it
 * isn't already the one on screen; [org.opencell.app.ui.CallActivity] being
 * in front (what the full-screen intent or Answer opened) is not
 * [mainInFront], so the notification stays posted while it's the one
 * showing the call.
 */
fun ringPlan(phase: CallPhase?, answering: Boolean, linkUp: Boolean, mainInFront: Boolean): RingPlan {
    val incoming = phase == CallPhase.INCOMING && !answering && linkUp
    return RingPlan(ring = incoming, notify = incoming && !mainInFront)
}
