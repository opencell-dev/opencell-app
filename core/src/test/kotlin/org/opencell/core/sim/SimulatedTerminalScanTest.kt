package org.opencell.core.sim

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.link.Connection
import org.opencell.core.link.ConnectionEvents
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.ChannelGrid
import org.opencell.core.protocol.Command
import org.opencell.core.protocol.ScanList
import org.opencell.core.protocol.ScanSource
import org.opencell.core.protocol.TerminalState
import org.opencell.core.protocol.TerminalStatus
import org.opencell.core.protocol.UserChannel

/**
 * The demo terminal's scan list at the wire level (contract v4, firmware parity): SCAN
 * carries every stored entry, only the search walk (the STATUS tail) dedupes, and SET_USER
 * refuses two entries with the same frequency and FIXED flag, like `oc_term_gatt_scan_command`.
 * [org.opencell.core.session.TerminalSessionTest] and [org.opencell.core.session.ChannelSessionTest]
 * cover this a layer up.
 */
class SimulatedTerminalScanTest {
    private val statuses = mutableListOf<TerminalStatus>()

    private val events = object : ConnectionEvents {
        override fun onDownlink(payload: ByteArray) = Unit
        override fun onStatus(raw: ByteArray) {
            TerminalStatus.decodeOrNull(raw)?.let { statuses += it }
        }
        override fun onEvent(raw: ByteArray) = Unit
        override fun onClosed(reason: String) = Unit
    }

    private suspend fun connected(sim: SimulatedTerminal): Connection =
        sim.connect(LinkTarget(SimulatedTerminal.ADDRESS, sim.name), events)

    /** SCAN keeps a user entry that duplicates a default channel; the walk (STATUS tail) skips the duplicate. */
    @Test
    fun scanKeepsTheShadowedEntryAndTheWalkExcludesIt() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val c = connected(sim) // still SEARCHing: no radio frame has ticked yet

        val dup = ChannelGrid.freqHz(0) // one of the six defaults
        assertEquals(WriteResult.Accepted, c.writeCommand(Command.ScanSetUser(listOf(UserChannel(dup))).encode()))

        val scan = ScanList.decode(c.readScan()!!)!!
        assertEquals(7, scan.entries.size) // 1 user + 6 defaults, none dropped
        assertEquals(2, scan.entries.count { it.freqHz == dup }) // shadowed, not removed
        assertEquals(listOf(ScanSource.USER, ScanSource.DEFAULT), scan.entries.filter { it.freqHz == dup }.map { it.source })

        sim.notifyStatus()
        val status = statuses.last()
        assertEquals(TerminalState.SEARCH, status.state)
        val tail = status.scan!!
        assertEquals(6, tail.len) // 7 entries minus the one the walk skips
        assertEquals(ScanSource.USER, tail.source) // the walk keeps the first occurrence
        assertEquals(dup, tail.freqKhz * 1000)

        c.close()
    }

    /** `oc_term_gatt_scan_command`: two SET_USER entries with the same frequency and FIXED flag are refused. */
    @Test
    fun setUserRefusesTwoEntriesWithTheSameFrequencyAndFixedFlag() = runTest {
        val sim = SimulatedTerminal(backgroundScope)
        val c = connected(sim)

        val dup = Command.ScanSetUser(
            listOf(UserChannel(917_250_000L, fixed = true), UserChannel(917_250_000L, fixed = true)),
        )
        assertEquals(WriteResult.BadArgument, c.writeCommand(dup.encode()))
        val scan = ScanList.decode(c.readScan()!!)!!
        assertTrue("the refused SET_USER changed nothing", scan.userEntries.isEmpty())

        // The same frequency with a different FIXED flag is not a duplicate.
        val notDup = Command.ScanSetUser(
            listOf(UserChannel(917_250_000L, fixed = true), UserChannel(917_250_000L, fixed = false)),
        )
        assertEquals(WriteResult.Accepted, c.writeCommand(notDup.encode()))
        assertEquals(2, ScanList.decode(c.readScan()!!)!!.userEntries.size)

        c.close()
    }
}
