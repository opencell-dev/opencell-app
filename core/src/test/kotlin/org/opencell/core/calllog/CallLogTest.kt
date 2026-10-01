package org.opencell.core.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.phone.Direction
import org.opencell.core.phone.FinishedCall
import org.opencell.core.protocol.EndCause
import org.opencell.core.voice.VoiceCounters

/** The call log: entries, kinds, the cap, persistence and the missed-call badge (spec §4.2-4.4). */
class CallLogTest {
    private val me = "+883160655501234"
    private val peer = "+883160655500100"

    private fun outgoing(at: Long, connected: Boolean = true, cause: Int? = EndCause.NORMAL.code) =
        FinishedCall(Direction.OUTGOING, peer, me, at, if (connected) at + 2_000 else null, at + 60_000, cause, 1, rejected = false)

    private fun incoming(at: Long, connected: Boolean = false, rejected: Boolean = false) =
        FinishedCall(Direction.INCOMING, peer, me, at, if (connected) at + 1_000 else null, at + 20_000, EndCause.NORMAL.code, null, rejected)

    @Test
    fun kindsFollowDirectionConnectionAndRejection() {
        val log = CallLog()
        log.record(outgoing(1_000), null)
        log.record(incoming(2_000, connected = true), null)
        log.record(incoming(3_000), null)
        log.record(incoming(4_000, rejected = true), null)
        log.record(FinishedCall(null, null, me, 5_000, 5_100, 9_000, null, null, false), null)
        assertEquals(
            listOf(CallKind.UNKNOWN, CallKind.REJECTED, CallKind.MISSED, CallKind.INCOMING, CallKind.OUTGOING),
            log.entries.value.map { it.kind },
        )
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), log.entries.value.map { it.id })
    }

    @Test
    fun durationIsConnectedTimeAndVoiceIsKeptOnlyForConnectedCalls() {
        val log = CallLog()
        val v = VoiceCounters(sent = 120, notSent = 2, received = 118, concealed = 3)
        val answered = log.record(outgoing(1_000), v)
        assertEquals(58_000L, answered.durationMillis)
        assertEquals(v, answered.voice)
        val busy = log.record(outgoing(100_000, connected = false, cause = EndCause.BUSY.code), VoiceCounters())
        assertNull(busy.durationMillis)
        assertNull(busy.voice)
    }

    /** A national form dialled before the terminal's number was known is completed when it can be. */
    @Test
    fun aNationalPeerIsCompletedFromTheTerminalsNumber() {
        val log = CallLog()
        val e = log.record(FinishedCall(Direction.OUTGOING, "6065550100", me, 1, null, 2, 2, null, false), null)
        assertEquals("+883160655500100", e.number)
        val unknownHome = log.record(FinishedCall(Direction.OUTGOING, "6065550100", null, 3, null, 4, 2, null, false), null)
        assertEquals("6065550100", unknownHome.number)
    }

    @Test
    fun theOldestGoFirstPastTheCap() {
        val log = CallLog(cap = 3)
        for (i in 1..5) log.record(outgoing(i * 1_000L), null)
        assertEquals(listOf(5_000L, 4_000L, 3_000L), log.entries.value.map { it.startedAt })
    }

    @Test
    fun itSurvivesARestart() {
        val store = CallLogStore.inMemory()
        val log = CallLog(store)
        log.record(outgoing(1_000), VoiceCounters(1, 2, 3, 4))
        log.record(incoming(2_000), null)
        val again = CallLog(store)
        assertEquals(log.entries.value, again.entries.value)
        assertEquals(1, again.unseenMissed.value)
        assertEquals(3L, again.record(outgoing(3_000), null).id)
    }

    @Test
    fun missedCallsAreUnseenUntilRecentsIsShown() {
        val store = CallLogStore.inMemory()
        val log = CallLog(store)
        log.record(incoming(1_000), null)
        log.record(incoming(2_000), null)
        log.record(incoming(3_000, rejected = true), null)
        log.record(outgoing(4_000), null)
        assertEquals(2, log.unseenMissed.value)
        log.markMissedSeen()
        assertEquals(0, log.unseenMissed.value)
        assertTrue(log.entries.value.all { it.seen })
        assertEquals(0, CallLog(store).unseenMissed.value)
    }

    @Test
    fun deleteAndClear() {
        val store = CallLogStore.inMemory()
        val log = CallLog(store)
        val a = log.record(incoming(1_000), null)
        log.record(outgoing(2_000), null)
        log.delete(a.id)
        assertEquals(listOf(2L), log.entries.value.map { it.id })
        assertEquals(0, log.unseenMissed.value)
        log.delete(99) // not there: nothing happens
        assertEquals(1, log.entries.value.size)
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertNull(store.read())
    }

    @Test
    fun theTextFormRoundTripsEveryField() {
        val entries = listOf(
            CallLogEntry(7, CallKind.OUTGOING, peer, 1_000, 3_000, 61_000, 0, 1, VoiceCounters(120, 2, 118, 3), seen = true),
            CallLogEntry(6, CallKind.MISSED, null, 500, null, 900, null, null, null, seen = false),
            CallLogEntry(5, CallKind.UNKNOWN, "6065550100", 1, 2, 3, 77, 9, VoiceCounters(), seen = true),
        )
        val text = CallLogCodec.encode(entries)
        assertEquals(
            "oc-calllog 1\n" +
                "7\tOUTGOING\t+883160655500100\t1000\t3000\t61000\t0\t1\t1\t120,2,118,3\n" +
                "6\tMISSED\t-\t500\t-\t900\t-\t-\t0\t-\n" +
                "5\tUNKNOWN\t6065550100\t1\t2\t3\t77\t9\t1\t0,0,0,0",
            text,
        )
        assertEquals(entries, CallLogCodec.decode(text))
    }

    @Test
    fun aDamagedLineIsSkippedNotTheWholeLog() {
        val text = "oc-calllog 1\n" +
            "2\tOUTGOING\t+883160655500100\t1000\t-\t2000\t2\t-\t1\t-\n" +
            "x\tOUTGOING\t+883160655500100\t1000\t-\t2000\t2\t-\t1\t-\n" + // bad id
            "3\tSIDEWAYS\t+883160655500100\t1000\t-\t2000\t2\t-\t1\t-\n" + // unknown kind
            "4\tMISSED\t+883 160\t1000\t-\t2000\t2\t-\t0\t-\n" + // not a number
            "5\tMISSED\t-\t1000\t-\t2000\t2\t-\t0\t1,2\n" + // three counters short
            "6\tMISSED\t-\t1000"
        assertEquals(listOf(2L), CallLogCodec.decode(text).map { it.id })
    }

    @Test
    fun anUnknownVersionReadsAsEmpty() {
        assertTrue(CallLogCodec.decode("oc-calllog 2\n1\tOUTGOING\t-\t1\t-\t2\t-\t-\t1\t-").isEmpty())
        assertTrue(CallLogCodec.decode(null).isEmpty())
        assertTrue(CallLog(CallLogStore.inMemory("garbage")).entries.value.isEmpty())
    }

    /** Two lines with one id (a damaged or edited store): the first (newest) is kept, so Recents' keys stay unique. */
    @Test
    fun duplicateIdsAreDroppedOnLoad() {
        val text = "oc-calllog 1\n" +
            "5\tOUTGOING\t+883160655500100\t3000\t-\t4000\t2\t-\t1\t-\n" +
            "5\tMISSED\t-\t2000\t-\t2500\t-\t-\t0\t-\n" +
            "3\tINCOMING\t-\t1000\t1100\t1500\t0\t1\t1\t-"
        val decoded = CallLogCodec.decode(text)
        assertEquals(listOf(5L, 3L), decoded.map { it.id })
        assertEquals(CallKind.OUTGOING, decoded.first().kind)
        val log = CallLog(CallLogStore.inMemory(text))
        assertEquals(0, log.unseenMissed.value)
        log.delete(5)
        assertEquals(listOf(3L), log.entries.value.map { it.id })
    }

    /**
     * A log written by a newer version (read after a downgrade) is left exactly as it is:
     * this version shows nothing from it and never writes over it, so upgrading again finds it whole.
     */
    @Test
    fun aNewerVersionsLogIsKeptUntouched() {
        val newer = "oc-calllog 2\nsomething\tnewer"
        val store = CallLogStore.inMemory(newer)
        val log = CallLog(store)
        assertTrue(log.entries.value.isEmpty())
        log.record(outgoing(1_000), null)
        log.record(incoming(2_000), null)
        log.markMissedSeen()
        log.delete(1)
        log.clear()
        assertEquals(newer, store.read())
        log.record(outgoing(3_000), null)
        assertEquals(1, log.entries.value.size) // still works for this run, in memory
        assertEquals(newer, store.read())
    }

    /** Text that isn't a call log at all (no header) is replaced by the next change. */
    @Test
    fun garbageIsReplacedByTheNextChange() {
        val store = CallLogStore.inMemory("garbage")
        CallLog(store).record(outgoing(1_000), null)
        assertTrue(store.read()!!.startsWith(CallLogCodec.HEADER + "\n"))
    }
}
