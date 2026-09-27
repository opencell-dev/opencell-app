package org.opencell.core.loopback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class LoopbackStatsTest {
    private fun echoed(seq: Int, ms: Int) = ProbeResult(seq, byteArrayOf(seq.toByte()), 1, ProbeOutcome.Echoed(ms.milliseconds))

    @Test
    fun summarizesLatencies() {
        val probes = listOf(
            echoed(0, 430),
            echoed(1, 410),
            echoed(2, 520),
            echoed(3, 500),
            ProbeResult(4, byteArrayOf(4), 1, ProbeOutcome.Lost),
            ProbeResult(5, byteArrayOf(5), 8, ProbeOutcome.SendFailed("not now (0x80) after 8 attempts")),
        )
        val s = LoopbackStats.from(probes, stray = 2)
        assertEquals(6, s.planned)
        assertEquals(5, s.sent)
        assertEquals(1, s.sendFailed)
        assertEquals(4, s.received)
        assertEquals(1, s.lost)
        assertEquals(0, s.pending)
        assertEquals(2, s.stray)
        assertEquals(465.milliseconds, s.mean)
        assertEquals(465.milliseconds, s.median) // (430 + 500) / 2
        assertEquals(410.milliseconds, s.min)
        assertEquals(520.milliseconds, s.max)
        assertEquals(3, s.withinThreshold) // 500 ms counts as within 0.5 s
        assertEquals(20.0, s.lossPercent, 1e-9)
    }

    @Test
    fun oddCountMedianAndCustomThreshold() {
        val s = LoopbackStats.from(listOf(echoed(0, 100), echoed(1, 300), echoed(2, 200)), 0, threshold = 150.milliseconds)
        assertEquals(200.milliseconds, s.median)
        assertEquals(1, s.withinThreshold)
    }

    @Test
    fun emptyRunHasNoLatencies() {
        val s = LoopbackStats.from(emptyList(), 0, planned = 10)
        assertEquals(10, s.planned)
        assertEquals(0, s.sent)
        assertNull(s.mean)
        assertNull(s.median)
        assertNull(s.max)
        assertEquals(0.0, s.lossPercent, 0.0)
    }

    @Test
    fun pendingProbesAreNotLost() {
        val s = LoopbackStats.from(listOf(echoed(0, 400), ProbeResult(1, byteArrayOf(1), 1, ProbeOutcome.Pending)), 0)
        assertEquals(2, s.sent)
        assertEquals(1, s.pending)
        assertEquals(0, s.lost)
    }

    @Test
    fun configValidation() {
        assertNull(LoopbackConfig().problem())
        assertEquals("HELLO#00", LoopbackConfig().payloadFor(0).decodeToString())
        assertEquals("HELLO#ff", LoopbackConfig().payloadFor(255).decodeToString())
        assertEquals("HELLO#00", LoopbackConfig().payloadFor(256).decodeToString())
        assertEquals("HELLO", LoopbackConfig(tagSequence = false).payloadFor(3).decodeToString())
        assertEquals(null, LoopbackConfig(payload = ByteArray(15)).problem())
        assertEquals(
            "Payload is 16 bytes; with the sequence tag the limit is 15",
            LoopbackConfig(payload = ByteArray(16)).problem(),
        )
        assertEquals(null, LoopbackConfig(payload = ByteArray(18), tagSequence = false).problem())
        assertEquals("Payload is 19 bytes; the limit is 18", LoopbackConfig(payload = ByteArray(19), tagSequence = false).problem())
        assertEquals("Count must be 1..10000", LoopbackConfig(count = 0).problem())
        assertEquals(
            "Interval must be at least 120 ms (one frame)",
            LoopbackConfig(interval = 100.milliseconds).problem(),
        )
    }
}
