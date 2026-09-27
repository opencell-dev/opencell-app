package org.opencell.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.service.CallActionReceiver
import org.opencell.app.service.CallNotifier
import org.opencell.app.ui.CallActivity
import org.opencell.core.link.LinkTarget
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.EndCause
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.SigState
import org.opencell.core.sim.SimulatedTerminal
import org.robolectric.Shadows.shadowOf

/** The background path of an incoming call: the notification, and its Answer and Reject actions. */
@RunWith(AndroidJUnit4::class)
class IncomingCallTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val phone get() = app.graph.session.phone
    private val nm get() = app.getSystemService(NotificationManager::class.java)

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

    /** The demo terminal, activated and registered, with a call from the test peer ringing. */
    private fun ringingDemoCall() {
        app.graph.repository.connect(LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal"))
        waitFor("not activated") { phone.state.value.linkUp && phone.state.value.sig == SigState.NOT_ACTIVATED }
        phone.activate((ActivationQr.parse(app.graph.simulator.demoQrText()) as QrParse.Ok).qr)
        waitFor("registered") { phone.state.value.sig == SigState.REGISTERED }
        assertTrue(app.graph.simulator.incomingCall(SimulatedTerminal.PEER))
        waitFor("ringing") { phone.state.value.call?.phase == CallPhase.INCOMING }
    }

    @Test
    fun notificationRingsFullScreenAndCancels() {
        val notifier = CallNotifier(app)
        notifier.showIncoming(Call(1, Direction.INCOMING, "+8836065550100", CallPhase.INCOMING))
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(Notification.CATEGORY_CALL, posted.category)
        assertNotNull(posted.fullScreenIntent)
        // CallStyle titles the notification with the caller.
        assertEquals("+883 606 555 0100", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("rings until handled", posted.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(CallNotifier.CHANNEL_ID, posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(CallNotifier.CHANNEL_ID).importance)
        notifier.cancel()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun callerUnknownAfterAResyncStillRings() {
        val n = CallNotifier(app).build(Call(null, Direction.INCOMING, null, CallPhase.INCOMING))
        assertEquals("Unknown caller", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun answerOpensTheCallScreenAndAnswers() {
        ringingDemoCall()
        val scenario = ActivityScenario.launch<CallActivity>(
            Intent(app, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER),
        )
        waitFor("connected") { phone.state.value.call?.phase == CallPhase.CONNECTED }
        scenario.close()
    }

    @Test
    fun rejectFromTheNotificationRejects() {
        ringingDemoCall()
        CallActionReceiver().onReceive(app, Intent(CallActionReceiver.ACTION_REJECT))
        waitFor("rejected") { phone.state.value.call?.cause == EndCause.REJECTED }
    }
}
