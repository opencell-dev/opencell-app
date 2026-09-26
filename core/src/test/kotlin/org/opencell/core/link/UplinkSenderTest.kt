package org.opencell.core.link

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeLink
import org.opencell.core.protocol.PayloadCheck
import kotlin.time.Duration.Companion.milliseconds

class UplinkSenderTest {
    @Test
    fun acceptedFirstTime() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        val out = UplinkSender(link).send("HELLO".encodeToByteArray())
        assertEquals(SendOutcome.Sent(1), out)
        assertEquals("HELLO", link.writes.single().decodeToString())
    }

    @Test
    fun notNowIsRetriedWithBackoff() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.results += listOf(WriteResult.NotNow, WriteResult.NotNow)
        val start = testScheduler.currentTime
        val out = UplinkSender(link).send(byteArrayOf(1))
        assertEquals(SendOutcome.Sent(3), out)
        assertEquals(3, link.writes.size)
        assertEquals(120L + 240L, testScheduler.currentTime - start)
    }

    @Test
    fun notNowGivesUpAfterMaxAttempts() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        repeat(20) { link.results += WriteResult.NotNow }
        val out = UplinkSender(link, RetryPolicy(maxAttempts = 4)).send(byteArrayOf(1))
        assertEquals(SendOutcome.Failed(WriteResult.NotNow, 4), out)
        assertEquals(4, link.writes.size)
        assertEquals(120L + 240L + 480L, testScheduler.currentTime)
    }

    @Test
    fun tooLongFromTerminalIsNotRetried() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.results += WriteResult.TooLong
        val out = UplinkSender(link).send(ByteArray(21), enforceLimit = false)
        assertEquals(SendOutcome.Failed(WriteResult.TooLong, 1), out)
        assertEquals(21, link.writes.single().size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun oversizeIsRejectedLocallyByDefault() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        val out = UplinkSender(link).send(ByteArray(21))
        assertEquals(SendOutcome.Invalid(PayloadCheck.TooLong(21)), out)
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun emptyIsRejectedEvenWithoutLimit() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        assertEquals(SendOutcome.Invalid(PayloadCheck.Empty), UplinkSender(link).send(ByteArray(0), enforceLimit = false))
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun notConnectedFailsImmediately() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.results += WriteResult.NotConnected
        assertEquals(SendOutcome.Failed(WriteResult.NotConnected, 1), UplinkSender(link).send(byteArrayOf(1)))
    }

    @Test
    fun sendsAreSerializedInCallOrder() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.writeLatency = 10.milliseconds
        link.results += listOf(WriteResult.NotNow, WriteResult.NotNow) // first payload is held back
        val sender = UplinkSender(link)
        val a = async { sender.send(byteArrayOf(0xA)) }
        val b = async { sender.send(byteArrayOf(0xB)) }
        assertEquals(SendOutcome.Sent(3), a.await())
        assertEquals(SendOutcome.Sent(1), b.await())
        assertEquals(listOf(0xA, 0xA, 0xA, 0xB), link.writes.map { it.single().toInt() })
    }
}
