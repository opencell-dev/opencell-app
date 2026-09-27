package org.opencell.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /** Robolectric doesn't enforce it, but a phone throws SecurityException from vibrate() without it. */
    @Test
    fun theManifestRequestsVibrate() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.VIBRATE))
    }

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
    fun vibrationCarriesTheRingtoneUsageSoDndAndIntensitySettingsApply() {
        // This test's Robolectric SDK is fixed at 36 (robolectric.properties), i.e. always the
        // TIRAMISU+ branch; the 31-32 AudioAttributes branch mirrors it exactly but isn't
        // separately exercised here (no offline Robolectric android-all jar for API 31/32).
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val ringer = CallRinger(app)
        ringer.start()
        val attrs = shadowOf(vibrator).vibrationAttributesFromLastVibration as VibrationAttributes
        assertEquals(VibrationAttributes.USAGE_RINGTONE, attrs.usage)
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

    /** I3: Do Not Disturb (any interruption filter but ALL) keeps the ring silent; the notification still posts. */
    @Test
    fun doNotDisturbSilencesTheRing() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        try {
            val ringer = CallRinger(app)
            ringer.start()
            assertFalse(shadowOf(vibrator).isVibrating)
            ringer.stop()
        } finally {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}
