package org.opencell.core.voice

/**
 * The voice codec ids that CALL_SETUP/SETUP_IND `codec_caps` and CONNECT/CONNECTED
 * `codec` carry (voice spec §4). `codec` is one id; `codec_caps` is a bitmap in
 * which bit (id - 1) says "supports id". Every firmware so far sends caps 0x01 and
 * codec 1.
 */
object CodecId {
    /** Never sent: "no codec". */
    const val NONE = 0

    /** Codec2 1200: three 40 ms frames of 6 bytes, concatenated, per 120 ms app data frame (18 B, no header). */
    const val CODEC2_1200 = 1

    /** Reserved for Codec2 700C with a one-byte header (sequence and codec); not implemented. */
    const val CODEC2_700C_SEQ = 2

    /** What a call whose CONNECTED the app missed (it learned of the call from STATUS) uses: the only codec any network sends. */
    const val DEFAULT = CODEC2_1200

    fun label(id: Int): String = when (id) {
        CODEC2_1200 -> "Codec2 1200"
        CODEC2_700C_SEQ -> "Codec2 700C"
        else -> "codec $id"
    }
}

/**
 * One Codec2-style voice codec instance: 8 kHz mono 16-bit PCM in fixed frames.
 * Not thread-safe: the voice session uses one instance to encode and another to decode.
 */
interface VoiceCodec : AutoCloseable {
    /** The [CodecId] this instance implements. */
    val codecId: Int

    /** PCM samples per codec frame (320 for Codec2 1200: 40 ms at 8 kHz). */
    val frameSamples: Int

    /** Encoded bytes per codec frame (6 for Codec2 1200). */
    val frameBytes: Int

    /** Encodes exactly [frameSamples] samples into [frameBytes] bytes. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes exactly [frameBytes] bytes into [frameSamples] samples. */
    fun decode(bits: ByteArray): ShortArray

    /** Frees the codec. Idempotent; no other call may follow. */
    override fun close()
}

/** Makes codec instances by [CodecId]: null for an id this build can't decode. */
fun interface VoiceCodecFactory {
    fun create(codecId: Int): VoiceCodec?

    companion object {
        /** No codecs at all: calls connect without audio (the default for tests and the loopback tools). */
        val NONE = VoiceCodecFactory { null }
    }
}
