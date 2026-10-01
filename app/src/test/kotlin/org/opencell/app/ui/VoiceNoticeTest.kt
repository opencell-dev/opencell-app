package org.opencell.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencell.core.voice.CodecId
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
}
