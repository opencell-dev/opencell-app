package org.opencell.core.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeConnector
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.TerminalEvent
import org.opencell.core.protocol.TerminalState
import org.opencell.core.protocol.TerminalStatus
import kotlin.time.Duration.Companion.seconds

class LinkManagerTest {
    private val target = LinkTarget("AA:BB:CC:DD:EE:FF", "OpenCell-76AD0488")
    private val status = TerminalStatus(4, 0, 2, -52, 50, 0x76AD0488, 1000, 7)

    private fun TestScope.manager(connector: FakeConnector) =
        LinkManager(connector, backgroundScope, Backoff.RECONNECT, testScheduler.timeSource) { testScheduler.currentTime }

    @Test
    fun connectsAndReadsStatus() = runTest {
        val connector = FakeConnector().apply { statusBytes = status.encode() }
        val m = manager(connector)
        m.connect(target)
        runCurrent()
        assertEquals(LinkState.Connecting(target, 1), m.state.value)
        advanceTimeBy(101)
        assertEquals(LinkState.Connected(target, 247), m.state.value)
        assertEquals(TerminalState.GRANTED, m.status.value?.state)
    }

    @Test
    fun reconnectsAfterTheLinkDrops() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        m.connect(target)
        advanceTimeBy(101)
        connector.connections[0].drop("supervision timeout")
        runCurrent()
        val waiting = m.state.value as LinkState.WaitingToReconnect
        assertEquals(1, waiting.failures)
        assertEquals(1.seconds, waiting.delay)
        assertEquals("supervision timeout", waiting.reason)
        assertTrue("old connection closed", connector.connections[0].closed)
        assertEquals(WriteResult.NotConnected, m.writeUp(byteArrayOf(1)))

        advanceTimeBy(1101)
        assertEquals(LinkState.Connected(target, 247), m.state.value)
        assertEquals(2, connector.connections.size)
        assertEquals(WriteResult.Accepted, m.writeUp(byteArrayOf(1)))
        assertEquals(1, connector.connections[1].writes.size)
    }

    @Test
    fun failedConnectsBackOffExponentially() = runTest {
        val connector = FakeConnector().apply { failNext = 3 }
        val m = manager(connector)
        m.connect(target)
        // attempt 1 fails at 100 ms, then waits 1 s, 2 s, 4 s between attempts
        advanceTimeBy(101)
        assertEquals(1.seconds, (m.state.value as LinkState.WaitingToReconnect).delay)
        advanceTimeBy(1100)
        assertEquals(2.seconds, (m.state.value as LinkState.WaitingToReconnect).delay)
        advanceTimeBy(2100)
        assertEquals(4.seconds, (m.state.value as LinkState.WaitingToReconnect).delay)
        advanceTimeBy(4100)
        assertTrue(m.state.value.isConnected)
        assertEquals(4, connector.attempts)

        // A good connection resets the backoff.
        connector.connections.single().drop()
        runCurrent()
        assertEquals(1.seconds, (m.state.value as LinkState.WaitingToReconnect).delay)
    }

    @Test
    fun disconnectStopsReconnecting() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        m.connect(target)
        advanceTimeBy(101)
        m.disconnect()
        assertEquals(LinkState.Disconnected, m.state.value)
        assertTrue(connector.connections.single().closed)
        advanceTimeBy(120_000)
        assertEquals(1, connector.attempts)
        assertEquals(LinkState.Disconnected, m.state.value)
    }

    @Test
    fun connectingElsewhereDropsTheCurrentLink() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        m.connect(target)
        advanceTimeBy(101)
        val other = LinkTarget("11:22:33:44:55:66", null)
        m.connect(other)
        advanceTimeBy(101)
        assertTrue(connector.connections[0].closed)
        assertEquals(LinkState.Connected(other, 247), m.state.value)
    }

    @Test
    fun forwardsDownlinkAndStatusNotifications() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        val received = mutableListOf<Downlink>()
        backgroundScope.launchCollect(m, received)
        m.connect(target)
        advanceTimeBy(101)
        assertNull(m.status.value)
        val c = connector.connections.single()
        c.events.onStatus(status.encode())
        c.events.onStatus(ByteArray(3)) // malformed: ignored
        c.events.onDownlink("HELLO".encodeToByteArray())
        runCurrent()
        assertEquals(status, m.status.value)
        assertEquals("HELLO", received.single().payload.decodeToString())
        assertEquals(testScheduler.currentTime, received.single().wallMillis)
    }

    @Test
    fun forwardsDecodedEvents() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        val events = mutableListOf<TerminalEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { m.events.collect { events += it } }
        m.connect(target)
        advanceTimeBy(101)
        val c = connector.connections.single()
        c.events.onEvent(byteArrayOf(0x06, 0, 0, 0, 7))
        c.events.onEvent(byteArrayOf(0x7F))
        runCurrent()
        assertEquals(listOf(TerminalEvent.Ringing(7), TerminalEvent.Unknown("7f", 0x7F)), events)
    }

    /** M6: EVENT and STATUS notifications share one stream, in arrival order (malformed STATUS dropped). */
    @Test
    fun inputsKeepEventsAndStatusInArrivalOrder() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        val inputs = mutableListOf<LinkInput>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { m.inputs.collect { inputs += it } }
        m.connect(target)
        advanceTimeBy(101)
        val c = connector.connections.single()
        c.events.onEvent(byteArrayOf(0x06, 0, 0, 0, 7))
        c.events.onStatus(status.encode())
        c.events.onStatus(ByteArray(3))
        c.events.onEvent(byteArrayOf(0x08, 0, 0, 0, 7, 0))
        runCurrent()
        assertEquals(
            listOf(LinkInput.Event(TerminalEvent.Ringing(7)), LinkInput.Status(status), LinkInput.Event(TerminalEvent.Ended(7, 0))),
            inputs,
        )
    }

    @Test
    fun commandsGoToTheConnectionAndReportAttErrors() = runTest {
        val connector = FakeConnector()
        val m = manager(connector)
        assertEquals(WriteResult.NotConnected, m.writeCommand(Command.Answer.encode()))
        m.connect(target)
        advanceTimeBy(101)
        val c = connector.connections.single()
        c.commandResults += listOf(WriteResult.NotNow, WriteResult.BadArgument)
        assertEquals(WriteResult.NotNow, m.writeCommand(Command.Answer.encode()))
        assertEquals(WriteResult.BadArgument, m.writeCommand(Command.Dial("+883").encode()))
        assertEquals(WriteResult.Accepted, m.writeCommand(Command.Deactivate.encode()))
        assertEquals(listOf("03", "02 2b 38 38 33", "06 a5"), c.commands.map { Hex.format(it) })
        assertTrue("commands never go to UP", c.writes.isEmpty())
    }

    @Test
    fun attErrorCodesMapToResults() {
        assertEquals(WriteResult.Accepted, WriteResult.fromGattStatus(0))
        assertEquals(WriteResult.NotNow, WriteResult.fromGattStatus(0x80))
        assertEquals(WriteResult.TooLong, WriteResult.fromGattStatus(0x0D))
        assertEquals(WriteResult.BadArgument, WriteResult.fromGattStatus(0x81))
        assertTrue(WriteResult.fromGattStatus(133) is WriteResult.Failed)
        assertEquals(RetryDecision.GiveUp, RetryPolicy().decide(1, WriteResult.BadArgument))
    }

    private fun CoroutineScope.launchCollect(m: LinkManager, into: MutableList<Downlink>) =
        launch(start = CoroutineStart.UNDISPATCHED) { m.downlink.collect { into += it } }
}
