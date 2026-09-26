package org.opencell.core.link

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencell.core.link.RetryPolicy.Companion.totalBackoff
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    private val policy = RetryPolicy()

    @Test
    fun notNowBacksOffFromOneFrameAndCapsAtOneSecond() {
        val delays = (1..7).map { (policy.decide(it, WriteResult.NotNow) as RetryDecision.RetryAfter).delay }
        assertEquals(
            listOf(120, 240, 480, 960, 1000, 1000, 1000).map { it.milliseconds },
            delays,
        )
    }

    @Test
    fun notNowGivesUpAfterMaxAttempts() {
        assertEquals(RetryDecision.GiveUp, policy.decide(8, WriteResult.NotNow))
        assertEquals(RetryDecision.GiveUp, RetryPolicy(maxAttempts = 1).decide(1, WriteResult.NotNow))
        assertEquals(4800.milliseconds, policy.totalBackoff())
    }

    @Test
    fun tooLongIsNeverRetried() {
        assertEquals(RetryDecision.GiveUp, policy.decide(1, WriteResult.TooLong))
    }

    @Test
    fun otherFailuresAreNotRetried() {
        assertEquals(RetryDecision.GiveUp, policy.decide(1, WriteResult.NotConnected))
        assertEquals(RetryDecision.GiveUp, policy.decide(1, WriteResult.Failed(133, "GATT_ERROR")))
        assertEquals(RetryDecision.GiveUp, policy.decide(1, WriteResult.Accepted))
    }

    @Test
    fun gattStatusMapsToTheContract() {
        assertEquals(WriteResult.Accepted, WriteResult.fromGattStatus(0))
        assertEquals(WriteResult.NotNow, WriteResult.fromGattStatus(0x80))
        assertEquals(WriteResult.TooLong, WriteResult.fromGattStatus(0x0D))
        assertEquals(WriteResult.Failed(133, "GATT status 0x85"), WriteResult.fromGattStatus(133))
    }

    @Test
    fun reconnectBackoffDoublesToThirtySeconds() {
        val b = Backoff.RECONNECT
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, (1..7).map { b.delayAfter(it) })
        assertEquals(30.seconds, b.delayAfter(1000))
    }
}
