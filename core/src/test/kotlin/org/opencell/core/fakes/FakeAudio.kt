package org.opencell.core.fakes

import kotlinx.coroutines.delay
import org.opencell.core.voice.AudioIo
import org.opencell.core.voice.BlockCodec
import org.opencell.core.voice.MicInput
import org.opencell.core.voice.SpeakerOutput

/**
 * Audio on virtual time: each microphone read takes 120 ms and returns a block
 * whose samples all equal [sample] of the block's index (1000, 1001, … by
 * default); the speaker takes 120 ms per block and keeps what it was given.
 */
class FakeAudio(
    var micWorks: Boolean = true,
    var speakerWorks: Boolean = true,
    private val sample: (Int) -> Short = { (1000 + it).toShort() },
) : AudioIo {
    val mics = mutableListOf<FakeMic>()
    val speakers = mutableListOf<FakeSpeaker>()

    override fun openMic(): MicInput? = if (micWorks) FakeMic(sample).also { mics += it } else null

    override fun openSpeaker(): SpeakerOutput? = if (speakerWorks) FakeSpeaker().also { speakers += it } else null
}

class FakeMic(private val sample: (Int) -> Short) : MicInput {
    var blocks = 0
    var closed = false

    /** Makes the next read fail, as a microphone taken away mid-call would. */
    var failNext = false

    override suspend fun read(block: ShortArray): Boolean {
        if (closed || failNext) return false
        delay(BlockCodec.BLOCK_MILLIS)
        block.fill(sample(blocks++))
        return true
    }

    override fun close() {
        closed = true
    }
}

class FakeSpeaker : SpeakerOutput {
    val played = mutableListOf<ShortArray>()
    var closed = false

    /** The first sample of every block played, for short assertions. */
    val firstSamples: List<Int> get() = played.map { it[0].toInt() }

    override suspend fun write(block: ShortArray) {
        check(!closed)
        played += block.copyOf()
        delay(BlockCodec.BLOCK_MILLIS)
    }

    override fun close() {
        closed = true
    }
}
