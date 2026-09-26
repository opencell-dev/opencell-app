package org.opencell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.opencell.app.R
import org.opencell.app.graph
import org.opencell.app.ui.MainActivity
import org.opencell.core.link.LinkState
import org.opencell.core.protocol.TerminalStatus

/**
 * Foreground service (type `connectedDevice`) that keeps the process alive,
 * and so the BLE link, while the screen is off or the app is in the
 * background. The link itself lives in the app-scoped repository; this
 * service only holds the process in the foreground and shows the ongoing
 * notification. It stops itself when the user disconnects.
 */
class LinkService : LifecycleService() {
    private var watching = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val repo = graph.repository
        if (intent?.action == ACTION_DISCONNECT) {
            repo.disconnect()
        }
        if (!goForeground()) return START_NOT_STICKY

        if (intent == null && repo.wanted.value == null) {
            // Restarted by the system after the process was killed: resume the link.
            repo.resumeTarget?.let { repo.connect(it) }
        }
        if (repo.wanted.value == null) {
            stop()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            watch()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(LinkState.Disconnected, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: Exception) {
        // Missing BLUETOOTH_CONNECT, or started from the background (Android 12+).
        Log.w(TAG, "can't start foreground", e)
        stopSelf()
        false
    }

    private fun watch() {
        val repo = graph.repository
        val link = graph.session.link
        lifecycleScope.launch {
            repo.wanted.first { it == null }
            stop()
        }
        lifecycleScope.launch {
            combine(link.state, link.status) { s, st -> s to st }
                .conflate()
                .collect { (s, st) ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s, st))
                    delay(1000) // at most one notification update per second
                }
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(state: LinkState, status: TerminalStatus?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_link_description)
                    setShowBadge(false)
                },
            )
        }
        val target = state.target ?: graph.repository.wanted.value
        val title = target?.name ?: target?.address ?: getString(R.string.app_name)
        val text = when (state) {
            LinkState.Disconnected -> "Starting…"
            is LinkState.Connecting -> "Connecting…"
            is LinkState.Connected -> status?.let { "${it.stateLabel} · ${it.bandLabel} · ${it.rssiDbm} dBm" } ?: "Connected"
            is LinkState.WaitingToReconnect -> "Link lost, retrying in ${state.delay.inWholeSeconds.coerceAtLeast(1)} s"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.disconnect), disconnect)
            .build()
    }

    companion object {
        private const val TAG = "LinkService"
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "org.opencell.app.DISCONNECT"

        /** Called from the foreground (the user tapped connect), as Android 12+ requires. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "can't start service", e)
            }
        }
    }
}
