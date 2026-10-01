package org.opencell.core.calllog

import org.opencell.core.phone.ServiceNumbers
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.voice.CodecId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One day's calls in Recents, under its heading. */
data class CallDay(val label: String, val entries: List<CallLogEntry>)

/**
 * How Recents words the call log (dial-and-recents spec §5.2), pure so the
 * iOS app can port it with its tests.
 */
object CallLogDisplay {
    /** [entries] (newest first) grouped by local day, newest day first, each headed "Today", "Yesterday", a weekday, or a date. */
    fun byDay(entries: List<CallLogEntry>, zone: ZoneId, today: LocalDate, locale: Locale): List<CallDay> =
        entries.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .entries.sortedByDescending { it.key }
            .map { (day, list) -> CallDay(dayLabel(day, today, locale), list.sortedByDescending { it.startedAt }) }

    fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String = when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> DateTimeFormatter.ofPattern("EEEE", locale).format(day)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }

    /** The row's title: a test service's name, the number, or who it was when unknown. */
    fun title(e: CallLogEntry): String = ServiceNumbers.label(e.number)
        ?: e.number?.let(PhoneNumber::display)
        ?: if (e.kind == CallKind.OUTGOING) "Unknown number" else "Unknown caller"

    /** The number under a test service's name (null when the title is already the number). */
    fun subtitleNumber(e: CallLogEntry): String? = e.number?.takeIf { ServiceNumbers.label(it) != null }?.let(PhoneNumber::display)

    /** `0:07`, `2:15`, `1:02:03`. */
    fun duration(millis: Long): String {
        val s = millis / 1000
        return if (s >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }

    /**
     * The row's second line: the kind, then the duration of a connected call or
     * how an unconnected outgoing call ended, then a dropped call's cause.
     * "Outgoing · 2:15", "Outgoing · Busy", "Outgoing · Cancelled",
     * "Incoming · 0:42 · Radio link lost", "Missed", "Rejected".
     */
    fun detail(e: CallLogEntry): String {
        val parts = mutableListOf(e.kind.label)
        val d = e.durationMillis
        if (d != null) {
            parts += duration(d)
            when (e.causeCode) {
                EndCause.NETWORK_FAILURE.code, EndCause.LINK_LOST.code -> parts += causeText(e.causeCode)
                null -> parts += AWAY
            }
        } else if (e.kind == CallKind.OUTGOING || e.kind == CallKind.UNKNOWN) {
            parts += when (e.causeCode) {
                null -> AWAY
                EndCause.NORMAL.code -> "Cancelled"
                else -> causeText(e.causeCode)
            }
        }
        return parts.joinToString(" · ")
    }

    /** The cause in words ([EndCause.text]), "cause N" for one this app doesn't know. */
    fun causeText(code: Int): String = EndCause.fromCode(code)?.text ?: "cause $code"

    /** Developer options only: the codec and the call's voice counters, like the call screen's line. */
    fun voiceLine(e: CallLogEntry): String? {
        val codec = e.codec?.let(CodecId::label)
        val v = e.voice
        val counters = v?.let { "sent ${it.sent} · not sent ${it.notSent} · received ${it.received} · concealed ${it.concealed}" }
        return listOfNotNull(codec, counters).joinToString(" · ").ifEmpty { null }
    }

    /** What TalkBack reads for a row: "Missed call, +883-1-606-555-00100, 14:05". */
    fun spoken(e: CallLogEntry, time: String): String {
        val what = when (e.kind) {
            CallKind.OUTGOING -> "Outgoing call"
            CallKind.INCOMING -> "Incoming call"
            CallKind.MISSED -> "Missed call"
            CallKind.REJECTED -> "Rejected call"
            CallKind.UNKNOWN -> "Call"
        }
        return listOfNotNull(what, title(e), subtitleNumber(e), detail(e).substringAfter(" · ", "").ifEmpty { null }, time)
            .joinToString(", ")
    }

    private const val AWAY = "phone was away from the terminal"
}
