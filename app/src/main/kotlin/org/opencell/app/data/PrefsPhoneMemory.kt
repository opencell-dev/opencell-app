package org.opencell.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.opencell.core.phone.PhoneMemory
import org.opencell.core.phone.Remembered
import org.opencell.core.protocol.PhoneNumber
import org.opencell.core.protocol.RegMode

/**
 * Remembers each terminal's number and mode across app restarts, so the home
 * screen can show them before the next REGISTERED event (the terminal only
 * reports its number in ACTIVATED and REGISTERED). Display only: no keys.
 *
 * Numbering v2 (spec §8): entries are kept under `v2_number_…` / `v2_mode_…`.
 * The keys of earlier versions (`number_…`, `mode_…`) held 13-digit numbers
 * such as `+8836065551234`, which still look well-formed under v2 (country
 * code 60), so they are recognised by their key, not their digits, and
 * deleted the first time the terminal is loaded. A v2 entry that isn't a
 * valid number is deleted too.
 */
class PrefsPhoneMemory(private val prefs: SharedPreferences) : PhoneMemory {
    override fun load(address: String): Remembered? {
        if (prefs.contains(OLD_NUMBER + address) || prefs.contains(OLD_MODE + address)) {
            prefs.edit {
                remove(OLD_NUMBER + address)
                remove(OLD_MODE + address)
            }
        }
        val number = prefs.getString(NUMBER + address, null) ?: return null
        if (!PhoneNumber.isValid(number)) {
            clear(address)
            return null
        }
        return Remembered(number, RegMode.fromCode(prefs.getInt(MODE + address, 0)))
    }

    override fun save(address: String, remembered: Remembered) {
        prefs.edit {
            putString(NUMBER + address, remembered.number)
            putInt(MODE + address, remembered.mode?.code ?: 0)
        }
    }

    override fun clear(address: String) {
        prefs.edit {
            remove(NUMBER + address)
            remove(MODE + address)
        }
    }

    private companion object {
        const val NUMBER = "v2_number_"
        const val MODE = "v2_mode_"
        const val OLD_NUMBER = "number_"
        const val OLD_MODE = "mode_"
    }
}
