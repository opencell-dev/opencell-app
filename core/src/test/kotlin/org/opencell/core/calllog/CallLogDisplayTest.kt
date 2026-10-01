package org.opencell.core.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencell.core.protocol.EndCause
import org.opencell.core.voice.VoiceCounters
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** How Recents words and groups the log (dial-and-recents spec §5.2). */
class CallLogDisplayTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 1) // a Thursday

    private fun at(day: LocalDate, h: Int, m: Int) = ZonedDateTime.of(day.atTime(h, m), zone).toInstant().toEpochMilli()

    private fun entry(
        id: Long,
        kind: CallKind = CallKind.OUTGOING,
        startedAt: Long = 0,
        connectedFor: Long? = null,
        cause: Int? = EndCause.NORMAL.code,
        number: String? = "+883160655501235",
    ) = CallLogEntry(
        id, kind, number, startedAt, connectedFor?.let { startedAt + 1_000 }, startedAt + 1_000 + (connectedFor ?: 0),
        cause, null, null, seen = true,
    )

    @Test
    fun daysAreHeadedTodayYesterdayWeekdayThenDate() {
        val us = Locale.US
        assertEquals("Today", CallLogDisplay.dayLabel(today, today, us))
        assertEquals("Yesterday", CallLogDisplay.dayLabel(today.minusDays(1), today, us))
        assertEquals("Monday", CallLogDisplay.dayLabel(today.minusDays(3), today, us))
        assertEquals("Friday", CallLogDisplay.dayLabel(today.minusDays(6), today, us))
        assertEquals("Sep 24, 2026", CallLogDisplay.dayLabel(today.minusDays(7), today, us))
    }

    @Test
    fun entriesGroupByLocalDayNewestFirst() {
        val e1 = entry(1, startedAt = at(today.minusDays(1), 23, 59))
        val e2 = entry(2, startedAt = at(today, 0, 1))
        val e3 = entry(3, startedAt = at(today, 14, 5))
        val days = CallLogDisplay.byDay(listOf(e3, e2, e1), zone, today, Locale.US)
        assertEquals(listOf("Today", "Yesterday"), days.map { it.label })
        assertEquals(listOf(3L, 2L), days[0].entries.map { it.id })
        assertEquals(listOf(1L), days[1].entries.map { it.id })
        assertEquals(emptyList<CallDay>(), CallLogDisplay.byDay(emptyList(), zone, today, Locale.US))
    }

    @Test
    fun durations() {
        assertEquals("0:00", CallLogDisplay.duration(999))
        assertEquals("0:07", CallLogDisplay.duration(7_400))
        assertEquals("2:15", CallLogDisplay.duration(135_000))
        assertEquals("1:02:03", CallLogDisplay.duration(3_723_000))
    }

    @Test
    fun theSecondLineSaysHowTheCallWent() {
        assertEquals("Outgoing · 2:15", CallLogDisplay.detail(entry(1, connectedFor = 135_000)))
        assertEquals("Outgoing · Busy", CallLogDisplay.detail(entry(1, cause = EndCause.BUSY.code)))
        assertEquals("Outgoing · Cancelled", CallLogDisplay.detail(entry(1, cause = EndCause.NORMAL.code)))
        assertEquals("Outgoing · Number unreachable", CallLogDisplay.detail(entry(1, cause = EndCause.UNREACHABLE.code)))
        assertEquals("Outgoing · cause 42", CallLogDisplay.detail(entry(1, cause = 42)))
        assertEquals("Outgoing · phone was away from the terminal", CallLogDisplay.detail(entry(1, cause = null)))
        assertEquals(
            "Incoming · 0:42 · Radio link lost",
            CallLogDisplay.detail(entry(1, CallKind.INCOMING, connectedFor = 42_000, cause = EndCause.LINK_LOST.code)),
        )
        assertEquals("Incoming · 0:42", CallLogDisplay.detail(entry(1, CallKind.INCOMING, connectedFor = 42_000)))
        assertEquals("Missed", CallLogDisplay.detail(entry(1, CallKind.MISSED, cause = EndCause.NO_ANSWER.code)))
        assertEquals("Rejected", CallLogDisplay.detail(entry(1, CallKind.REJECTED, cause = EndCause.REJECTED.code)))
        assertEquals("Call · 1:00", CallLogDisplay.detail(entry(1, CallKind.UNKNOWN, connectedFor = 60_000)))
    }

    @Test
    fun titlesNameTestServicesAndUnknownParties() {
        assertEquals("+883-1-606-555-01235", CallLogDisplay.title(entry(1)))
        assertNull(CallLogDisplay.subtitleNumber(entry(1)))
        val echo = entry(1, number = "+883160655500100")
        assertEquals("Echo test (core 1)", CallLogDisplay.title(echo))
        assertEquals("+883-1-606-555-00100", CallLogDisplay.subtitleNumber(echo))
        assertEquals("Unknown caller", CallLogDisplay.title(entry(1, CallKind.MISSED, number = null)))
        assertEquals("Unknown number", CallLogDisplay.title(entry(1, CallKind.OUTGOING, number = null)))
        assertEquals("6065551235", CallLogDisplay.title(entry(1, number = "6065551235"))) // never completed: as dialled
    }

    @Test
    fun theVoiceLineIsTheCodecAndCounters() {
        val e = entry(1, connectedFor = 1_000).copy(codec = 1, voice = VoiceCounters(120, 2, 118, 3))
        assertEquals("Codec2 1200 · sent 120 · not sent 2 · received 118 · concealed 3", CallLogDisplay.voiceLine(e))
        assertNull(CallLogDisplay.voiceLine(entry(1)))
    }

    @Test
    fun talkBackReadsTheWholeRow() {
        assertEquals(
            "Missed call, Echo test (core 1), +883-1-606-555-00100, 14:05",
            CallLogDisplay.spoken(entry(1, CallKind.MISSED, number = "+883160655500100"), "14:05"),
        )
        assertEquals(
            "Outgoing call, +883-1-606-555-01235, 2:15, 09:00",
            CallLogDisplay.spoken(entry(1, connectedFor = 135_000), "09:00"),
        )
    }
}
