package org.opencell.core.voice

/**
 * The phone's microphone and speaker as the voice session sees them: 8 kHz
 * mono 16-bit PCM in 120 ms blocks ([BlockCodec.SAMPLES] samples). Android
 * implements it with AudioRecord and AudioTrack; tests with fakes on virtual time.
 */
interface AudioIo {
    /** Opens the microphone, or null when it can't be used now (no permission, or the device refused). */
    fun openMic(): MicInput?

    /** Opens the call's audio output (earpiece, speaker or headset), or null if the device refused. */
    fun openSpeaker(): SpeakerOutput?

    companion object {
        /** No audio devices: the voice session sends silence and plays nothing. */
        val NONE = object : AudioIo {
            override fun openMic(): MicInput? = null
            override fun openSpeaker(): SpeakerOutput? = null
        }
    }
}

interface MicInput : AutoCloseable {
    /**
     * Fills [block] with the next 120 ms of audio, suspending until it has been
     * recorded: the microphone's clock paces the uplink. False once the
     * microphone stopped working (the session then sends silence).
     */
    suspend fun read(block: ShortArray): Boolean

    override fun close()
}

interface SpeakerOutput : AutoCloseable {
    /**
     * Queues one 120 ms block, suspending while the output is full: the speaker's clock
     * paces playout. False if the output refused it (a dead device); a refused write
     * still takes about a block's time, so a caller never spins on it.
     */
    suspend fun write(block: ShortArray): Boolean

    override fun close()
}
