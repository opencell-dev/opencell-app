package org.opencell.core.voice

/** What the speaker plays for the next 120 ms block. */
sealed interface Playout {
    /** A received block. */
    class Play(val payload: ByteArray) : Playout

    /** Nothing to play: the last good block again at [gain] (loss concealment). */
    class Conceal(val payload: ByteArray, val gain: Float) : Playout

    data object Silence : Playout
}

/** The jitter buffer's counters since the call's voice started. */
data class JitterCounts(
    val played: Int = 0,
    val concealed: Int = 0,
    /** Blocks dropped because too many were waiting (latency trimmed). */
    val trimmed: Int = 0,
    /** Blocks dropped because the buffer was full. */
    val overflowed: Int = 0,
    /** Times playout ran dry and waited for [JitterBuffer.target] blocks again. */
    val rebuffered: Int = 0,
)

/**
 * The downlink's jitter buffer (voice spec §5.4). Blocks arrive about every
 * 120 ms (one per radio frame) with BLE jitter; the speaker takes one every
 * 120 ms by its own clock ([poll]). No sequence number reaches the app, so a
 * lost block is found from arrival times: a gap of about n x 120 ms means
 * n - 1 blocks were lost, and that many concealment markers go in first (at
 * most [maxConceal]; fewer if the speaker already concealed while it waited).
 *
 * - Playout starts, and restarts after running dry, once [target] blocks wait.
 * - Missing blocks are concealed by the last good block at -6 dB, then -12 dB;
 *   after [maxConceal] in a row the speaker is silent and the buffer refills.
 * - More than [target] + 1 blocks waiting at a poll are trimmed back to [target]
 *   (a burst after a BLE stall must not become lasting delay); more than [max]
 *   on arrival drops the oldest.
 *
 * Thread-safe: arrivals and polls come from different coroutines.
 */
class JitterBuffer(
    val target: Int = DEFAULT_TARGET,
    val max: Int = DEFAULT_MAX,
    val maxConceal: Int = DEFAULT_MAX_CONCEAL,
    private val blockMillis: Long = BlockCodec.BLOCK_MILLIS,
) {
    init {
        require(target in 1..max) { "target $target must be 1..$max" }
    }

    /** Waiting blocks; null is a lost block's concealment marker. */
    private val queue = ArrayDeque<ByteArray?>()
    private var lastArrival: Long? = null
    private var playing = false
    private var last: ByteArray? = null
    private var concealRun = 0
    private var drySinceArrival = 0
    private var counts = JitterCounts()

    @Synchronized
    fun counts(): JitterCounts = counts

    /** Blocks (and markers) waiting now. */
    @Synchronized
    fun depth(): Int = queue.size

    /** A received block, at monotonic [atMillis]. */
    @Synchronized
    fun offer(payload: ByteArray, atMillis: Long) {
        lastArrival?.let { prev ->
            val missing = ((atMillis - prev + blockMillis / 2) / blockMillis - 1).toInt() - drySinceArrival
            if (missing in 1..maxConceal) repeat(missing) { queue.addLast(null) }
        }
        lastArrival = atMillis
        drySinceArrival = 0
        queue.addLast(payload.copyOf())
        while (queue.size > max) {
            queue.removeFirst()
            counts = counts.copy(overflowed = counts.overflowed + 1)
        }
    }

    /** What to play for the next block. */
    @Synchronized
    fun poll(): Playout {
        if (!playing) {
            if (queue.size < target) return Playout.Silence
            playing = true
        }
        if (queue.size > target + 1) {
            while (queue.size > target) queue.removeFirst()
            counts = counts.copy(trimmed = counts.trimmed + 1)
        }
        if (queue.isEmpty()) {
            drySinceArrival++
            return conceal()
        }
        val head = queue.removeFirst() ?: return conceal()
        last = head
        concealRun = 0
        counts = counts.copy(played = counts.played + 1)
        return Playout.Play(head)
    }

    private fun conceal(): Playout {
        concealRun++
        val l = last
        if (l == null || concealRun > maxConceal) {
            if (queue.isEmpty()) {
                playing = false
                counts = counts.copy(rebuffered = counts.rebuffered + 1)
                concealRun = 0
                last = null
            }
            return Playout.Silence
        }
        counts = counts.copy(concealed = counts.concealed + 1)
        return Playout.Conceal(l, GAIN_STEP.pow(concealRun))
    }

    private fun Float.pow(n: Int): Float {
        var g = 1f
        repeat(n) { g *= this }
        return g
    }

    companion object {
        /** 240 ms of cushion (voice spec R11). */
        const val DEFAULT_TARGET = 2
        const val DEFAULT_MAX = 6
        const val DEFAULT_MAX_CONCEAL = 2

        /** -6 dB per concealed block. */
        const val GAIN_STEP = 0.5f
    }
}
