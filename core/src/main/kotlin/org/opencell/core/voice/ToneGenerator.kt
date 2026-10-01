package org.opencell.core.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PCM for one [Tone] at [sampleRate], sample-exact: a segment of n ms is
 * n x rate / 1000 samples wherever the block boundaries fall, and each tone
 * burst starts at phase 0. A tone that doesn't repeat is followed by silence
 * and reports [finished].
 */
class ToneGenerator(private val tone: Tone, private val sampleRate: Int = BlockCodec.SAMPLE_RATE) {
    private val lengths = tone.segments.map { it.millis * sampleRate / 1000 }
    private val cycle = lengths.sum()
    private val peak = FULL_SCALE * 10.0.pow((tone.levelDbm0 - DBM0_FULL_SCALE) / 20)
    private var pos = 0L

    /** A non-repeating tone has played to its end. */
    val finished: Boolean get() = !tone.repeat && pos >= cycle

    /** Fills [block] with the next samples. */
    fun fill(block: ShortArray) {
        for (i in block.indices) block[i] = next()
    }

    private fun next(): Short {
        if (!tone.repeat && pos >= cycle) return 0
        var at = (pos % cycle).toInt()
        pos++
        var seg = 0
        while (at >= lengths[seg]) {
            at -= lengths[seg]
            seg++
        }
        val freqs = tone.segments[seg].freqs
        if (freqs.isEmpty()) return 0
        val t = at.toDouble() / sampleRate
        return freqs.sumOf { peak * sin(2 * PI * it * t) }.roundToInt().toShort()
    }

    companion object {
        private const val FULL_SCALE = 32767.0

        /** A full-scale sine is +3.14 dBm0 (G.711 mu-law). */
        const val DBM0_FULL_SCALE = 3.14
    }
}
