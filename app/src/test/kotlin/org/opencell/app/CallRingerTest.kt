package org.opencell.app

import android.media.AudioManager
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.service.CallRinger
import org.robolectric.Shadows.shadowOf

/** [CallRinger] honours [AudioManager.getRingerMode] and is idempotent. */
@RunWith(AndroidJUnit4::class)
class CallRingerTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val audioManager get() = app.getSystemService(AudioManager::class.java)
    private val vibrator get() = app.getSystemService(Vibrator::class.java)

    @Test
    fun normalModeVibratesAndStopsOnStop() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val ringer = CallRinger(app)
        ringer.start()
        assertTrue(shadowOf(vibrator).isVibrating)
        ringer.stop()
        assertTrue(shadowOf(vibrator).isCancelled)
    }

    @Test
    fun vibrateModeOnlyVibrates() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val ringer = CallRinger(app)
        ringer.start()
        assertTrue(shadowOf(vibrator).isVibrating)
        ringer.stop()
    }

    @Test
    fun silentModeNeitherRingsNorVibrates() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val ringer = CallRinger(app)
        ringer.start()
        assertFalse(shadowOf(vibrator).isVibrating)
        ringer.stop()
    }

    @Test
    fun startIsIdempotentAndStopIsSafeWithoutStart() {
        CallRinger(app).stop() // doesn't throw when never started
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val ringer = CallRinger(app)
        ringer.start()
        ringer.start() // idempotent: a second start doesn't restart the pattern or crash
        ringer.stop()
        assertTrue(shadowOf(vibrator).isCancelled)
    }
}
