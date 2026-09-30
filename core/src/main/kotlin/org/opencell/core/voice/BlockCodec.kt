package org.opencell.core.voice

import org.opencell.core.protocol.GattContract

/**
 * One app data frame of voice: [FRAMES] codec frames (120 ms of audio, one radio
 * frame) encoded and concatenated into a single UP/DOWN payload, with no header
 * (codec id 1: 3 x 6 B = 18 B, exactly [GattContract.MAX_PAYLOAD]).
 */
class BlockCodec(private val codec: VoiceCodec) {
    val codecId: Int get() = codec.codecId

    /** PCM samples per block: [SAMPLES] (960). */
    val blockSamples: Int = codec.frameSamples * FRAMES

    /** Payload bytes per block (18 for Codec2 1200). */
    val payloadBytes: Int = codec.frameBytes * FRAMES

    init {
        require(blockSamples == SAMPLES) { "a block must be $BLOCK_MILLIS ms at $SAMPLE_RATE Hz, not $blockSamples samples" }
        require(payloadBytes <= GattContract.MAX_PAYLOAD) { "$payloadBytes B doesn't fit one app data frame" }
    }

    /** Encodes one block of exactly [blockSamples] samples into [payloadBytes] bytes. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == blockSamples) { "a block is $blockSamples samples, not ${pcm.size}" }
        val n = codec.frameSamples
        val out = ByteArray(payloadBytes)
        for (i in 0 until FRAMES) {
            codec.encode(pcm.copyOfRange(i * n, (i + 1) * n)).copyInto(out, i * codec.frameBytes)
        }
        return out
    }

    /** Decodes one payload of exactly [payloadBytes] bytes into [blockSamples] samples. */
    fun decode(payload: ByteArray): ShortArray {
        require(payload.size == payloadBytes) { "a block is $payloadBytes B, not ${payload.size}" }
        val b = codec.frameBytes
        val out = ShortArray(blockSamples)
        for (i in 0 until FRAMES) {
            codec.decode(payload.copyOfRange(i * b, (i + 1) * b)).copyInto(out, i * codec.frameSamples)
        }
        return out
    }

    companion object {
        /** Codec frames per app data frame. */
        const val FRAMES = 3
        const val SAMPLE_RATE = 8_000

        /** One radio frame ([GattContract.FRAME_MILLIS]). */
        const val BLOCK_MILLIS = GattContract.FRAME_MILLIS

        /** PCM samples per block: 120 ms at 8 kHz. */
        const val SAMPLES = (SAMPLE_RATE * BLOCK_MILLIS / 1000).toInt()
    }
}
