package org.opencell.core.loopback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What happened to one loopback probe. */
sealed interface ProbeOutcome {
    /** Written (or being written), no echo yet. */
    data object Pending : ProbeOutcome

    data class Echoed(val latency: Duration) : ProbeOutcome

    /** Accepted by the terminal but never echoed back within the test. */
    data object Lost : ProbeOutcome

    /** The terminal never accepted it (0x80 retries exhausted, not connected, ...). */
    data class SendFailed(val reason: String) : ProbeOutcome
}

data class ProbeResult(
    val seq: Int,
    val payload: ByteArray,
    val attempts: Int,
    val outcome: ProbeOutcome,
) {
    override fun equals(other: Any?): Boolean =
        other is ProbeResult && seq == other.seq && attempts == other.attempts &&
            outcome == other.outcome && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (seq * 31 + attempts) * 31 + outcome.hashCode()
}

/**
 * Summary of a loopback run. Latency is measured from the start of the
 * UP write the terminal accepted to the matching DOWN notification, so it
 * includes the BLE write round trip, the wait for the UL slot, the cell's
 * turnaround and the DOWN notification.
 */
data class LoopbackStats(
    val planned: Int,
    /** Probes written so far, minus those the terminal never accepted. */
    val sent: Int,
    val sendFailed: Int,
    val received: Int,
    /** Accepted and not echoed by the end of the test. */
    val lost: Int,
    /** Still waiting for an echo (only while running). */
    val pending: Int,
    /** DOWN payloads during the run that matched no probe. */
    val stray: Int,
    val mean: Duration?,
    val median: Duration?,
    val min: Duration?,
    val max: Duration?,
    val threshold: Duration,
    val withinThreshold: Int,
) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else 100.0 * lost / sent

    companion object {
        val DEFAULT_THRESHOLD = 500.milliseconds

        fun from(probes: List<ProbeResult>, stray: Int, threshold: Duration = DEFAULT_THRESHOLD, planned: Int = probes.size): LoopbackStats {
            val latencies = probes.mapNotNull { (it.outcome as? ProbeOutcome.Echoed)?.latency }.sorted()
            val failed = probes.count { it.outcome is ProbeOutcome.SendFailed }
            val lost = probes.count { it.outcome == ProbeOutcome.Lost }
            val pending = probes.count { it.outcome == ProbeOutcome.Pending }
            return LoopbackStats(
                planned = planned,
                sent = probes.size - failed,
                sendFailed = failed,
                received = latencies.size,
                lost = lost,
                pending = pending,
                stray = stray,
                mean = if (latencies.isEmpty()) null else latencies.fold(Duration.ZERO) { a, b -> a + b } / latencies.size,
                median = median(latencies),
                min = latencies.firstOrNull(),
                max = latencies.lastOrNull(),
                threshold = threshold,
                withinThreshold = latencies.count { it <= threshold },
            )
        }

        private fun median(sorted: List<Duration>): Duration? = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }
}
