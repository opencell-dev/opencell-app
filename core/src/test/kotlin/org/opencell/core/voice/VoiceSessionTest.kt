package org.opencell.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.fakes.FakeAudio
import org.opencell.core.fakes.FakeCodecs
import org.opencell.core.fakes.FakeLink
import org.opencell.core.link.WriteResult
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.SigState
import kotlin.time.Duration.Companion.milliseconds

/** The voice session on virtual time, against a fake link, fake codecs and fake audio. */
class VoiceSessionTest {
    private val idle = PhoneState(linkUp = true, sig = SigState.REGISTERED)

    private fun inCall(codec: Int? = CodecId.CODEC2_1200, id: Long = 7) = idle.copy(
        sig = SigState.IN_CALL,
        call = Call(id, Direction.OUTGOING, "+883160655500100", CallPhase.CONNECTED, codec = codec),
    )

    /** What [org.opencell.core.fakes.FakeCodec] makes of a block whose samples are all [v]. */
    private fun payloadOf(v: Int) = ByteArray(18) { i ->
        when (i % 6) {
            0 -> (v shr 8).toByte()
            1 -> v.toByte()
            else -> 0
        }
    }

    private class Rig(
        val link: FakeLink,
        val phone: MutableStateFlow<PhoneState>,
        val codecs: FakeCodecs,
        val audio: FakeAudio,
        val mic: MutableStateFlow<Boolean>,
        val voice: VoiceSession,
    ) {
        val on: VoiceState.On get() = voice.state.value as VoiceState.On
    }

    private fun TestScope.rig(
        codecs: FakeCodecs = FakeCodecs(),
        audio: FakeAudio = FakeAudio(),
        micAllowed: Boolean = true,
    ): Rig {
        val link = FakeLink(backgroundScope, testScheduler.timeSource)
        val phone = MutableStateFlow(idle)
        val mic = MutableStateFlow(micAllowed)
        val voice = VoiceSession(link, phone, codecs, audio, backgroundScope, mic, testScheduler.timeSource)
        runCurrent()
        return Rig(link, phone, codecs, audio, mic, voice)
    }

    @Test
    fun aConnectedCallSendsOneBlockEvery120MsUntilItEnds() = runTest {
        val r = rig()
        assertEquals(VoiceState.Off, r.voice.state.value)
        r.phone.value = inCall()
        runCurrent()
        assertEquals(CodecId.CODEC2_1200, r.on.codec)
        advanceTimeBy(1_210)
        assertEquals(10, r.link.writes.size) // at 120, 240, … 1200 ms
        r.link.writes.forEachIndexed { i, w -> assertArrayEquals(payloadOf(1000 + i), w) }
        val gaps = r.link.writeTimes.zipWithNext { a, b -> (b - a).inWholeMilliseconds }.toSet()
        assertEquals(setOf(120L), gaps)
        assertEquals(10, r.on.stats.sent)
        assertTrue(r.on.mic)
        assertTrue(r.on.output)

        r.phone.value = r.phone.value.copy(call = r.phone.value.call!!.copy(phase = CallPhase.ENDED, causeCode = 0))
        runCurrent()
        assertEquals(VoiceState.Off, r.voice.state.value)
        advanceTimeBy(1_000)
        assertEquals(10, r.link.writes.size)
        assertTrue(r.codecs.made.size == 2 && r.codecs.made.all { it.closed }) // an encoder and a decoder
        assertTrue(r.audio.mics.single().closed)
        assertTrue(r.audio.speakers.single().closed)
    }

    @Test
    fun notNowDropsTheBlockAndNeverRetriesIt() = runTest {
        val r = rig()
        r.link.results.addAll(listOf(WriteResult.Accepted, WriteResult.NotNow, WriteResult.Accepted))
        r.phone.value = inCall()
        advanceTimeBy(370)
        assertEquals(3, r.link.writes.size)
        assertArrayEquals(payloadOf(1002), r.link.writes[2]) // the refused 1001 is gone
        assertEquals(2, r.on.stats.sent)
        assertEquals(1, r.on.stats.dropped)
    }

    @Test
    fun aSlowWriteDropsOldBlocksInsteadOfFallingBehind() = runTest {
        val r = rig()
        r.link.writeLatency = 300.milliseconds
        r.phone.value = inCall()
        advanceTimeBy(1_500)
        // Writes start at 120, 420, 720, 1020, 1320: each takes the newest block, never a queue of old ones.
        assertEquals(listOf(120L, 420L, 720L, 1020L, 1320L), r.link.writeTimes.map { (it - r.link.writeTimes[0]).inWholeMilliseconds + 120 })
        assertArrayEquals(payloadOf(1000), r.link.writes[0])
        assertArrayEquals(payloadOf(1002), r.link.writes[1]) // 1001 was ready at 240, overtaken at 360
        assertTrue(r.on.stats.late >= 4)
    }

    @Test
    fun mutedSendsEncodedSilence() = runTest {
        val r = rig()
        r.phone.value = inCall()
        advanceTimeBy(130)
        r.voice.setMuted(true)
        assertTrue(r.on.muted)
        advanceTimeBy(240)
        assertArrayEquals(payloadOf(1000), r.link.writes[0])
        assertArrayEquals(payloadOf(0), r.link.writes[1])
        assertArrayEquals(payloadOf(0), r.link.writes[2])
        r.voice.setMuted(false)
        advanceTimeBy(120)
        assertArrayEquals(payloadOf(1003), r.link.writes[3])
    }

    @Test
    fun withoutTheMicrophoneSilenceGoesOutUntilItIsAllowed() = runTest {
        val r = rig(micAllowed = false)
        r.phone.value = inCall()
        advanceTimeBy(370)
        assertEquals(3, r.link.writes.size) // paced by a timer instead
        assertTrue(r.link.writes.all { it.contentEquals(payloadOf(0)) })
        assertFalse(r.on.mic)
        assertTrue(r.audio.mics.isEmpty())

        r.mic.value = true
        advanceTimeBy(250)
        assertTrue(r.on.mic)
        assertArrayEquals(payloadOf(1000), r.link.writes.last())
    }

    @Test
    fun aMicrophoneThatFailsLeavesSilenceAndIsNotReopenedInALoop() = runTest {
        val r = rig(audio = FakeAudio(micWorks = false))
        r.phone.value = inCall()
        advanceTimeBy(610)
        assertEquals(5, r.link.writes.size)
        assertFalse(r.on.mic)

        r.audio.micWorks = true
        advanceTimeBy(600)
        assertTrue(r.audio.mics.isEmpty()) // tried once; again only when the microphone is allowed afresh
        r.mic.value = false
        advanceTimeBy(120)
        r.mic.value = true
        advanceTimeBy(250)
        assertTrue(r.on.mic)
    }

    @Test
    fun downlinkIsPlayedThroughTheJitterBuffer() = runTest {
        val r = rig()
        r.phone.value = inCall()
        runCurrent()
        val speaker = r.audio.speakers.single()
        for (i in 0 until 5) {
            r.link.emitDown(payloadOf(200 + i))
            advanceTimeBy(120)
        }
        r.link.emitDown(ByteArray(9)) // a test frame from an older app
        advanceTimeBy(600)
        // Silence until two blocks wait, then the five blocks in order, then fading concealment.
        val heard = speaker.firstSamples.dropWhile { it == 0 }
        assertEquals(listOf(200, 201, 202, 203, 204, 102, 51), heard.take(7))
        assertEquals(5, r.on.stats.received)
        assertEquals(1, r.on.stats.notVoice)
        assertEquals(5, r.on.stats.jitter.played)
        assertEquals(2, r.on.stats.jitter.concealed)
    }

    @Test
    fun aCodecThisBuildLacksMeansNoAudio() = runTest {
        val r = rig()
        r.phone.value = inCall(codec = 9)
        advanceTimeBy(1_000)
        assertEquals(VoiceState.Unsupported(9), r.voice.state.value)
        assertTrue(r.link.writes.isEmpty())
        assertTrue(r.audio.mics.isEmpty())
    }

    @Test
    fun aCallKnownOnlyFromStatusUsesCodec2x1200() = runTest {
        val r = rig()
        r.phone.value = inCall(codec = null)
        runCurrent()
        assertEquals(CodecId.CODEC2_1200, r.on.codec)
    }

    @Test
    fun theLinkGoingDownStopsVoiceAndItComesBackWithTheLink() = runTest {
        val r = rig()
        r.phone.value = inCall()
        advanceTimeBy(250)
        r.phone.value = r.phone.value.copy(linkUp = false)
        runCurrent()
        assertEquals(VoiceState.Off, r.voice.state.value)
        advanceTimeBy(1_000)
        assertEquals(2, r.link.writes.size)
        r.phone.value = r.phone.value.copy(linkUp = true)
        advanceTimeBy(130)
        assertEquals(3, r.link.writes.size)
        assertEquals(4, r.codecs.made.size) // fresh codecs for the restart
    }

    @Test
    fun muteLastsOnlyForTheCall() = runTest {
        val r = rig()
        r.phone.value = inCall(id = 1)
        runCurrent()
        r.voice.setMuted(true)
        r.phone.value = idle
        runCurrent()
        r.phone.value = inCall(id = 2)
        runCurrent()
        assertFalse(r.on.muted)
    }
}
