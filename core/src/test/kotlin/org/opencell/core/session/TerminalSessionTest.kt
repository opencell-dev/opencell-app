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
import org.opencell.core.protocol.TerminalState
import org.opencell.core.sim.SimulatedTerminal

/** End to end on virtual time: session -> link manager -> simulated terminal and echoing cell. */
class TerminalSessionTest {
    @Test
    fun simulatedTerminalAttachesAndEchoes() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val session = TerminalSession(sim, backgroundScope, testScheduler.timeSource, { testScheduler.currentTime })
        session.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name))
        advanceTimeBy(3_000)
        assertEquals(TerminalState.GRANTED, session.link.status.value?.state)
        assertEquals(0x76AD0488L, session.link.status.value?.tmid)

        session.send("HELLO".encodeToByteArray())
        advanceTimeBy(1_000)
        val down = session.console.entries.value.filter { it.kind == ConsoleKind.DOWN }
        assertEquals("HELLO", down.single().payload?.decodeToString())

        assertNull(session.startLoopback(LoopbackConfig(count = 5)))
        advanceTimeBy(10_000)
        val s = session.loopback.value!!.stats
        assertEquals(5, s.received)
        assertEquals(5, s.withinThreshold) // 15 ms BLE + <=120 ms frame wait + 300 ms cell
        assertTrue(s.max!!.inWholeMilliseconds in 315..435)
        session.disconnect()
        advanceUntilIdle()
    }

    @Test
    fun loopbackNeedsAConnection() = runTest {
        val session = TerminalSession(SimulatedTerminal(backgroundScope), backgroundScope, testScheduler.timeSource)
        assertEquals("Not connected", session.startLoopback(LoopbackConfig()))
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
