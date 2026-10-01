package org.opencell.core.calllog

import org.opencell.core.phone.Direction
import org.opencell.core.phone.FinishedCall
import org.opencell.core.protocol.DialCheck
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.voice.VoiceCounters

/** How a call in the log went, for its icon and its first word. */
enum class CallKind(val label: String) {
    OUTGOING("Outgoing"),

    /** An incoming call that connected. */
    INCOMING("Incoming"),

    /** An incoming call that never connected and that the user didn't reject here. */
    MISSED("Missed"),

    /** An incoming call the user rejected on this phone. */
    REJECTED("Rejected"),

    /** A call the app only knew from STATUS (it reconnected mid-call): direction unknown. */
    UNKNOWN("Call"),
}

/**
 * One call in the log (dial-and-recents spec §4.2). Times are wall-clock
 * millis. [number] is the full form (`+883…`) when the app could complete it,
 * the digits as dialled when it couldn't, or null when the caller wasn't known.
 * [connectedAt] is null for a call that never connected; [causeCode] is null
 * when the end wasn't seen. [voice] is the call's voice counters (null if it
 * never connected). [seen] is false only for a missed call nobody has looked
 * at in Recents yet (the badge).
 */
data class CallLogEntry(
    val id: Long,
    val kind: CallKind,
    val number: String?,
    val startedAt: Long,
    val connectedAt: Long?,
    val endedAt: Long,
    val causeCode: Int?,
    val codec: Int?,
    val voice: VoiceCounters?,
    val seen: Boolean,
) {
    /** Connected time, the call's duration as the log shows it; null if it never connected. */
    val durationMillis: Long? get() = connectedAt?.let { (endedAt - it).coerceAtLeast(0) }

    companion object {
        /** The log's entry for [call], with [id] and the call's [voice] counters. */
        fun of(call: FinishedCall, id: Long, voice: VoiceCounters?): CallLogEntry {
            val kind = when (call.direction) {
                Direction.OUTGOING -> CallKind.OUTGOING
                Direction.INCOMING -> when {
                    call.connectedAt != null -> CallKind.INCOMING
                    call.rejected -> CallKind.REJECTED
                    else -> CallKind.MISSED
                }
                null -> CallKind.UNKNOWN
            }
            // A national form dialled before the terminal's own number was known is completed now if it can be.
            val number = call.peer?.let { (PhoneNumber.check(it, call.home) as? DialCheck.Number)?.full ?: it }
            return CallLogEntry(
                id = id,
                kind = kind,
                number = number,
                startedAt = call.startedAt,
                connectedAt = call.connectedAt,
                endedAt = call.endedAt,
                causeCode = call.causeCode,
                codec = call.codec,
                voice = if (call.connectedAt != null) voice else null,
                seen = kind != CallKind.MISSED,
            )
        }
    }
}
