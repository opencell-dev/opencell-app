package org.opencell.core.loopback

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeLink
import org.opencell.core.link.UplinkSender
import org.opencell.core.link.WriteResult
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LoopbackRunnerTest {
    @Test
    fun benchLikeRunAllWithinHalfASecond() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.writeLatency = 20.milliseconds
        link.echoDelay = { 410.milliseconds }
        val runner = LoopbackRunner(link, UplinkSender(link), testScheduler.timeSource)
        val report = runner.run(LoopbackConfig(count = 10))
        val s = report.stats
        assertFalse(report.running)
        assertEquals(10, s.sent)
        assertEquals(10, s.received)
        assertEquals(0, s.lost)
        assertEquals(430.milliseconds, s.mean) // write round trip + echo
        assertEquals(430.milliseconds, s.max)
        assertEquals(10, s.withinThreshold)
        // One write per second, tagged with the sequence number.
        assertEquals((0 until 10).map { "HELLO#%02x".format(it) }, link.writes.map { it.decodeToString() })
        val gaps = link.writeTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.toString(), gaps.all { it == 1.seconds })
    }

    @Test
    fun countsLateLostAndFailedProbes() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        // accepted writes: #0 fast, #1 slow, #2 dropped, #3 fast
        link.echoDelay = { n -> listOf(300.milliseconds, 700.milliseconds, null, 450.milliseconds)[n] }
        // probe 2 is refused permanently (0x0D), so it never counts as sent
        link.results += listOf(WriteResult.Accepted, WriteResult.Accepted, WriteResult.TooLong, WriteResult.Accepted, WriteResult.Accepted)
        val runner = LoopbackRunner(link, UplinkSender(link), testScheduler.timeSource)
        val s = runner.run(LoopbackConfig(count = 5, echoTimeout = 2.seconds)).stats
        assertEquals(4, s.sent)
        assertEquals(1, s.sendFailed)
        assertEquals(3, s.received)
        assertEquals(1, s.lost)
        assertEquals(2, s.withinThreshold)
        assertEquals(300.milliseconds, s.min)
        assertEquals(700.milliseconds, s.max)
        assertEquals(1450.milliseconds / 3, s.mean)
    }

    @Test
    fun retriedProbeLatencyStartsAtTheAcceptedWrite() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.results += listOf(WriteResult.NotNow, WriteResult.NotNow, WriteResult.Accepted)
        link.echoDelay = { 400.milliseconds }
        val runner = LoopbackRunner(link, UplinkSender(link), testScheduler.timeSource)
        val report = runner.run(LoopbackConfig(count = 1))
        assertEquals(3, report.probes.single().attempts)
        assertEquals(ProbeOutcome.Echoed(400.milliseconds), report.probes.single().outcome)
    }

    @Test
    fun untaggedEchoesMatchOldestFirstAndStraysAreCounted() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.echoDelay = { 1500.milliseconds } // echo arrives after the next probe is sent
        val runner = LoopbackRunner(link, UplinkSender(link), testScheduler.timeSource)
        val job = async { runner.run(LoopbackConfig(count = 3, tagSequence = false)) }
        advanceTimeBy(200)
        link.emitDown("noise".encodeToByteArray())
        val s = job.await().stats
        assertEquals(3, s.received)
        assertEquals(1500.milliseconds, s.max)
        assertEquals(1, s.stray)
    }

    @Test
    fun reportsProgressWhileRunning() = runTest {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        link.echoDelay = { 400.milliseconds }
        val runner = LoopbackRunner(link, UplinkSender(link), testScheduler.timeSource)
        val updates = mutableListOf<LoopbackReport>()
        runner.run(LoopbackConfig(count = 2)) { updates += it }
        assertTrue(updates.first().running)
        assertEquals(ProbeOutcome.Pending, updates.first().probes.single().outcome)
        assertFalse(updates.last().running)
        assertEquals(2, updates.last().stats.received)
    }
}
