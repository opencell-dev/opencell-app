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

    /** Every open attempt, in order: "mic" or "speaker". */
    val opened = mutableListOf<String>()

    /** Thrown by every open while set, as a broken device layer would. */
    var failWith: Throwable? = null

    /** Open attempts, including refused ones. */
    val micOpens: Int get() = opened.count { it == "mic" }

    override fun openMic(): MicInput? {
        opened += "mic"
        failWith?.let { throw it }
        return if (micWorks) FakeMic(sample).also { mics += it } else null
    }

    override fun openSpeaker(): SpeakerOutput? {
        opened += "speaker"
        failWith?.let { throw it }
        return if (speakerWorks) FakeSpeaker().also { speakers += it } else null
    }
}

class FakeMic(private val sample: (Int) -> Short) : MicInput {
    var blocks = 0
    var closed = false

    /** Makes the next read fail, as a microphone taken away mid-call would. */
    var failNext = false

    private var stallMillis = 0L
    private var backlog = 0

    /**
     * The next read takes [millis] instead of 120 ms, and the [backlog] reads after it
     * return at once: a device that stalled and then hands over what it buffered.
     */
    fun stall(millis: Long, backlog: Int) {
        stallMillis = millis
        this.backlog = backlog
    }

    override suspend fun read(block: ShortArray): Boolean {
        if (closed || failNext) return false
        when {
            stallMillis > 0 -> delay(stallMillis).also { stallMillis = 0 }
            backlog > 0 -> backlog--
            else -> delay(BlockCodec.BLOCK_MILLIS)
        }
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

    /** Every write fails (after a block's time), as a dead AudioTrack's does. */
    var failWrites = false

    /** The first sample of every block played, for short assertions. */
    val firstSamples: List<Int> get() = played.map { it[0].toInt() }

    override suspend fun write(block: ShortArray): Boolean {
        check(!closed)
        if (failWrites) {
            delay(BlockCodec.BLOCK_MILLIS)
            return false
        }
        played += block.copyOf()
        delay(BlockCodec.BLOCK_MILLIS)
        return true
    }

    override fun close() {
        closed = true
    }
}
