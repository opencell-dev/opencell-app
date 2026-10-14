package org.opencell.openvox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.voice.BlockCodec
import org.opencell.core.voice.CodecChoice
import org.opencell.core.voice.CodecId
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.CodecPreference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real oc_vox through the real JNI glue, built for the host by the hostOpenVox
 * task (the same sources the APK's arm64 library is built from). OpenVoxNB spec §5.
 */
class OpenVoxTest {
    /** Codec id to block size: OVX-600 … OVX-1200 (V1). */
    private val modes = listOf(CodecId.OVX_600 to 9, CodecId.OVX_800 to 12, CodecId.OVX_1000 to 15, CodecId.OVX_1200 to 18)

    /** [blocks] x 120 ms of a voice-like signal: 200 Hz with harmonics at 600 and 1100 Hz. */
    private fun speechish(blocks: Int) = ShortArray(blocks * 960) { i ->
        val t = i / 8000.0
        (8000 * sin(2 * PI * 200 * t) + 4000 * sin(2 * PI * 600 * t) + 2000 * sin(2 * PI * 1100 * t)).toInt().toShort()
    }

    private fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    private fun block(pcm: ShortArray, b: Int) = pcm.copyOfRange(b * 960, (b + 1) * 960)

    @Test
    fun eachModeIsOneBlockOfItsOwnSize() {
        for ((id, bytes) in modes) {
            OpenVox.create(id)!!.use { c ->
                assertEquals(id, c.codecId)
                assertEquals(960, c.frameSamples)
                assertEquals(bytes, c.frameBytes)
                assertTrue(c.concealsLoss)
                val b = BlockCodec(c)
                assertEquals(1, b.frames)
                assertEquals(bytes, b.payloadBytes)
            }
        }
    }

    @Test
    fun onlyIdsThreeToSixAreMade() {
        assertEquals(listOf(3, 4, 5, 6), OpenVox.codecs.map { it.id })
        for (id in listOf(CodecId.NONE, CodecId.CODEC2_1200, CodecId.LPCNET_1733, 7)) assertNull(OpenVox.create(id))
    }

    @Test
    fun aRoundTripKeepsItsLoudnessInEveryMode() {
        val input = speechish(10)
        for ((id, bytes) in modes) {
            val enc = BlockCodec(OpenVox.create(id)!!)
            val dec = BlockCodec(OpenVox.create(id)!!)
            val out = ShortArray(input.size)
            for (b in 0 until 10) {
                val payload = enc.encode(block(input, b))
                assertEquals(bytes, payload.size)
                dec.decode(payload).copyInto(out, b * 960)
            }
            val tail = 2 * 960 // past the codec's start-up
            val ratio = rms(out.copyOfRange(tail, out.size)) / rms(input.copyOfRange(tail, input.size))
            assertTrue("${CodecId.label(id)}: output/input loudness $ratio", ratio in 0.3..3.0)
        }
    }

    @Test
    fun silenceDecodesQuietly() {
        for ((id, _) in modes) {
            val enc = OpenVox.create(id)!!
            val dec = OpenVox.create(id)!!
            var last = ShortArray(0)
            repeat(10) { last = dec.decode(enc.encode(ShortArray(960))) }
            assertTrue("${CodecId.label(id)}: rms ${rms(last)}", rms(last) < 100)
            enc.close()
            dec.close()
        }
    }

    /** Review focus 5: decode(null) makes up a whole block and is counted. */
    @Test
    fun aLostBlockIsConcealedAndCounted() {
        val enc = OpenVox.create(CodecId.OVX_1200)!!
        val dec = OpenVox.create(CodecId.OVX_1200)!!
        val input = speechish(3)
        for (b in 0 until 3) dec.decode(enc.encode(block(input, b)))
        assertEquals(960, BlockCodec(dec).decode(null).size)
        val s = dec.decoderStats!! // a VoiceCodec's is nullable; OpenVox always has one
        assertEquals(4, s.blocks)
        assertEquals(1, s.lostBlocks)
        assertTrue("concealed frames ${s.concealedFrames}", s.concealedFrames >= 6)
    }

    /** V8: the block's toggle bit lets the receiver count a loss nobody announced. */
    @Test
    fun aBlockThatNeverCameIsNoticedByItsToggleBit() {
        val enc = OpenVox.create(CodecId.OVX_600)!!
        val dec = OpenVox.create(CodecId.OVX_600)!!
        val input = speechish(4)
        for (b in 0 until 4) {
            val payload = enc.encode(block(input, b))
            if (b != 2) dec.decode(payload)
        }
        assertEquals(1, dec.decoderStats!!.toggleGaps)
    }

    @Test
    fun theEncoderIsDeterministic() {
        val a = OpenVox.create(CodecId.OVX_1000)!!
        val b = OpenVox.create(CodecId.OVX_1000)!!
        val pcm = speechish(1)
        assertArrayEquals(a.encode(pcm), b.encode(pcm))
        a.close()
        b.close()
    }

    @Test
    fun wrongSizesAndClosedCodecsAreRefused() {
        val c = OpenVox.create(CodecId.OVX_1200)!!
        assertThrows(IllegalArgumentException::class.java) { c.encode(ShortArray(320)) }
        assertThrows(IllegalArgumentException::class.java) { c.decode(ByteArray(9)) }
        c.close()
        c.close() // idempotent
        assertThrows(IllegalStateException::class.java) { c.encode(ShortArray(960)) }
        assertThrows(IllegalStateException::class.java) { c.decodeLost() }
    }

    /** V3/V4: the generated constants are the library's, and while OC_VOX_VERSION is 0 Automatic leaves OpenVoxNB out. */
    @Test
    fun theVendoredVersionDecidesWhetherItIsExperimental() {
        assertEquals(OpenVoxVendor.VERSION, OpenVoxNative.version())
        assertEquals(OpenVoxVendor.COMMIT, OpenVoxNative.commit())
        val experimental = OpenVoxVendor.VERSION == 0
        assertTrue(OpenVox.codecs.all { it.experimental == experimental })
        val automatic = CodecChoice.allowedCaps(CodecPreference.Automatic, listOf(CodecInfo(CodecId.CODEC2_1200)) + OpenVox.codecs)
        assertEquals(if (experimental) 0x01 else 0x3D, automatic)
        assertEquals(if (experimental) "OpenVoxNB 1200 (experimental)" else "OpenVoxNB 1200", OpenVox.codecs.last().choiceLabel)
    }
}
