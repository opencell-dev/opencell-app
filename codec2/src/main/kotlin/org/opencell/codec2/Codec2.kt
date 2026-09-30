package org.opencell.codec2

import org.opencell.core.voice.CodecId
import org.opencell.core.voice.VoiceCodec
import org.opencell.core.voice.VoiceCodecFactory

/**
 * Codec2 through JNI (the vendored libcodec2, LGPL-2.1, see
 * `src/main/cpp/codec2/README.opencell`). One instance is one `struct CODEC2`:
 * use one to encode and another to decode, from one thread each.
 */
class Codec2 private constructor(override val codecId: Int, mode: Int) : VoiceCodec {
    private var handle: Long = Codec2Native.create(mode).also {
        check(it != 0L) { "codec2_create($mode) failed" }
    }
    override val frameSamples: Int = Codec2Native.samplesPerFrame(handle)
    override val frameBytes: Int = Codec2Native.bytesPerFrame(handle)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == frameSamples) { "a frame is $frameSamples samples, not ${pcm.size}" }
        val out = ByteArray(frameBytes)
        Codec2Native.encode(live(), pcm, out)
        return out
    }

    override fun decode(bits: ByteArray): ShortArray {
        require(bits.size == frameBytes) { "a frame is $frameBytes B, not ${bits.size}" }
        val out = ShortArray(frameSamples)
        Codec2Native.decode(live(), bits, out)
        return out
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) Codec2Native.destroy(handle)
        handle = 0L
    }

    private fun live(): Long = handle.also { check(it != 0L) { "codec closed" } }

    /** The [VoiceCodecFactory] for the app: [CodecId.CODEC2_1200] only. */
    companion object Factory : VoiceCodecFactory {
        /** `CODEC2_MODE_1200` in codec2.h. */
        const val MODE_1200 = 5

        override fun create(codecId: Int): VoiceCodec? = when (codecId) {
            CodecId.CODEC2_1200 -> Codec2(codecId, MODE_1200)
            else -> null
        }
    }
}

/** The JNI functions of `libopencell_codec2.so` (`src/main/cpp/opencell_codec2_jni.c`). */
internal object Codec2Native {
    init {
        System.loadLibrary("codec2")
        System.loadLibrary("opencell_codec2")
    }

    @JvmStatic external fun create(mode: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun samplesPerFrame(handle: Long): Int
    @JvmStatic external fun bytesPerFrame(handle: Long): Int
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, bits: ByteArray)
    @JvmStatic external fun decode(handle: Long, bits: ByteArray, pcm: ShortArray)
}
