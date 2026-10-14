package org.opencell.openvox

import org.opencell.core.voice.CodecId
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.DecoderStats
import org.opencell.core.voice.VoiceCodec
import org.opencell.core.voice.VoiceCodecFactory

/**
 * OpenVoxNB through JNI (the vendored oc_vox, see `src/main/cpp/oc_vox/README.opencell`):
 * one mode, OVX-600 … OVX-1200 (codec ids 3-6, OpenVoxNB spec V1). Its frame is the whole
 * 120 ms block (960 samples, [frameBytes] = 9 … 18 bytes), and it conceals a lost block
 * itself ([decodeLost]). One instance holds one encoder and one decoder; the voice session
 * uses one instance to encode and another to decode, from one thread each.
 */
class OpenVox private constructor(override val codecId: Int, mode: Int) : VoiceCodec {
    private var encoder: Long = OpenVoxNative.encoderCreate(mode)
    private var decoder: Long = OpenVoxNative.decoderCreate(mode)

    init {
        if (encoder == 0L || decoder == 0L) {
            close()
            error("oc_vox init for mode $mode failed")
        }
    }

    override val frameSamples: Int = BLOCK_SAMPLES
    override val frameBytes: Int = OpenVoxNative.blockBytes(mode)
    override val concealsLoss: Boolean get() = true

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == frameSamples) { "a block is $frameSamples samples, not ${pcm.size}" }
        val out = ByteArray(frameBytes)
        OpenVoxNative.encode(live(encoder), pcm, out)
        return out
    }

    override fun decode(bits: ByteArray): ShortArray {
        require(bits.size == frameBytes) { "a block is $frameBytes B, not ${bits.size}" }
        return ShortArray(frameSamples).also { OpenVoxNative.decode(live(decoder), bits, it) }
    }

    /** A lost block: `oc_vox_decode(d, NULL, pcm)`, the codec's parameter-domain concealment. */
    override fun decodeLost(): ShortArray = ShortArray(frameSamples).also { OpenVoxNative.decode(live(decoder), null, it) }

    override val decoderStats: DecoderStats
        get() {
            val s = IntArray(8)
            OpenVoxNative.decoderStats(live(decoder), s)
            return DecoderStats(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
        }

    @Synchronized
    override fun close() {
        if (encoder != 0L) OpenVoxNative.encoderDestroy(encoder)
        if (decoder != 0L) OpenVoxNative.decoderDestroy(decoder)
        encoder = 0L
        decoder = 0L
    }

    private fun live(handle: Long): Long = handle.also { check(it != 0L) { "codec closed" } }

    /** The [VoiceCodecFactory] for OpenVoxNB: codec ids 3-6, experimental until the bitstream freezes (V3). */
    companion object Factory : VoiceCodecFactory {
        /** One frame is the whole 120 ms block. */
        const val BLOCK_SAMPLES = 960

        /** True while OC_VOX_VERSION is 0: the bitstream may change between builds, so Automatic leaves it out (V3). */
        val experimental: Boolean get() = OpenVoxVendor.VERSION == 0

        override val codecs: List<CodecInfo> = (CodecId.OVX_600..CodecId.OVX_1200).map { CodecInfo(it, experimental) }

        override fun create(codecId: Int): VoiceCodec? =
            if (codecId in CodecId.OVX_600..CodecId.OVX_1200) OpenVox(codecId, mode = codecId - CodecId.OVX_600) else null
    }
}

/** The JNI functions of `libopencell_openvox.so` (`src/main/cpp/opencell_openvox_jni.c`); modes are `oc_vox_mode_t` (0 = OVX-600). */
internal object OpenVoxNative {
    init {
        System.loadLibrary("opencell_openvox")
    }

    @JvmStatic external fun version(): Int
    @JvmStatic external fun commit(): String
    @JvmStatic external fun blockBytes(mode: Int): Int
    @JvmStatic external fun encoderCreate(mode: Int): Long
    @JvmStatic external fun encoderDestroy(handle: Long)
    @JvmStatic external fun decoderCreate(mode: Int): Long
    @JvmStatic external fun decoderDestroy(handle: Long)
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, block: ByteArray)
    @JvmStatic external fun decode(handle: Long, block: ByteArray?, pcm: ShortArray)
    @JvmStatic external fun decoderStats(handle: Long, out: IntArray)
}
