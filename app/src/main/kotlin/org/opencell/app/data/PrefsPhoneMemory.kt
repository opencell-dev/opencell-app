package org.opencell.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.opencell.core.phone.PhoneMemory
import org.opencell.core.phone.Remembered
import org.opencell.core.protocol.RegMode

/**
 * Remembers each terminal's number and mode across app restarts, so the home
 * screen can show them before the next REGISTERED event (the terminal only
 * reports its number in ACTIVATED and REGISTERED). Display only: no keys.
 */
class PrefsPhoneMemory(private val prefs: SharedPreferences) : PhoneMemory {
    override fun load(address: String): Remembered? {
        val number = prefs.getString("number_$address", null) ?: return null
        return Remembered(number, RegMode.fromCode(prefs.getInt("mode_$address", 0)))
    }

    override fun save(address: String, remembered: Remembered) {
        prefs.edit {
            putString("number_$address", remembered.number)
            putInt("mode_$address", remembered.mode?.code ?: 0)
        }
    }

    override fun clear(address: String) {
        prefs.edit {
            remove("number_$address")
            remove("mode_$address")
        }
    }
}
