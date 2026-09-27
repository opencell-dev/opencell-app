package org.opencell.app.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * The phone's ring for an incoming call: the default ringtone, looped, and a
 * repeating vibration pattern. [LinkService] is the only thing that drives
 * this, and does so regardless of which screen is visible — the call
 * notification ([CallNotifier]) is silent, and neither the in-app call
 * screen nor [org.opencell.app.ui.CallActivity] make any sound of their own,
 * so without this the phone would wake to a call it can't be heard ringing
 * for. Honours [AudioManager.getRingerMode]: SILENT rings neither, VIBRATE
 * only vibrates, NORMAL does both. Honours Do Not Disturb too: while any
 * interruption filter other than [NotificationManager.INTERRUPTION_FILTER_ALL]
 * is on (read when the ring starts) it stays silent — the call notification
 * still posts. [start] and [stop] are each idempotent.
 */
class CallRinger(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var ringtone: Ringtone? = null
    private var ringing = false

    fun start() {
        if (ringing) return
        ringing = true
        val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> {
                vibrate()
                playRingtone()
            }
        }
    }

    fun stop() {
        if (!ringing) return
        ringing = false
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }

    /**
     * Without usage attributes on the vibration itself, Android doesn't apply the user's
     * ring-vibration intensity setting or Do Not Disturb's call-vibration rules to it. The
     * `AudioAttributes` overload is deprecated in favour of `VibrationAttributes`, but that one
     * only exists from API 33; minSdk is 31, so 31-32 still need the older overload.
     */
    @Suppress("DEPRECATION")
    private fun vibrate() {
        runCatching {
            val effect = VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                vibrator?.vibrate(effect, VIBRATION_AUDIO_ATTRIBUTES)
            }
        }.onFailure { Log.w(TAG, "can't vibrate", it) }
    }

    private fun playRingtone() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = RINGTONE_ATTRIBUTES
                isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "can't play the ringtone", it) }
    }

    companion object {
        private const val TAG = "CallRinger"

        /** No delay, 1 s on, 1 s off, repeating from index 0 (VibrationEffect's repeat index). */
        private val PATTERN = longArrayOf(0, 1000, 1000)
        private const val REPEAT_FROM_INDEX = 0
        private val RINGTONE_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Pre-Android 13's way to say "this is the ring vibration" (13+ uses [VibrationAttributes] instead). */
        private val VIBRATION_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
    }
}
