package org.opencell.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.core.phone.Remembered
import org.opencell.core.protocol.RegMode

/** Numbering v2 §8: the app never shows a number remembered before numbering v2. */
@RunWith(AndroidJUnit4::class)
class PrefsPhoneMemoryTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("prefs_phone_memory_test", Context.MODE_PRIVATE)
        .also { it.edit(commit = true) { clear() } }
    private val memory = PrefsPhoneMemory(prefs)

    @Test
    fun keepsAV2Number() {
        memory.save("AA:BB", Remembered("+883160655501234", RegMode.PART15))
        assertEquals(Remembered("+883160655501234", RegMode.PART15), memory.load("AA:BB"))
    }

    @Test
    fun dropsANumberSavedBeforeNumberingV2() {
        // What the plan-6 app wrote: a 13-digit number under the old keys.
        prefs.edit(commit = true) {
            putString("number_AA:BB", "+8836065551234")
            putInt("mode_AA:BB", RegMode.PART15.code)
        }
        assertNull(memory.load("AA:BB"))
        assertFalse(prefs.contains("number_AA:BB"))
        assertFalse(prefs.contains("mode_AA:BB"))
    }

    @Test
    fun dropsAnInvalidV2Entry() {
        prefs.edit(commit = true) { putString("v2_number_AA:BB", "+88316065551234") } // CC 1, 14 digits
        assertNull(memory.load("AA:BB"))
        assertFalse(prefs.contains("v2_number_AA:BB"))
    }
}
