package org.opencell.core.phone

import org.opencell.core.protocol.TerminalEvent

/**
 * One call that stopped being active, as the phone's state machine saw it: the
 * call log's source (dial-and-recents spec §4). [peer] is what the call carried
 * (a full number, raw national digits dialled before the terminal's own number
 * was known, or null), with [home] the terminal's own number at the time so
 * the log can complete it. [causeCode] is null when the end wasn't seen (the
 * link was closed, the user closed the call while the link was down, or STATUS
 * showed a different call). [rejected]: the user turned down the incoming call
 * on this phone (REJECT accepted while it rang).
 */
data class FinishedCall(
    val direction: Direction?,
    val peer: String?,
    val home: String?,
    val startedAt: Long,
    val connectedAt: Long?,
    val endedAt: Long,
    val causeCode: Int?,
    val codec: Int?,
    val rejected: Boolean,
)

/**
 * Follows [PhoneReducer]'s steps and reports each call once, when it stops
 * being active: it ended (ENDED, or the link closed for good), the user closed
 * it while the link was down, or STATUS replaced it with a different call
 * ([PhoneState.callSerial] changed). It remembers when the app first saw the
 * current call and whether the user rejected it. Not thread-safe:
 * [PhoneSession] calls [step] under its lock, once per reduce, in order.
 */
class CallTracker {
    private var known = false
    private var startedAt = 0L
    private var rejected = false

    /** One reduce: [before] --[input]--> [after], at wall-clock [wallNow]. The finished call, if one finished. */
    fun step(before: PhoneState, input: PhoneInput, after: PhoneState, wallNow: Long): FinishedCall? {
        if (!known && before.activeCall != null) begin(wallNow) // a call already up when tracking started
        val was = before.activeCall
        val sameCall = after.callSerial == before.callSerial
        val deactivated = input is PhoneInput.Event && input.event is TerminalEvent.Deactivated
        var finished: FinishedCall? = null
        if (was != null && !deactivated && (!sameCall || after.activeCall == null)) {
            val ended = after.call?.takeIf { sameCall && it.phase == CallPhase.ENDED }
            finished = FinishedCall(
                direction = was.direction,
                peer = was.peer,
                home = before.number,
                startedAt = startedAt,
                connectedAt = ended?.connectedAt ?: was.connectedAt,
                endedAt = wallNow,
                causeCode = ended?.causeCode,
                codec = was.codec,
                rejected = rejected,
            )
        }
        if (input == PhoneInput.Releasing && sameCall && was?.phase == CallPhase.INCOMING) rejected = true
        if (!sameCall) begin(wallNow)
        return finished
    }

    private fun begin(wallNow: Long) {
        known = true
        startedAt = wallNow
        rejected = false
    }
}
