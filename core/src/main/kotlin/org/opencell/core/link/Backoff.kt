package org.opencell.core.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Exponential backoff: [initial] before attempt 2, times [factor] after each failure, capped at [max]. */
data class Backoff(val initial: Duration, val factor: Double, val max: Duration) {
    init {
        require(initial.isPositive()) { "initial must be positive" }
        require(factor >= 1.0) { "factor must be >= 1" }
        require(max >= initial) { "max must be >= initial" }
    }

    /** Delay after the [failures]-th consecutive failure (1-based). */
    fun delayAfter(failures: Int): Duration {
        require(failures >= 1) { "failures must be >= 1" }
        var d = initial
        repeat(failures - 1) {
            d = (d * factor).coerceAtMost(max)
            if (d == max) return max
        }
        return d
    }

    companion object {
        /** Reconnecting a dropped BLE link: 1 s, 2 s, 4 s ... 30 s, forever. */
        val RECONNECT = Backoff(1.seconds, 2.0, 30.seconds)

        /** Retrying a 0x80 write: start at one radio frame (120 ms), cap at 1 s. */
        val NOT_NOW = Backoff(120.milliseconds, 2.0, 1.seconds)
    }
}

/** What to do after a failed UP write. */
sealed interface RetryDecision {
    data class RetryAfter(val delay: Duration) : RetryDecision
    data object GiveUp : RetryDecision
}

/**
 * The UP write retry policy from the GATT contract:
 * - ATT 0x80 ("not now") is retried with [backoff], at most [maxAttempts] attempts in total.
 * - ATT 0x0D ("too long") is permanent: never retried.
 * - Not connected / other GATT errors are not retried either: the link layer
 *   reconnects, and replaying an old payload afterwards is the caller's call.
 *
 * The default (8 attempts: 120, 240, 480, 960 ms then 1 s) gives up after ~4.8 s,
 * long enough for a RACH attach and grant, short enough to notice a dead cell.
 */
data class RetryPolicy(
    val backoff: Backoff = Backoff.NOT_NOW,
    val maxAttempts: Int = 8,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** [attempts] is how many writes were made so far, [last] the result of the latest one. */
    fun decide(attempts: Int, last: WriteResult): RetryDecision = when {
        last != WriteResult.NotNow -> RetryDecision.GiveUp
        attempts >= maxAttempts -> RetryDecision.GiveUp
        else -> RetryDecision.RetryAfter(backoff.delayAfter(attempts))
    }

    companion object {
        /** Worst-case time spent waiting between attempts before giving up. */
        fun RetryPolicy.totalBackoff(): Duration =
            (1 until maxAttempts).fold(Duration.ZERO) { acc, n -> acc + backoff.delayAfter(n) }
    }
}
