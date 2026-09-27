package org.opencell.app.service

import android.content.Intent
import android.media.AudioManager
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.OpenCellApplication
import org.opencell.core.link.LinkTarget
import org.opencell.core.phone.CallPhase
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.SigState
import org.opencell.core.sim.SimulatedTerminal
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** [LinkService] stops ringing no matter how the service is destroyed. */
@RunWith(AndroidJUnit4::class)
class LinkServiceTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val phone get() = app.graph.session.phone
    private val vibrator get() = app.getSystemService(Vibrator::class.java)
    private val nm get() = app.getSystemService(android.app.NotificationManager::class.java)

    @After
    fun tearDown() {
        app.graph.repository.disconnect()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; phone: ${phone.state.value}")
            Thread.sleep(20)
        }
    }

    @Test
    fun onDestroyStopsTheRingerAndTheNotificationEvenWithoutAnOrdinaryStop() {
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        // Set up the ringing call *before* the service starts: LinkService's collector's first,
        // synchronous subscription then already sees it ringing, without depending on a live
        // update reaching it later (see task-11-report.md's fix-round-1 note on lifecycleScope).
        app.graph.repository.connect(LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal"))
        waitFor("not activated") { phone.state.value.linkUp && phone.state.value.sig == SigState.NOT_ACTIVATED }
        phone.activate((ActivationQr.parse(app.graph.simulator.demoQrText()) as QrParse.Ok).qr)
        waitFor("registered") { phone.state.value.sig == SigState.REGISTERED }
        assertTrue(app.graph.simulator.incomingCall(SimulatedTerminal.PEER))
        waitFor("ringing") { phone.state.value.call?.phase == CallPhase.INCOMING }

        val controller = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java)).create()
        controller.get().onStartCommand(Intent(app, LinkService::class.java), 0, 0)

        assertTrue("the ringer started", shadowOf(vibrator).isVibrating)
        assertTrue("the call notification is posted", shadowOf(nm).getNotification(CallNotifier.NOTIFICATION_ID) != null)

        // Destroyed some other way than the ordinary disconnect path (stop()) — e.g. the
        // system reclaiming the service — must still silence the ringer and drop the notification.
        controller.destroy()

        assertTrue("the vibration is cancelled", shadowOf(vibrator).isCancelled)
        assertFalse("the call notification is gone", shadowOf(nm).getNotification(CallNotifier.NOTIFICATION_ID) != null)
    }
}
