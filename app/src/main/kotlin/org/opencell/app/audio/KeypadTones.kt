package org.opencell.app.audio

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the keypad sounds each key (⋮ > Keypad tones on the Phone tab). On by default, like the stock dialer. */
class KeypadTones(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit { putBoolean(KEY, on) }
        _enabled.value = on
    }

    private companion object {
        const val KEY = "keypad_tones"
    }
}

/** Plays one keypad key's tone. */
fun interface KeySound {
    fun play(key: Char)
}
