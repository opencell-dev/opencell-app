package org.opencell.app.service

import org.opencell.core.phone.PhoneSession
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.SigState
import org.opencell.core.protocol.TerminalStatus

/**
 * The ongoing notification's text while the link is up, from the phone's state alone (a pure
 * function so it's unit-testable, like [ringPlan]). A silent call ([org.opencell.core.phone.Call.silent]:
 * waiting for CALL_CAPS, or being turned down for want of a codec) is not announced: not by its
 * caller, and not by the terminal's RINGING_IN state either, so the line reads as if idle.
 */
fun connectedText(phone: PhoneState?, status: TerminalStatus?): String {
    val call = phone?.activeCall
    val shown = call?.takeUnless { it.silent }
    val sig = if (call != null && shown == null && phone.sig == SigState.RINGING_IN) SigState.REGISTERED else phone?.sig
    return when {
        shown != null -> shown.phase.label + (shown.peer?.let { " · " + PhoneSession.peerLabel(it, phone.number) } ?: "")
        sig != null -> sig.label + (phone?.number?.let { " · " + PhoneNumber.display(it) } ?: "") +
            (status?.let { " · ${it.signalLabel}" } ?: "")
        else -> status?.let { "${it.stateLabel} · ${it.bandLabel} · ${it.signalLabel}" } ?: "Connected"
    }
}
