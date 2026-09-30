package org.opencell.core.fakes

import org.opencell.core.voice.CodecId
import org.opencell.core.voice.VoiceCodec
import org.opencell.core.voice.VoiceCodecFactory

/**
 * A codec with Codec2 1200's shape (320 samples, 6 bytes) that is easy to read in
 * tests: a frame encodes to its first sample (u16, big-endian) and then four
 * zero bytes; decoding fills the frame with that sample.
 */
class FakeCodec(override val codecId: Int = CodecId.CODEC2_1200) : VoiceCodec {
    override val frameSamples = 320
    override val frameBytes = 6
    var encoded = 0
    var decoded = 0
    var closed = false

    override fun encode(pcm: ShortArray): ByteArray {
        check(!closed)
        require(pcm.size == frameSamples)
        encoded++
        val v = pcm[0].toInt()
        return byteArrayOf((v shr 8).toByte(), v.toByte(), 0, 0, 0, 0)
    }

    override fun decode(bits: ByteArray): ShortArray {
        check(!closed)
        require(bits.size == frameBytes)
        decoded++
        val v = ((bits[0].toInt() and 0xFF) shl 8) or (bits[1].toInt() and 0xFF)
        return ShortArray(frameSamples) { v.toShort() }
    }

    override fun close() {
        closed = true
    }
}

/** Makes [FakeCodec]s for [supported] ids and remembers every instance. */
class FakeCodecs(private val supported: Set<Int> = setOf(CodecId.CODEC2_1200)) : VoiceCodecFactory {
    val made = mutableListOf<FakeCodec>()

    override fun create(codecId: Int): VoiceCodec? =
        if (codecId in supported) FakeCodec(codecId).also { made += it } else null
}
