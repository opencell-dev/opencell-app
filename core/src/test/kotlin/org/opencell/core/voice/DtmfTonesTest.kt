package org.opencell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** The keypad's DTMF key tones (dial-and-recents spec §3.4). */
class DtmfTonesTest {
    private val rate = 16_000

    /** Relative energy of [pcm] at [freq] (Goertzel), normalised by length. */
    private fun power(pcm: ShortArray, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in pcm) {
            val s0 = x + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / pcm.size
    }

    @Test
    fun theQ23Table() {
        assertEquals(697.0 to 1209.0, DtmfTones.freqs('1'))
        assertEquals(770.0 to 1336.0, DtmfTones.freqs('5'))
        assertEquals(852.0 to 1477.0, DtmfTones.freqs('9'))
        assertEquals(941.0 to 1209.0, DtmfTones.freqs('*'))
        assertEquals(941.0 to 1336.0, DtmfTones.freqs('0'))
        assertEquals(941.0 to 1477.0, DtmfTones.freqs('#'))
        assertNull(DtmfTones.freqs('+'))
        assertNull(DtmfTones.pcm('A', rate))
    }

    @Test
    fun aKeySoundsItsTwoFrequenciesAndNoOthers() {
        val pcm = DtmfTones.pcm('5', rate)!!
        assertEquals(DtmfTones.KEY_MILLIS * rate / 1000, pcm.size)
        val on = minOf(power(pcm, 770.0), power(pcm, 1336.0))
        for (other in listOf(697.0, 852.0, 941.0, 1209.0, 1477.0)) {
            assertTrue("$other Hz", power(pcm, other) < on / 10)
        }
    }

    @Test
    fun itFadesInAndOutAndStaysBelowFullScale() {
        val pcm = DtmfTones.pcm('#', rate)!!
        assertEquals(0, pcm.first().toInt())
        assertTrue(abs(pcm.last().toInt()) < 2_000)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 5_000..20_000) // two sines at -12 dBm0 each
    }
}
