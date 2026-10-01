package org.opencell.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.core.dev.DeveloperAccess

/** Persists the developer unlock like any other setting (`TonePlanSetting`'s pattern). */
@RunWith(AndroidJUnit4::class)
class DeveloperUnlockTest {
    private fun freshPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("developer_unlock_test", Context.MODE_PRIVATE)
        .also { it.edit(commit = true) { clear() } }

    @Test
    fun startsLocked() {
        val unlock = DeveloperUnlock(freshPrefs())
        assertFalse(unlock.unlocked.value)
    }

    @Test
    fun theCorrectCodeUnlocks() {
        val unlock = DeveloperUnlock(freshPrefs())
        assertTrue(unlock.tryUnlock(DeveloperAccess.CODE))
        assertTrue(unlock.unlocked.value)
    }

    @Test
    fun aWrongCodeLeavesItLocked() {
        val unlock = DeveloperUnlock(freshPrefs())
        assertFalse(unlock.tryUnlock("00000000"))
        assertFalse(unlock.unlocked.value)
    }

    @Test
    fun unlockingIsRememberedAcrossInstances() {
        val prefs = freshPrefs()
        DeveloperUnlock(prefs).tryUnlock(DeveloperAccess.CODE)
        assertTrue(DeveloperUnlock(prefs).unlocked.value)
    }

    @Test
    fun lockClearsAndIsRemembered() {
        val prefs = freshPrefs()
        val unlock = DeveloperUnlock(prefs)
        unlock.tryUnlock(DeveloperAccess.CODE)
        unlock.lock()
        assertFalse(unlock.unlocked.value)
        assertFalse(DeveloperUnlock(prefs).unlocked.value)
    }
}
