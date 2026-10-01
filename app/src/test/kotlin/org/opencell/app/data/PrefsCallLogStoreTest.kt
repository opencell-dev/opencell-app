package org.opencell.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.audio.KeypadTones
import org.opencell.core.calllog.CallLog
import org.opencell.core.phone.Direction
import org.opencell.core.phone.FinishedCall

/** The call log and the keypad-tones setting in the app's preferences (dial-and-recents spec §4.3, §3.4). */
@RunWith(AndroidJUnit4::class)
class PrefsCallLogStoreTest {
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(name, Context.MODE_PRIVATE)
        .also { it.edit(commit = true) { clear() } }

    @Test
    fun theLogSurvivesARestartAndClearingRemovesIt() {
        val prefs = prefs("call_log_test")
        val log = CallLog(PrefsCallLogStore(prefs))
        log.record(FinishedCall(Direction.INCOMING, "+883160655500100", null, 1_000, null, 2_000, 0, null, false), null)
        val again = CallLog(PrefsCallLogStore(prefs))
        assertEquals(log.entries.value, again.entries.value)
        assertEquals(1, again.unseenMissed.value)
        again.clear()
        assertFalse(prefs.contains("call_log"))
        assertTrue(CallLog(PrefsCallLogStore(prefs)).entries.value.isEmpty())
    }

    @Test
    fun keypadTonesAreOnUntilTurnedOff() {
        val prefs = prefs("keypad_tones_test")
        assertTrue(KeypadTones(prefs).enabled.value)
        KeypadTones(prefs).set(false)
        assertFalse(KeypadTones(prefs).enabled.value)
    }
}
