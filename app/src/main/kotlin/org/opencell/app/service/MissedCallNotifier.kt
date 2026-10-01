package org.opencell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.opencell.app.R
import org.opencell.app.ui.MainActivity
import org.opencell.core.calllog.CallLog
import org.opencell.core.calllog.CallLogDisplay

/**
 * The missed-call notification (dial-and-recents spec §6.2): one notification
 * while there are missed calls not yet seen in Recents, "Missed call" / "3
 * missed calls" with the latest caller, silent (the ring just happened), on
 * its own channel. Tapping it opens the Phone tab on Recents; Recents being
 * seen clears the count and so the notification. It runs in the app's scope,
 * not the link service, so a call missed because the user disconnected still
 * shows.
 *
 * It is posted only for a call missed since the last one it told about (the
 * newest unseen id, kept in [prefs]): one the user swiped away doesn't come
 * back when the app starts again, only with the next missed call.
 */
class MissedCallNotifier(
    private val context: Context,
    private val log: CallLog,
    scope: CoroutineScope,
    private val prefs: SharedPreferences,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        scope.launch {
            log.entries.collect { entries ->
                val unseen = entries.filter { !it.seen }
                if (unseen.isEmpty()) {
                    nm.cancel(NOTIFICATION_ID)
                    // Ids start again after a clear: the next missed call must count as new.
                    if (notifiedId() != 0L) prefs.edit { putLong(KEY_NOTIFIED, 0L) }
                } else {
                    val newest = unseen.maxOf { it.id }
                    when {
                        newest > notifiedId() -> {
                            post(unseen.size)
                            prefs.edit { putLong(KEY_NOTIFIED, newest) }
                        }
                        // Still showing (not swiped away) and the count changed (a delete): keep it right.
                        nm.activeNotifications.any { it.id == NOTIFICATION_ID } -> post(unseen.size)
                    }
                }
            }
        }
    }

    private fun notifiedId(): Long = prefs.getLong(KEY_NOTIFIED, 0L)

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
        private const val KEY_NOTIFIED = "missed_notified_id"
    }
}
