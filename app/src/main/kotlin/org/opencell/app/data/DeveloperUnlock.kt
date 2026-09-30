package org.opencell.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencell.core.dev.DeveloperAccess

/**
 * Whether OpenCell's developer features (Console, Loopback, the demo
 * terminal, the call screen's voice stats line) are unlocked on this phone.
 * Entering [DeveloperAccess.CODE] once unlocks them for good; kept in the
 * app's preferences, same as [org.opencell.app.audio.TonePlanSetting]. There
 * is no lockout on a wrong code (the code is a speed bump, not security).
 */
class DeveloperUnlock(private val prefs: SharedPreferences) {
    private val _unlocked = MutableStateFlow(prefs.getBoolean(KEY, false))
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** True and unlocks (remembered) if [code] is correct; false and unchanged otherwise. */
    fun tryUnlock(code: String): Boolean {
        if (!DeveloperAccess.isCorrect(code)) return false
        prefs.edit { putBoolean(KEY, true) }
        _unlocked.value = true
        return true
    }

    /** Locks again; the next unlock needs the code once more. */
    fun lock() {
        prefs.edit { putBoolean(KEY, false) }
        _unlocked.value = false
    }

    private companion object {
        const val KEY = "developer_unlocked"
    }
}
