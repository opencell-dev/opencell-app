package org.opencell.app

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.audio.VoiceCodecSetting
import org.opencell.app.ui.MainActivity
import org.opencell.core.voice.CodecId
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.CodecPreference
import org.robolectric.annotation.Config

/** The voice codec setting (codec-negotiation spec §7.1), built like the call tones setting. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class VoiceCodecTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val codecs = listOf(CodecInfo(CodecId.CODEC2_1200), CodecInfo(7, experimental = true))

    private fun freshPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("voice_codec_test", Context.MODE_PRIVATE)
        .also { it.edit(commit = true) { clear() } }

    @After
    fun tearDown() {
        compose.activity.graph.voiceCodec.set(CodecPreference.Automatic)
        compose.activity.graph.repository.disconnect()
    }

    @Test
    fun automaticByDefaultAndAChoiceIsKept() {
        val prefs = freshPrefs()
        assertEquals(CodecPreference.Automatic, VoiceCodecSetting(prefs, codecs).choice.value)
        VoiceCodecSetting(prefs, codecs).set(CodecPreference.Only(7))
        assertEquals(CodecPreference.Only(7), VoiceCodecSetting(prefs, codecs).choice.value) // kept across restarts
        VoiceCodecSetting(prefs, codecs).set(CodecPreference.Automatic)
        assertEquals(CodecPreference.Automatic, VoiceCodecSetting(prefs, codecs).choice.value)
    }

    /** §7.1: an unknown stored id reads as Automatic (a codec removed from a later build, or garbage). */
    @Test
    fun aCodecThisBuildLacksReadsAsAutomatic() {
        val prefs = freshPrefs()
        VoiceCodecSetting(prefs, codecs).set(CodecPreference.Only(7))
        assertEquals(CodecPreference.Automatic, VoiceCodecSetting(prefs, codecs.take(1)).choice.value)
        prefs.edit(commit = true) { putInt("voice_codec", 200) }
        assertEquals(CodecPreference.Automatic, VoiceCodecSetting(prefs, codecs).choice.value)
    }

    @Test
    fun thePhoneMenuChoosesTheCodec() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Voice codec").performClick()
        compose.onNodeWithText("Automatic").assertExists()
        compose.onNodeWithText("Only Codec2 1200").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(CodecPreference.Only(CodecId.CODEC2_1200), compose.activity.graph.voiceCodec.choice.value)
        assertEquals(0x01, compose.activity.graph.session.phone.allowedCaps())
    }

    /** OpenVoxNB spec V3: the dialog lists OpenVoxNB, labelled experimental, and Automatic still leaves it out. */
    @Test
    fun theDialogLabelsOpenVoxNbAsExperimental() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Voice codec").performClick()
        compose.onNodeWithText("Only Codec2 1200").assertExists()
        compose.onNodeWithText("Only OpenVoxNB 600 (experimental)").assertExists()
        compose.onNodeWithText("Only OpenVoxNB 1200 (experimental)").performClick()
        compose.onNodeWithText("Done").performClick()
        val graph = compose.activity.graph
        assertEquals(CodecPreference.Only(CodecId.OVX_1200), graph.voiceCodec.choice.value)
        assertEquals(0x20, graph.session.phone.allowedCaps())
        graph.voiceCodec.set(CodecPreference.Automatic)
        assertEquals(0x01, graph.session.phone.allowedCaps()) // experimental: not in Automatic
    }
}
