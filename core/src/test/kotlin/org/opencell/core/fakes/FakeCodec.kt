package org.opencell.core.fakes

import org.opencell.core.voice.CodecId
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.DecoderStats
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

/**
 * Makes codecs for [supported] ids and remembers every instance: [FakeCodec]s, and
 * [FakeConcealingCodec]s for OpenVoxNB's ids (3-6, with their block sizes).
 */
class FakeCodecs(supported: Set<Int> = setOf(CodecId.CODEC2_1200)) : VoiceCodecFactory {
    private val ids = supported
    val made = mutableListOf<FakeCodec>()

    /** The [FakeConcealingCodec]s made, in order (each voice run: the encoder, then the decoder). */
    val concealing = mutableListOf<FakeConcealingCodec>()

    override val codecs: List<CodecInfo> get() = ids.sorted().map { CodecInfo(it) }

    override fun create(codecId: Int): VoiceCodec? = when {
        codecId !in ids -> null
        codecId in CodecId.OVX_600..CodecId.OVX_1200 ->
            FakeConcealingCodec(codecId, frameBytes = 9 + 3 * (codecId - CodecId.OVX_600)).also { concealing += it }
        else -> FakeCodec(codecId).also { made += it }
    }
}

/**
 * OpenVoxNB's shape, easy to read in tests: one frame is the whole 120 ms block (960
 * samples, [frameBytes] bytes). A block encodes to its first sample (u16, big-endian)
 * then zeros, decodes to that sample throughout, and a lost block decodes to [LOST].
 */
class FakeConcealingCodec(override val codecId: Int = CodecId.OVX_1200, override val frameBytes: Int = 18) : VoiceCodec {
    override val frameSamples = 960
    override val concealsLoss = true
    var lost = 0
    var closed = false

    override val decoderStats: DecoderStats get() = DecoderStats(concealedFrames = 6 * lost)

    override fun encode(pcm: ShortArray): ByteArray {
        check(!closed)
        require(pcm.size == frameSamples)
        val v = pcm[0].toInt()
        return ByteArray(frameBytes).also {
            it[0] = (v shr 8).toByte()
            it[1] = v.toByte()
        }
    }

    override fun decode(bits: ByteArray): ShortArray {
        check(!closed)
        require(bits.size == frameBytes)
        val v = ((bits[0].toInt() and 0xFF) shl 8) or (bits[1].toInt() and 0xFF)
        return ShortArray(frameSamples) { v.toShort() }
    }

    override fun decodeLost(): ShortArray {
        check(!closed)
        lost++
        return ShortArray(frameSamples) { LOST }
    }

    override fun close() {
        closed = true
    }

    companion object {
        const val LOST: Short = 77
    }
}
