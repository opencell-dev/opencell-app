package org.opencell.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

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
    fun notificationIsSilentFullScreenAndCancels() {
        val notifier = CallNotifier(app)
        notifier.showIncoming(Call(1, Direction.INCOMING, "+883160655500100", CallPhase.INCOMING))
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(Notification.CATEGORY_CALL, posted.category)
        assertNotNull(posted.fullScreenIntent)
        // CallStyle titles the notification with the caller.
        assertEquals("+883-1-606-555-00100", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(CallNotifier.CHANNEL_ID, posted.channelId)
        val channel = nm.getNotificationChannel(CallNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        // CallRinger is the only sound/vibration source now: the notification's own channel carries none.
        assertNull(channel.sound)
        assertEquals(90_000L, posted.timeoutAfter)
        notifier.cancel()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun doesNotSuppressAlertingViaGrouping() {
        // androidx.core's setSilent(true), with no group key set, implicitly groups the
        // notification and sets GROUP_ALERT_SUMMARY on a non-summary notification: the
        // framework's suppressAlertingDueToGrouping() then returns true, and on Android 13+
        // SystemUI suppresses the heads-up and the full-screen intent — a locked phone rings
        // but no call screen appears. The channel already carries no sound or vibration, and
        // setOnlyAlertOnce keeps a later update quiet, so the notification itself must not
        // silence or group itself.
        val posted = CallNotifier(app).build(Call(1, Direction.INCOMING, "+883160655500100", CallPhase.INCOMING))
        assertNull("no implicit group key", posted.group)
        assertEquals(Notification.GROUP_ALERT_ALL, posted.groupAlertBehavior)
        assertNotNull(posted.fullScreenIntent)
    }

    @Test
    fun ensureChannelReplacesTheOldSoundedChannel() {
        nm.createNotificationChannel(
            android.app.NotificationChannel("calls", "Old calls channel", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.net.Uri.parse("content://old-ringtone"), null)
            },
        )
        CallNotifier(app).ensureChannel()
        assertNull("the immutable, sounded channel is removed rather than reused", nm.getNotificationChannel("calls"))
        assertNotNull(nm.getNotificationChannel(CallNotifier.CHANNEL_ID))
    }

    @Test
    fun callerUnknownAfterAResyncStillRings() {
        val notifier = CallNotifier(app)
        notifier.showIncoming(Call(null, Direction.INCOMING, null, CallPhase.INCOMING))
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals("Unknown caller", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(posted.fullScreenIntent)
        notifier.cancel()
    }

    @Test
    fun answerAndRejectActionsTargetTheRightComponentsAndActions() {
        val n = CallNotifier(app).build(Call(1, Direction.INCOMING, "+883160655500100", CallPhase.INCOMING))
        val rejectAction = n.actions.first { shadowOf(it.actionIntent).savedIntent.action == CallActionReceiver.ACTION_REJECT }
        val rejectIntent = shadowOf(rejectAction.actionIntent).savedIntent
        assertEquals(CallActionReceiver::class.java.name, rejectIntent.component?.className)

        val answerAction = n.actions.first { shadowOf(it.actionIntent).savedIntent.action == CallActivity.ACTION_ANSWER }
        val answerIntent = shadowOf(answerAction.actionIntent).savedIntent
        assertEquals(CallActivity::class.java.name, answerIntent.component?.className)
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
    fun callActivityFinishesWhenThereIsNoCallToShow() {
        // No call at all when it launches: its very first (synchronous) read of the phone's
        // state already has call == null, so it finishes without needing to observe a live
        // state change (see CallEndActionTest for the ENDED-then-delay half of this decision).
        val scenario = ActivityScenario.launch<CallActivity>(Intent(app, CallActivity::class.java))
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun rejectFromTheNotificationRejects() {
        ringingDemoCall()
        CallActionReceiver().onReceive(app, Intent(CallActionReceiver.ACTION_REJECT))
        waitFor("rejected") { phone.state.value.call?.cause == EndCause.REJECTED }
    }

    @Test
    fun rejectReceiverDoesNotCancelTheNotificationItself() {
        val notifier = CallNotifier(app)
        notifier.showIncoming(Call(1, Direction.INCOMING, "+883160655500100", CallPhase.INCOMING))
        CallActionReceiver().onReceive(app, Intent(CallActionReceiver.ACTION_REJECT))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        notifier.cancel()
    }

    /** Runs what's queued on the main looper (lifecycleScope collectors) while waiting for [condition]. */
    private fun waitForMain(what: String, condition: () -> Boolean) {
        waitFor(what) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    /** M7: when CallActivity closes itself after ENDED it also dismisses the call, so MainActivity doesn't show "Call ended" again. */
    @Test
    fun callActivityDismissesTheEndedCallWhenItFinishesByItself() {
        ringingDemoCall()
        val controller = Robolectric.buildActivity(CallActivity::class.java, Intent(app, CallActivity::class.java)).setup()
        val activity = controller.get()
        phone.reject()
        waitForMain("ended") { phone.state.value.call?.phase == CallPhase.ENDED }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing) // "Call ended" shows for a moment first
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(activity.isFinishing)
        assertNull(phone.state.value.call)
        controller.pause().stop().destroy()
    }

    /** Triage: a new INCOMING while CallActivity waits to close after ENDED cancels that close. */
    @Test
    fun aNewIncomingCallCancelsCallActivitysPendingFinish() {
        ringingDemoCall()
        val controller = Robolectric.buildActivity(CallActivity::class.java, Intent(app, CallActivity::class.java)).setup()
        val activity = controller.get()
        phone.reject()
        waitForMain("ended") { phone.state.value.call?.phase == CallPhase.ENDED }
        assertTrue(app.graph.simulator.incomingCall(SimulatedTerminal.PEER))
        waitForMain("ringing again") { phone.state.value.call?.phase == CallPhase.INCOMING }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertFalse(activity.isFinishing)
        assertEquals(CallPhase.INCOMING, phone.state.value.call?.phase)
        controller.pause().stop().destroy()
    }
}
