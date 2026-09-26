package org.opencell.core.loopback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.opencell.core.link.SendOutcome
import org.opencell.core.link.TerminalLink
import org.opencell.core.link.UplinkSender
import org.opencell.core.protocol.GattContract
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The bench loopback test: [count] UP writes, one every [interval], each
 * expected back on DOWN (a test cell echoes UL payloads on DL).
 *
 * With [tagSequence] each probe is `payload + "#" + 2 hex digits` so echoes are
 * matched to probes exactly; otherwise identical echoes are matched oldest-first.
 */
data class LoopbackConfig(
    val count: Int = 20,
    val interval: Duration = 1.seconds,
    val payload: ByteArray = "HELLO".encodeToByteArray(),
    val tagSequence: Boolean = true,
    /** How long to keep listening after the last probe. */
    val echoTimeout: Duration = 3.seconds,
    val threshold: Duration = LoopbackStats.DEFAULT_THRESHOLD,
) {
    /** A user-facing problem with this config, or null if it can run. */
    fun problem(): String? = when {
        count !in 1..MAX_COUNT -> "Count must be 1..$MAX_COUNT"
        interval < MIN_INTERVAL -> "Interval must be at least ${MIN_INTERVAL.inWholeMilliseconds} ms (one frame)"
        payload.isEmpty() -> "Payload is empty"
        tagSequence && payload.size > MAX_TAGGED_BASE ->
            "Payload is ${payload.size} bytes; with the sequence tag the limit is $MAX_TAGGED_BASE"
        payload.size > GattContract.MAX_PAYLOAD -> "Payload is ${payload.size} bytes; the limit is ${GattContract.MAX_PAYLOAD}"
        else -> null
    }

    fun payloadFor(seq: Int): ByteArray =
        if (tagSequence) payload + "#%02x".format(seq and 0xFF).encodeToByteArray() else payload

    override fun equals(other: Any?): Boolean =
        other is LoopbackConfig && count == other.count && interval == other.interval &&
            payload.contentEquals(other.payload) && tagSequence == other.tagSequence &&
            echoTimeout == other.echoTimeout && threshold == other.threshold

    override fun hashCode(): Int = ((count * 31 + interval.hashCode()) * 31 + payload.contentHashCode()) * 31 + tagSequence.hashCode()

    companion object {
        const val MAX_COUNT = 10_000
        const val TAG_LEN = 3
        const val MAX_TAGGED_BASE = GattContract.MAX_PAYLOAD - TAG_LEN
        val MIN_INTERVAL = GattContract.FRAME_MILLIS.milliseconds
    }
}

data class LoopbackReport(
    val config: LoopbackConfig,
    val probes: List<ProbeResult>,
    val stats: LoopbackStats,
    val running: Boolean,
)

class LoopbackRunner(
    private val link: TerminalLink,
    private val sender: UplinkSender,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private class Probe(val seq: Int, val payload: ByteArray) {
        var attempts = 0
        var sentAt: ComparableTimeMark? = null
        var latency: Duration? = null
        var failure: String? = null
    }

    /**
     * Runs the test and returns the final report. [onUpdate] gets a snapshot
     * after every probe and every echo. Cancel the calling coroutine to stop early.
     */
    suspend fun run(config: LoopbackConfig, onUpdate: (LoopbackReport) -> Unit = {}): LoopbackReport {
        config.problem()?.let { throw IllegalArgumentException(it) }
        val lock = Any()
        val probes = ArrayList<Probe>(config.count)
        var stray = 0
        val changes = MutableStateFlow(0L)

        fun report(running: Boolean): LoopbackReport = synchronized(lock) {
            val results = probes.map { p ->
                val outcome = when {
                    p.latency != null -> ProbeOutcome.Echoed(p.latency!!)
                    p.failure != null -> ProbeOutcome.SendFailed(p.failure!!)
                    running -> ProbeOutcome.Pending
                    else -> ProbeOutcome.Lost
                }
                ProbeResult(p.seq, p.payload, p.attempts, outcome)
            }
            LoopbackReport(config, results, LoopbackStats.from(results, stray, config.threshold, config.count), running)
        }

        fun unresolved(): Int = synchronized(lock) {
            probes.count { it.latency == null && it.failure == null }
        }

        return coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                link.downlink.collect { d ->
                    synchronized(lock) {
                        val p = probes.firstOrNull {
                            it.sentAt != null && it.latency == null && it.failure == null &&
                                it.payload.contentEquals(d.payload)
                        }
                        if (p == null) stray++ else p.latency = d.at - p.sentAt!!
                    }
                    changes.value++
                    onUpdate(report(running = true))
                }
            }
            val start = timeSource.markNow()
            for (i in 0 until config.count) {
                val wait = (start + config.interval * i) - timeSource.markNow()
                if (wait.isPositive()) delay(wait)
                val probe = Probe(i, config.payloadFor(i))
                synchronized(lock) { probes += probe }
                val outcome = sender.send(probe.payload) { attempt ->
                    synchronized(lock) {
                        probe.attempts = attempt
                        probe.sentAt = timeSource.markNow()
                    }
                }
                if (outcome !is SendOutcome.Sent) synchronized(lock) { probe.failure = outcome.label }
                changes.value++
                onUpdate(report(running = true))
            }
            withTimeoutOrNull(config.echoTimeout) {
                changes.first { unresolved() == 0 }
            }
            collector.cancel()
            report(running = false).also(onUpdate)
        }
    }
}
