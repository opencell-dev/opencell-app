package org.opencell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class ToneGeneratorTest {
    /** [seconds] of [tone], made 120 ms block by block as the player does. */
    private fun render(tone: Tone, seconds: Double): ShortArray {
        val gen = ToneGenerator(tone)
        val n = (seconds * 8000).toInt()
        val out = ShortArray(n + 960)
        val block = ShortArray(960)
        var at = 0
        while (at < n) {
            gen.fill(block)
            block.copyInto(out, at)
            at += 960
        }
        return out.copyOf(n)
    }

    private fun rms(x: ShortArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** Goertzel power of [f] in [x], normalised so a sine of peak A gives about A. */
    private fun level(x: ShortArray, f: Double): Double {
        val k = 2 * cos(2 * PI * f / 8000)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = v + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        return 2 * sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / x.size
    }

    /** Checks [pcm] against [tone]'s segments, sample-exact, for as many as fit. */
    private fun assertCadence(name: String, tone: Tone, pcm: ShortArray) {
        val peak = 32767 * Math.pow(10.0, (tone.levelDbm0 - 3.14) / 20)
        var at = 0
        var i = 0
        while (true) {
            val seg = tone.segments[i % tone.segments.size]
            val n = seg.millis * 8
            if (at + n > pcm.size) break
            val part = pcm.copyOfRange(at, at + n)
            if (seg.freqs.isEmpty()) {
                assertTrue("$name: segment $i (off) at sample $at is silent", part.all { it == 0.toShort() })
            } else {
                for (f in seg.freqs) {
                    val l = level(part, f)
                    assertTrue("$name: segment $i has $f Hz at ${l.toInt()}, want ${peak.toInt()}", l in 0.9 * peak..1.1 * peak)
                }
                assertTrue("$name: segment $i is not silent", rms(part) > 0.5 * peak)
                // The tone isn't at some other frequency (a wrong table entry would be).
                assertTrue("$name: segment $i has energy at 1000 Hz", seg.freqs.contains(1000.0) || level(part, 1000.0) < 0.1 * peak)
            }
            at += n
            i++
            if (!tone.repeat && i == tone.segments.size) {
                assertTrue("$name: silent after it ends", pcm.copyOfRange(at, pcm.size).all { it == 0.toShort() })
                break
            }
        }
        assertTrue("$name: checked at least one whole cycle", i >= tone.segments.size)
    }

    @Test
    fun everyTonePlaysItsCadenceInBothPlans() {
        for (plan in TonePlans.ALL) {
            for (t in CallTone.entries) {
                val tone = plan[t]
                val cycle = tone.segments.sumOf { it.millis } / 1000.0
                assertCadence("${plan.id} $t", tone, render(tone, 2 * cycle + 0.5))
            }
        }
    }

    @Test
    fun theNorthAmericanTablesAreThePreciseTonePlan() {
        val na = TonePlans.NORTH_AMERICA
        assertEquals(listOf(ToneSegment.on(2000, 440.0, 480.0), ToneSegment.off(4000)), na[CallTone.RINGBACK].segments)
        assertEquals(listOf(ToneSegment.on(500, 480.0, 620.0), ToneSegment.off(500)), na[CallTone.BUSY].segments)
        assertEquals(listOf(ToneSegment.on(250, 480.0, 620.0), ToneSegment.off(250)), na[CallTone.REORDER].segments)
        assertEquals(listOf(913.8, 1370.6, 1776.7), na[CallTone.UNOBTAINABLE].segments.map { it.freqs.single() })
        assertEquals(listOf(274, 274, 380), na[CallTone.UNOBTAINABLE].segments.map { it.millis })
        assertFalse(na[CallTone.UNOBTAINABLE].repeat)
    }

    @Test
    fun theUkTablesAreTheBtTones() {
        val uk = TonePlans.UK
        assertEquals(listOf(400, 200, 400, 2000), uk[CallTone.RINGBACK].segments.map { it.millis })
        assertEquals(listOf(400.0, 450.0), uk[CallTone.RINGBACK].segments[0].freqs)
        assertEquals(listOf(375, 375), uk[CallTone.BUSY].segments.map { it.millis })
        assertEquals(listOf(400, 350, 225, 525), uk[CallTone.REORDER].segments.map { it.millis })
        assertTrue(uk[CallTone.UNOBTAINABLE].repeat) // continuous
        assertEquals(listOf(400.0), uk[CallTone.UNOBTAINABLE].segments.single().freqs)
    }

    @Test
    fun aContinuousToneHasNoClickWhereItsSegmentRepeats() {
        val pcm = render(TonePlans.UK[CallTone.UNOBTAINABLE], 2.5)
        val jumps = pcm.toList().zipWithNext { a, b -> kotlin.math.abs(a - b) }
        val largest = jumps.max()
        assertTrue("largest step $largest", largest < 2 * PI * 400 / 8000 * 1500) // a 400 Hz sine's own slope, not a click
    }

    @Test
    fun theSitEndsAndSaysSo() {
        val gen = ToneGenerator(TonePlans.NORTH_AMERICA[CallTone.UNOBTAINABLE])
        val block = ShortArray(960)
        var blocks = 0
        while (!gen.finished) {
            gen.fill(block)
            blocks++
        }
        assertEquals(8, blocks) // 928 ms of SIT in 120 ms blocks
    }

    @Test
    fun theDefaultPlanFollowsTheCountry() {
        assertEquals(TonePlans.UK, TonePlans.forLocale(Locale.UK))
        assertEquals(TonePlans.NORTH_AMERICA, TonePlans.forLocale(Locale.US))
        assertEquals(TonePlans.NORTH_AMERICA, TonePlans.forLocale(Locale.GERMANY))
        assertEquals(TonePlans.NORTH_AMERICA, TonePlans.forLocale(Locale.ENGLISH)) // no country
        assertEquals(TonePlans.UK, TonePlans.byId("uk"))
    }
}
