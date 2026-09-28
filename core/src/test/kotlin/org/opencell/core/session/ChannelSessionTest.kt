package org.opencell.core.session

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeLink
import org.opencell.core.link.LinkState
import org.opencell.core.link.WriteResult
import org.opencell.core.protocol.Hex
import org.opencell.core.protocol.ScanEntry
import org.opencell.core.protocol.ScanList
import org.opencell.core.protocol.UserChannel

class ChannelSessionTest {
    private fun hex(b: ByteArray) = Hex.format(b)

    private fun list(vararg user: Long) = ScanList(
        modeCode = 1, fallbackAfter = 2, fallbackChunk = 13, netVer = 0,
        entries = user.map { ScanEntry(it, fixed = false, sourceCode = 2, active = true) } +
            ScanEntry(902_250_000L, fixed = false, sourceCode = 5, active = true),
    )

    @Test
    fun readsTheListWhenConnected() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource).apply { scanList = list(917_250_000L) }
        val ch = ChannelSession(link, backgroundScope)
        runCurrent()
        assertEquals(list(917_250_000L), ch.list.value)
        assertNull(ch.problem.value)
        link.stateFlow.value = LinkState.Disconnected
        runCurrent()
        assertNull(ch.list.value)
    }

    @Test
    fun firmwareWithoutScanSaysSo() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        val ch = ChannelSession(link, backgroundScope)
        runCurrent()
        assertNull(ch.list.value)
        assertEquals(ChannelSession.NO_SCAN, ch.problem.value)
    }

    /** SET_USER carries the whole list: the old entries, then the new one; then SCAN is read again. */
    @Test
    fun addAndRemoveSendTheWholeUserList() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource).apply { scanList = list(907_250_000L) }
        val ch = ChannelSession(link, backgroundScope)
        runCurrent()
        ch.addUser(UserChannel(917_250_000L, fixed = true))
        runCurrent()
        assertEquals("07 01 02 50 89 13 36 00 d0 1f ac 36 01", hex(link.commands.last()))
        assertEquals(2, link.scanReads)
        ch.addUser(UserChannel(907_250_000L, fixed = true)) // the same frequency again: replaced, not added
        runCurrent()
        assertEquals("07 01 01 50 89 13 36 01", hex(link.commands.last()))
        ch.removeUser(907_250_000L)
        runCurrent()
        assertEquals("07 01 00", hex(link.commands.last()))
    }

    @Test
    fun aFifthChannelIsRefusedHere() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
            .apply { scanList = list(903_250_000L, 905_250_000L, 907_250_000L, 909_250_000L) }
        val ch = ChannelSession(link, backgroundScope)
        runCurrent()
        ch.addUser(UserChannel(917_250_000L))
        runCurrent()
        assertTrue(link.commands.isEmpty())
        assertEquals(ChannelSession.TOO_MANY, ch.problem.value)
    }

    @Test
    fun fallbackForgetAndRefusals() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource).apply { scanList = list() }
        val ch = ChannelSession(link, backgroundScope)
        runCurrent()
        ch.setFallback(ScanList.NEVER, 52)
        runCurrent()
        assertEquals("07 02 0f 34", hex(link.commands.last()))
        ch.forgetLearned()
        runCurrent()
        assertEquals("07 03", hex(link.commands.last()))
        link.commandResults += WriteResult.BadArgument
        ch.setFallback(2, 13)
        runCurrent()
        assertEquals(ChannelSession.REFUSED, ch.problem.value)
        assertEquals(3, link.scanReads) // the refusal read nothing again
    }
}
