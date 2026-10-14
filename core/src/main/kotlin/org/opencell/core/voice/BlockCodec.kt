package org.opencell.core.voice

import org.opencell.core.protocol.GattContract

/**
 * One app data frame of voice: [frames] codec frames (120 ms of audio, one radio
 * frame) encoded and concatenated into a single UP/DOWN payload, with no header.
 * Codec2 1200: three 40 ms frames of 6 B, 18 B. OpenVoxNB: its frame is the whole
 * block, one frame of 9 to 18 B (OpenVoxNB spec V8).
 */
class BlockCodec(private val codec: VoiceCodec) {
    val codecId: Int get() = codec.codecId

    /** Codec frames per block: [SAMPLES] / the codec's frame (3 for Codec2 1200, 1 for OpenVoxNB). */
    val frames: Int = if (codec.frameSamples > 0) SAMPLES / codec.frameSamples else 0

    /** PCM samples per block: [SAMPLES] (960). */
    val blockSamples: Int = codec.frameSamples * frames

    /** Payload bytes per block (18 for Codec2 1200). */
    val payloadBytes: Int = codec.frameBytes * frames

    /** The codec makes up lost blocks itself: [decode] takes null for one. */
    val concealsLoss: Boolean get() = codec.concealsLoss

    /** The decoder's own counters, if the codec keeps them. */
    val decoderStats: DecoderStats? get() = codec.decoderStats

    init {
        require(frames > 0 && blockSamples == SAMPLES) {
            "a block must be $BLOCK_MILLIS ms at $SAMPLE_RATE Hz, a whole number of ${codec.frameSamples}-sample frames"
        }
        require(payloadBytes <= GattContract.MAX_PAYLOAD) { "$payloadBytes B doesn't fit one app data frame" }
    }

    /** Encodes one block of exactly [blockSamples] samples into [payloadBytes] bytes. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == blockSamples) { "a block is $blockSamples samples, not ${pcm.size}" }
        val n = codec.frameSamples
        val out = ByteArray(payloadBytes)
        for (i in 0 until frames) {
            codec.encode(pcm.copyOfRange(i * n, (i + 1) * n)).copyInto(out, i * codec.frameBytes)
        }
        return out
    }

    /**
     * Decodes one payload of exactly [payloadBytes] bytes into [blockSamples] samples;
     * null is a lost block, which only a codec that [concealsLoss] can make up.
     */
    fun decode(payload: ByteArray?): ShortArray {
        if (payload == null) {
            require(codec.concealsLoss) { "${CodecId.label(codecId)} doesn't conceal lost blocks" }
        } else {
            require(payload.size == payloadBytes) { "a block is $payloadBytes B, not ${payload.size}" }
        }
        val b = codec.frameBytes
        val out = ShortArray(blockSamples)
        for (i in 0 until frames) {
            val pcm = if (payload == null) codec.decodeLost() else codec.decode(payload.copyOfRange(i * b, (i + 1) * b))
            pcm.copyInto(out, i * codec.frameSamples)
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 8_000

        /** One radio frame ([GattContract.FRAME_MILLIS]). */
        const val BLOCK_MILLIS = GattContract.FRAME_MILLIS

        /** PCM samples per block: 120 ms at 8 kHz. */
        const val SAMPLES = (SAMPLE_RATE * BLOCK_MILLIS / 1000).toInt()
    }
}
