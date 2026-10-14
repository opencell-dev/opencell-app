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

    /**
     * OpenVoxNB, one id per mode (OpenVoxNB spec V1): one 120 ms block of 9, 12, 15 or
     * 18 bytes per app data frame. Its higher modes get ids when the frame grows.
     */
    const val OVX_600 = 3
    const val OVX_800 = 4
    const val OVX_1000 = 5
    const val OVX_1200 = 6

    /** What a call whose CONNECTED the app missed (it learned of the call from STATUS) uses: what plain DIAL and ANSWER mean. */
    const val DEFAULT = CODEC2_1200

    /**
     * Best first (codec-negotiation spec N2, OpenVoxNB spec V2): OpenVoxNB's modes by bit
     * rate, and OVX-600 below Codec2 1200 because it carries half the bits. One table per
     * code base: firmware and core have theirs.
     */
    val RANKING: List<Int> = listOf(OVX_1200, OVX_1000, OVX_800, LPCNET_1733, CODEC2_1200, OVX_600)

    fun label(id: Int): String = when (id) {
        CODEC2_1200 -> "Codec2 1200"
        LPCNET_1733 -> "LPCNet 1733"
        OVX_600 -> "OpenVoxNB 600"
        OVX_800 -> "OpenVoxNB 800"
        OVX_1000 -> "OpenVoxNB 1000"
        OVX_1200 -> "OpenVoxNB 1200"
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

/** A decoder's own counters since it started (OpenVoxNB's `oc_vox_dec_stats_t`), for the call screen's developer line. */
data class DecoderStats(
    val blocks: Int = 0,
    /** Blocks lost, and blocks whose protected bits couldn't be repaired. */
    val lostBlocks: Int = 0,
    val concealedFrames: Int = 0,
    /** A block went missing unannounced: its toggle bit repeated. */
    val toggleGaps: Int = 0,
    val golayFixed: Int = 0,
    val golayFailed: Int = 0,
    val hammingFixed: Int = 0,
    val crcFailed: Int = 0,
)

/**
 * One voice codec instance: 8 kHz mono 16-bit PCM in fixed frames.
 * Not thread-safe: the voice session uses one instance to encode and another to decode.
 */
interface VoiceCodec : AutoCloseable {
    /** The [CodecId] this instance implements. */
    val codecId: Int

    /** PCM samples per codec frame (320 for Codec2 1200: 40 ms at 8 kHz; 960 for OpenVoxNB, whose frame is the whole block). */
    val frameSamples: Int

    /** Encoded bytes per codec frame (6 for Codec2 1200; 9 to 18 for OpenVoxNB). */
    val frameBytes: Int

    /** Encodes exactly [frameSamples] samples into [frameBytes] bytes. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes exactly [frameBytes] bytes into [frameSamples] samples. */
    fun decode(bits: ByteArray): ShortArray

    /**
     * True: the codec makes up a lost frame itself ([decodeLost]: OpenVoxNB's concealment).
     * False: the jitter buffer replays the last block quieter instead.
     */
    val concealsLoss: Boolean get() = false

    /** [frameSamples] samples standing in for a lost frame; only for a codec that [concealsLoss]. */
    fun decodeLost(): ShortArray = throw UnsupportedOperationException("${CodecId.label(codecId)} doesn't conceal lost frames")

    /** The decoder's own counters, for a codec that keeps them (OpenVoxNB); null otherwise. */
    val decoderStats: DecoderStats? get() = null

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

        /** Each id made by the first of [factories] that makes it; [codecs] is all of theirs, in order, each id once. */
        fun of(vararg factories: VoiceCodecFactory): VoiceCodecFactory = object : VoiceCodecFactory {
            override val codecs: List<CodecInfo> = factories.flatMap { it.codecs }.distinctBy { it.id }

            override fun create(codecId: Int): VoiceCodec? = factories.firstNotNullOfOrNull { it.create(codecId) }
        }
    }
}
