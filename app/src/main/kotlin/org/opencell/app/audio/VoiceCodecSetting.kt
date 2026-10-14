package org.opencell.app.audio

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencell.core.voice.CodecId
import org.opencell.core.voice.CodecInfo
import org.opencell.core.voice.CodecPreference

/**
 * Which voice codecs this phone offers when it calls and accepts when it answers
 * (codec-negotiation spec §7.1): Automatic (the default), or only one of the
 * [supported] codecs. Kept in the app's preferences, like [TonePlanSetting]; set
 * from the Phone tab's menu. A stored codec this build lacks reads as Automatic.
 */
class VoiceCodecSetting(private val prefs: SharedPreferences, val supported: List<CodecInfo>) {
    private val _choice = MutableStateFlow(resolve(prefs.getInt(KEY, CodecId.NONE)))
    val choice: StateFlow<CodecPreference> = _choice.asStateFlow()

    fun set(choice: CodecPreference) {
        val id = (choice as? CodecPreference.Only)?.codecId ?: CodecId.NONE
        prefs.edit { putInt(KEY, id) }
        _choice.value = resolve(id)
    }

    /** [CodecId.NONE] stands for Automatic in the preferences. */
    private fun resolve(id: Int): CodecPreference =
        if (supported.any { it.id == id }) CodecPreference.Only(id) else CodecPreference.Automatic

    private companion object {
        const val KEY = "voice_codec"
    }
}
