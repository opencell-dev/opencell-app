package org.opencell.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.voice.CodecId
import org.opencell.core.voice.DecoderStats
import org.opencell.core.voice.JitterCounts
import org.opencell.core.voice.VoiceStats
import org.opencell.core.voice.VoiceState

/** Plain JVM: the call screen's line about the microphone and the audio devices. */
class VoiceNoticeTest {
    private val working = VoiceState.On(CodecId.CODEC2_1200, mic = true, output = true)

    @Test
    fun aFailedAudioDeviceSaysSoInsteadOfBlamingTheScreen() {
        val failing = "Audio device failed, retrying…" to true
        assertEquals(failing, voiceNotice(working.copy(mic = false, micFailed = true), granted = true))
        assertEquals(failing, voiceNotice(working.copy(output = false, outputFailed = true), granted = true))
    }

    @Test
    fun theOtherNotices() {
        assertEquals(true, voiceNotice(working.copy(mic = false), granted = false)?.second)
        assertEquals(
            "Microphone off: it starts when OpenCell is open on the screen." to true,
            voiceNotice(working.copy(mic = false), granted = true),
        )
        assertEquals("Muted: the other side hears silence." to false, voiceNotice(working.copy(muted = true), granted = true))
        assertNull(voiceNotice(working, granted = true))
    }

    /** §7.4: the call screen names the codec CONNECTED carried. */
    @Test
    fun theConnectedLineNamesTheCodec() {
        val call = Call(5, Direction.OUTGOING, null, CallPhase.CONNECTED, connectedAt = 0L, codec = CodecId.CODEC2_1200)
        assertTrue(connectedLine(call)!!.matches(Regex("Since .+ · Codec2 1200")))
        assertEquals("Codec2 1200", connectedLine(call.copy(connectedAt = null)))
        assertNull(connectedLine(call.copy(connectedAt = null, codec = null)))
    }

    /** Ruling R5: the developer line adds a concealing decoder's own counters. */
    @Test
    fun theDeveloperLineAddsTheDecodersCounters() {
        val s = VoiceStats(sent = 5, dropped = 1, received = 4, jitter = JitterCounts(concealed = 2))
        assertEquals("Sent 5 · not sent 1 · received 4 · concealed 2", voiceStatsLine(s))
        assertEquals(
            "Sent 5 · not sent 1 · received 4 · concealed 2 · decoder lost 3 · toggle gaps 1 · FEC fixed 7 · FEC failed 2",
            voiceStatsLine(s.copy(decoder = DecoderStats(lostBlocks = 3, toggleGaps = 1, golayFixed = 4, hammingFixed = 3, golayFailed = 1, crcFailed = 1))),
        )
    }
}
