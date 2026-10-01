package org.opencell.core.session

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.link.LinkTarget
import org.opencell.core.loopback.LoopbackConfig
import org.opencell.core.protocol.RegMode
import org.opencell.core.protocol.ScanSource
import org.opencell.core.protocol.TerminalState
import org.opencell.core.protocol.UserChannel
import org.opencell.core.sim.SimulatedTerminal

/** End to end on virtual time: session -> link manager -> simulated terminal and echoing cell. */
class TerminalSessionTest {
    @Test
    fun simulatedTerminalAttaches() = runTest {
        val sim = SimulatedTerminal(backgroundScope, mode = RegMode.PART97)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)
        assertEquals(TerminalState.GRANTED, session.link.status.value?.state)
        assertEquals(0x76AD0488L, session.link.status.value?.tmid)
        session.disconnect()
        advanceUntilIdle()
    }

    @Test
    fun loopbackIsRefusedOutsideACallOnAPart97TerminalToo() = runTest {
        // Decision #25 (2026-10-01): the Part 97 out-of-call diagnostic
        // loopback is gone. UP is refused with 0x80 outside a connected call
        // in every mode now (see upIsRefusedOutsideACallInPart15 below, which
        // pins the same rule on the console's send path in Part 15).
        val sim = SimulatedTerminal(backgroundScope, mode = RegMode.PART97)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)

        assertNull(session.startLoopback(LoopbackConfig(count = 1)))
        advanceTimeBy(6_000) // 8 attempts with backoff, ~4.8 s, plus BLE delay
        val s = session.loopback.value!!.stats
        assertEquals(0, s.sent)
        assertEquals(1, s.sendFailed)
        assertEquals(0, s.received)
        session.disconnect()
        advanceUntilIdle()
    }

    /** The demo terminal keeps a scan list like the firmware (contract v4), and the session edits it. */
    @Test
    fun simulatedTerminalScanList() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)
        assertEquals("Channel 903.25 MHz", session.link.status.value?.scanLabel)
        session.channels.refresh()
        advanceTimeBy(100)
        val first = session.channels.list.value!!
        assertEquals(ScanSource.LAST, first.entries[0].source) // the cell it attached to
        assertEquals(SimulatedTerminal.ANCHOR_HZ, first.entries[0].freqHz)
        // SCAN carries every stored entry: all six defaults are there, even though 903.25 (channel 2)
        // duplicates the LAST entry above; only the search walk dedupes that away.
        assertEquals(6, first.entries.count { it.source == ScanSource.DEFAULT })
        assertEquals(2, first.entries.count { it.freqHz == SimulatedTerminal.ANCHOR_HZ }) // LAST + its DEFAULT twin

        session.channels.addUser(UserChannel(917_250_000L))
        advanceTimeBy(100)
        assertEquals(listOf(917_250_000L), session.channels.list.value!!.userEntries.map { it.freqHz })
        session.channels.addUser(UserChannel(922_250_000L, fixed = true)) // Part 15: kept, not used
        advanceTimeBy(100)
        assertEquals(false, session.channels.list.value!!.userEntries.last().active)

        session.channels.setUser(listOf(UserChannel(917_300_000L)))
        advanceTimeBy(100)
        assertEquals(ChannelSession.REFUSED, session.channels.problem.value)
        assertEquals(2, session.channels.list.value!!.userEntries.size) // unchanged
        session.disconnect()
        advanceUntilIdle()
    }

    /**
     * SCAN keeps every stored entry (only the search walk dedupes), so a user channel equal to
     * the last serving cell still counts as the user's: "Your channels" must not lose it.
     */
    @Test
    fun aUserChannelThatShadowsLastStillCounts() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000) // attached: lastServing == ANCHOR_HZ
        session.channels.setUser(listOf(UserChannel(SimulatedTerminal.ANCHOR_HZ), UserChannel(917_250_000L)))
        advanceTimeBy(100)
        val list = session.channels.list.value!!
        assertEquals(2, list.userEntries.size) // both kept, even though one shadows `last`
        // last, the user entry that duplicates it, and its DEFAULT twin (ANCHOR_HZ is channel 2): all three in SCAN.
        assertEquals(3, list.entries.count { it.freqHz == SimulatedTerminal.ANCHOR_HZ })
        session.disconnect()
        advanceUntilIdle()
    }

    @Test
    fun loopbackNeedsAConnection() = runTest {
        val session = TerminalSession(SimulatedTerminal(backgroundScope), backgroundScope, testScheduler.timeSource)
        assertEquals("Not connected", session.startLoopback(LoopbackConfig()))
    }

    @Test
    fun upIsRefusedOutsideACallInPart15() = runTest {
        // The media gate (2026-09-30-voice-codec2/media-gate.md §3): in Part 15,
        // UP is refused with 0x80 anywhere outside a connected call, even granted
        // and even with no key material issue — there's simply no key for it yet.
        val sim = SimulatedTerminal(backgroundScope, mode = RegMode.PART15)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)
        assertEquals(TerminalState.GRANTED, session.link.status.value?.state)

        session.send("HELLO".encodeToByteArray())
        advanceTimeBy(6_000) // 8 attempts with backoff, ~4.8 s, plus BLE delay
        val last = session.console.entries.value.last()
        assertEquals(ConsoleKind.ERROR, last.kind)
        assertEquals("UP 5 B: not now (0x80) after 8 attempts", last.text)
        assertTrue(session.console.entries.value.none { it.kind == ConsoleKind.DOWN })
        session.disconnect()
    }

    @Test
    fun oversizeConsoleWriteIsRefusedByTheTerminal() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource)
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)
        session.send(ByteArray(21), enforceLimit = false)
        runCurrent()
        advanceTimeBy(100)
        val last = session.console.entries.value.last()
        assertEquals(ConsoleKind.ERROR, last.kind)
        assertEquals("UP 21 B: too long (0x0D) after 1 attempt", last.text)
        session.disconnect()
    }
}
