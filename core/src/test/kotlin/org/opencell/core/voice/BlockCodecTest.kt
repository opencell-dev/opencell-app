package org.opencell.core.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opencell.core.fakes.FakeCodec
import org.opencell.core.protocol.GattContract

class BlockCodecTest {
    /** 120 ms whose three 40 ms frames hold the constant samples a, b and c. */
    private fun block(a: Int, b: Int, c: Int) = ShortArray(960) { i -> listOf(a, b, c)[i / 320].toShort() }

    @Test
    fun codec2x1200FillsOneAppDataFrameExactly() {
        val c = BlockCodec(FakeCodec())
        assertEquals(960, c.blockSamples)
        assertEquals(18, c.payloadBytes)
        assertEquals(GattContract.MAX_PAYLOAD, c.payloadBytes)
        assertEquals(960, BlockCodec.SAMPLES)
    }

    @Test
    fun threeFramesAreConcatenatedInOrder() {
        val codec = FakeCodec()
        val payload = BlockCodec(codec).encode(block(0x0102, 0x0304, 0x0506))
        assertArrayEquals(
            byteArrayOf(1, 2, 0, 0, 0, 0, 3, 4, 0, 0, 0, 0, 5, 6, 0, 0, 0, 0),
            payload,
        )
        assertEquals(3, codec.encoded)
    }

    @Test
    fun decodeSplitsThePayloadBackIntoThreeFrames() {
        val c = BlockCodec(FakeCodec())
        val pcm = c.decode(c.encode(block(100, -200, 300)))
        assertArrayEquals(block(100, -200, 300), pcm)
    }

    @Test
    fun wrongSizesAreRefused() {
        val c = BlockCodec(FakeCodec())
        assertThrows(IllegalArgumentException::class.java) { c.encode(ShortArray(959)) }
        assertThrows(IllegalArgumentException::class.java) { c.decode(ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { c.decode(ByteArray(9)) } // a test frame is not voice
    }

    @Test
    fun aCodecThatDoesNotFitOneFrameIsRefused() {
        val tooBig = object : VoiceCodec by FakeCodec() {
            override val frameBytes = 7 // Codec2 1300: 21 B per 120 ms
        }
        assertThrows(IllegalArgumentException::class.java) { BlockCodec(tooBig) }
    }

    @Test
    fun codecIdsAndLabels() {
        assertEquals(1, CodecId.CODEC2_1200)
        assertEquals(CodecId.CODEC2_1200, CodecId.DEFAULT)
        assertEquals("Codec2 1200", CodecId.label(1))
        assertEquals("codec 9", CodecId.label(9))
    }
}
