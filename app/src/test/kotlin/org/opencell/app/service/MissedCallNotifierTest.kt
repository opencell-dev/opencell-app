package org.opencell.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.OpenCellApplication
import org.opencell.core.calllog.CallLog
import org.opencell.core.phone.Direction
import org.opencell.core.phone.FinishedCall
import org.opencell.core.protocol.EndCause
import org.robolectric.Shadows.shadowOf

/** The missed-call notification comes back only for a call missed since the last one (dial-and-recents spec §6.2). */
@RunWith(AndroidJUnit4::class)
class MissedCallNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<OpenCellApplication>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val prefs = app.getSharedPreferences("missed-test", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var log: CallLog

    @Before
    fun setUp() {
        app.graph.callLog.clear() // the app's own notifier stays quiet
        prefs.edit().clear().commit()
        log = CallLog()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun missed() {
        val now = System.currentTimeMillis()
        log.record(FinishedCall(Direction.INCOMING, "+883160655500100", null, now - 30_000, null, now, EndCause.NORMAL.code, null, false), null)
    }

    private fun shown(): List<Notification> {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(nm).allNotifications.filter { it.channelId == MissedCallNotifier.CHANNEL_ID }
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun aDismissedNotificationStaysGoneUntilAnotherCallIsMissed() {
        MissedCallNotifier(app, log, scope, prefs)
        missed()
        assertEquals("Missed call", title(shown().single()))
        nm.cancel(MissedCallNotifier.NOTIFICATION_ID) // swiped away, Recents not opened
        assertTrue(shown().isEmpty())

        // The process starts again with the same call still unseen: nothing new to tell.
        MissedCallNotifier(app, log, scope, prefs)
        assertTrue(shown().isEmpty())

        // Another call is missed: one notification, counting both.
        missed()
        assertEquals("2 missed calls", title(shown().single()))

        // Seen in Recents: gone; the next missed call is new again, even with ids restarting after a clear.
        log.markMissedSeen()
        assertTrue(shown().isEmpty())
        log.clear()
        missed()
        assertEquals("Missed call", title(shown().single()))
    }
}
