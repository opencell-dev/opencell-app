package org.opencell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.opencell.app.R
import org.opencell.app.ui.CallActivity
import org.opencell.core.phone.Call
import org.opencell.core.protocol.PhoneNumber

/**
 * The incoming-call notification, posted while the app is in the background:
 * CallStyle with Answer and Reject, a ringtone on a high-importance channel,
 * and a full-screen intent that opens [CallActivity] over the lock screen.
 */
class CallNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_calls_description)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun build(call: Call): Notification {
        val name = call.peer?.let { PhoneNumber.display(it) } ?: "Unknown caller"
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val fullScreen = PendingIntent.getActivity(
            context, REQUEST_SHOW,
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context, REQUEST_ANSWER,
            Intent(context, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reject = PendingIntent.getBroadcast(
            context, REQUEST_REJECT,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_REJECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Incoming call")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, reject, answer))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT } // ring until answered, rejected or ended
    }

    fun showIncoming(call: Call) {
        ensureChannel()
        nm.notify(NOTIFICATION_ID, build(call))
    }

    fun cancel() = nm.cancel(NOTIFICATION_ID)

    /**
     * Android 14+ grants USE_FULL_SCREEN_INTENT by default only to apps Play lists
     * as calling or alarm apps; otherwise the user allows it in Settings. Without
     * it the call shows as a heads-up notification instead of a full screen.
     */
    fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
        } else {
            true
        }

    companion object {
        const val CHANNEL_ID = "calls"
        const val NOTIFICATION_ID = 2
        private const val REQUEST_SHOW = 10
        private const val REQUEST_ANSWER = 11
        private const val REQUEST_REJECT = 12
    }
}
