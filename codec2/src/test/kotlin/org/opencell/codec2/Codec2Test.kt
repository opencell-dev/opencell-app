package org.opencell.codec2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.voice.BlockCodec
import org.opencell.core.voice.CodecId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real libcodec2 through the real JNI glue, built for the host by the
 * hostCodec2 task (the same sources the APK's arm64 library is built from).
 */
class Codec2Test {
    /** [blocks] x 120 ms of a voice-like signal: 200 Hz with harmonics at 600 and 1100 Hz. */
    private fun speechish(blocks: Int) = ShortArray(blocks * BlockCodec.SAMPLES) { i ->
        val t = i / 8000.0
        (8000 * sin(2 * PI * 200 * t) + 4000 * sin(2 * PI * 600 * t) + 2000 * sin(2 * PI * 1100 * t)).toInt().toShort()
    }

    private fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    @Test
    fun codec2x1200IsFortyMillisecondsInSixBytes() {
        Codec2.create(CodecId.CODEC2_1200)!!.use {
            assertEquals(320, it.frameSamples)
            assertEquals(6, it.frameBytes)
        }
    }

    @Test
    fun onlyCodecId1IsMade() {
        assertNull(Codec2.create(CodecId.CODEC2_700C_SEQ))
        assertNull(Codec2.create(0))
    }

    @Test
    fun aBlockRoundTripsThroughEighteenBytesAndKeepsItsLoudness() {
        val enc = BlockCodec(Codec2.create(1)!!)
        val dec = BlockCodec(Codec2.create(1)!!)
        val input = speechish(10)
        val out = ShortArray(input.size)
        for (b in 0 until 10) {
            val payload = enc.encode(input.copyOfRange(b * 960, (b + 1) * 960))
            assertEquals(18, payload.size)
            dec.decode(payload).copyInto(out, b * 960)
        }
        val tail = 2 * 960 // past the codec's start-up
        val ratio = rms(out.copyOfRange(tail, out.size)) / rms(input.copyOfRange(tail, input.size))
        assertTrue("output/input loudness $ratio", ratio in 0.3..3.0)
    }

    @Test
    fun theEncoderIsDeterministic() {
        val a = Codec2.create(1)!!
        val b = Codec2.create(1)!!
        val frame = speechish(1).copyOfRange(0, 320)
        assertArrayEquals(a.encode(frame), b.encode(frame))
        a.close()
        b.close()
    }

    @Test
    fun silenceDecodesQuietly() {
        val enc = Codec2.create(1)!!
        val dec = Codec2.create(1)!!
        var last = ShortArray(0)
        repeat(10) { last = dec.decode(enc.encode(ShortArray(320))) }
        assertTrue("rms ${rms(last)}", rms(last) < 100)
    }

    @Test
    fun wrongSizesAndClosedCodecsAreRefused() {
        val c = Codec2.create(1)!!
        assertThrows(IllegalArgumentException::class.java) { c.encode(ShortArray(160)) }
        assertThrows(IllegalArgumentException::class.java) { c.decode(ByteArray(7)) }
        c.close()
        c.close() // idempotent
        assertThrows(IllegalStateException::class.java) { c.encode(ShortArray(320)) }
    }
}
