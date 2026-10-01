package org.opencell.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.opencell.core.calllog.CallLogStore

/**
 * The call log's text ([org.opencell.core.calllog.CallLogCodec]) in its own
 * preferences file, `opencell_calls`, apart from the settings. Local only:
 * `allowBackup="false"` keeps it out of cloud backup and
 * `res/xml/data_extraction_rules.xml` out of a device-to-device transfer too.
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
