package org.opencell.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.opencell.core.calllog.CallLogStore

/**
 * The call log's text ([org.opencell.core.calllog.CallLogCodec]) in its own
 * preferences file, `opencell_calls`, apart from the settings. Local only: the
 * app sets `allowBackup="false"`, so it never leaves the phone.
 */
class PrefsCallLogStore(private val prefs: SharedPreferences) : CallLogStore {
    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(text: String?) = prefs.edit {
        if (text == null) remove(KEY) else putString(KEY, text)
    }

    companion object {
        const val FILE = "opencell_calls"
        private const val KEY = "call_log"
    }
}
