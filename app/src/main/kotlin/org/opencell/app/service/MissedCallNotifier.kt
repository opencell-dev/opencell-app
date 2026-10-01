package org.opencell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.opencell.app.R
import org.opencell.app.ui.MainActivity
import org.opencell.core.calllog.CallLog
import org.opencell.core.calllog.CallLogDisplay

/**
 * The missed-call notification (dial-and-recents spec §6.2): one notification
 * while [CallLog.unseenMissed] is above zero, "Missed call" / "3 missed calls"
 * with the latest caller, silent (the ring just happened), on its own channel.
 * Tapping it opens the Phone tab on Recents; Recents being seen clears the
 * count and so the notification. It runs in the app's scope, not the link
 * service, so a call missed because the user disconnected still shows.
 */
class MissedCallNotifier(private val context: Context, private val log: CallLog, scope: CoroutineScope) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        scope.launch {
            log.unseenMissed.collect { n -> if (n == 0) nm.cancel(NOTIFICATION_ID) else post(n) }
        }
    }

    private fun post(count: Int) {
        ensureChannel()
        val latest = log.entries.value.firstOrNull { !it.seen }
        val title = if (count == 1) "Missed call" else "$count missed calls"
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_RECENTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(latest?.let(CallLogDisplay::title))
            .setWhen(latest?.startedAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_missed_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "missed_calls"
        const val NOTIFICATION_ID = 3
        private const val REQUEST_OPEN = 20
    }
}
