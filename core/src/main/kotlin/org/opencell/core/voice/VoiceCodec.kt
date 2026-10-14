package org.opencell.core.voice

/**
 * The voice codec ids that CALL_SETUP/SETUP_IND `codec_caps` and CONNECT/CONNECTED
 * `codec` carry (codec-negotiation spec N1, as amended by the OpenVoxNB spec V1).
 * `codec` is one id; `codec_caps` is a bitmap in which bit (id - 1) says "supports
 * id". Each call's codec is the best one in [RANKING] that both ends have (N2/V2),
 * fixed when the call is set up.
 */
object CodecId {
    /** "No codec": ANSWER_CODEC 0 turns a call down for want of a shared codec (cause 7). */
    const val NONE = 0

    /** Codec2 1200: three 40 ms frames of 6 bytes, concatenated, per 120 ms app data frame (18 B, no header). */
    const val CODEC2_1200 = 1

    /** Reserved for LPCNet 1733; not implemented. (Codec2 700C, this id's old meaning, was never sent and is retired.) */
    const val LPCNET_1733 = 2

    /** What a call whose CONNECTED the app missed (it learned of the call from STATUS) uses: what plain DIAL and ANSWER mean. */
    const val DEFAULT = CODEC2_1200

    /** Best first (codec-negotiation spec N2, OpenVoxNB spec V2). One table per code base: firmware and core have theirs. */
    val RANKING: List<Int> = listOf(LPCNET_1733, CODEC2_1200)

    fun label(id: Int): String = when (id) {
        CODEC2_1200 -> "Codec2 1200"
        LPCNET_1733 -> "LPCNet 1733"
        else -> "codec $id"
    }

    /** [id]'s bit in `codec_caps`; 0 for an id with none (0, or above 8). */
    fun bit(id: Int): Int = if (id in 1..8) 1 shl (id - 1) else 0

    /** The `codec_caps` of [ids]. */
    fun caps(ids: Iterable<Int>): Int = ids.fold(0) { acc, id -> acc or bit(id) }

    /** Whether [caps] has [id]. */
    fun inCaps(caps: Int, id: Int): Boolean = bit(id) != 0 && caps and bit(id) != 0

    /** The best codec in [caps] by [RANKING], or [NONE]; bits of ids not in the ranking are ignored. */
    fun best(caps: Int): Int = RANKING.firstOrNull { inCaps(caps, it) } ?: NONE
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

    /** The codecs [create] makes: what the phone may offer and accept (codec-negotiation spec §7.1). */
    val codecs: List<CodecInfo> get() = emptyList()

    companion object {
        /** No codecs at all: calls connect without audio (the default for tests and the loopback tools). */
        val NONE = VoiceCodecFactory { null }
    }
}
