package org.opencell.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Looper
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.OpenCellApplication
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.phone.CallPhase
import org.opencell.core.protocol.ActivationQr
import org.opencell.core.protocol.QrParse
import org.opencell.core.protocol.SigState
import org.opencell.core.sim.SimulatedTerminal
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** [LinkService]'s ring collector, and that it stops ringing no matter how the service is destroyed. */
@RunWith(AndroidJUnit4::class)
class LinkServiceTest {
    private val app: OpenCellApplication get() = ApplicationProvider.getApplicationContext()
    private val phone get() = app.graph.session.phone
    private val vibrator get() = app.getSystemService(Vibrator::class.java)
    private val nm get() = app.getSystemService(android.app.NotificationManager::class.java)
    private val link get() = app.graph.session.link
    private val prefs get() = app.getSharedPreferences("opencell", Context.MODE_PRIVATE)
    private val demo = LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal")
    private val MIC_AND_LINK = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @After
    fun tearDown() {
        app.graph.repository.disconnect()
        app.graph.mainActivityInFront.value = false
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) {
                throw AssertionError("timed out waiting for $what; link: ${link.state.value}, phone: ${phone.state.value}")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun onDestroyStopsTheRingerAndTheNotificationEvenWithoutAnOrdinaryStop() {
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        // Set up the ringing call *before* the service starts: LinkService's collector's first,
        // synchronous subscription then already sees it ringing (a live update reaching it later,
        // with the main looper idled, is covered by the test below).
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

    /**
     * The ring collector itself, live: a call that arrives while the service runs starts the
     * ringer and posts the notification, and rejecting it stops both. lifecycleScope runs on the
     * main looper, which Robolectric runs when the test idles it.
     */
    @Test
    fun ringsAndNotifiesForACallThatArrivesWhileTheServiceRunsAndStopsWhenItEnds() {
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        app.graph.repository.connect(LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal"))
        waitFor("not activated") { phone.state.value.linkUp && phone.state.value.sig == SigState.NOT_ACTIVATED }
        phone.activate((ActivationQr.parse(app.graph.simulator.demoQrText()) as QrParse.Ok).qr)
        waitFor("registered") { phone.state.value.sig == SigState.REGISTERED }

        val controller = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java)).create()
        controller.get().onStartCommand(Intent(app, LinkService::class.java), 0, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("no call, no ring", shadowOf(vibrator).isVibrating)

        assertTrue(app.graph.simulator.incomingCall(SimulatedTerminal.PEER))
        waitFor("ringing") { phone.state.value.call?.phase == CallPhase.INCOMING }
        waitFor("the ringer to start") {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(vibrator).isVibrating
        }
        assertTrue("the call notification is posted", shadowOf(nm).getNotification(CallNotifier.NOTIFICATION_ID) != null)

        phone.reject()
        waitFor("rejected") { phone.state.value.call?.phase == CallPhase.ENDED }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the vibration is cancelled", shadowOf(vibrator).isCancelled)
        assertFalse("the call notification is gone", shadowOf(nm).getNotification(CallNotifier.NOTIFICATION_ID) != null)
        controller.destroy()
    }

    /**
     * The microphone type (voice spec §5.7): taken during a call while the app is in front with
     * RECORD_AUDIO, kept in the background, given up when the call ends.
     */
    @Test
    fun holdsTheMicrophoneTypeDuringACallOnly() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = app.graph.callAudio
        audio.refreshPermission(app)
        app.graph.repository.connect(demo)
        waitFor("not activated") { phone.state.value.linkUp && phone.state.value.sig == SigState.NOT_ACTIVATED }
        phone.activate((ActivationQr.parse(app.graph.simulator.demoQrText()) as QrParse.Ok).qr)
        waitFor("registered") { phone.state.value.sig == SigState.REGISTERED }
        val controller = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java)).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, LinkService::class.java), 0, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)

        app.graph.mainActivityInFront.value = true
        assertEquals(null, phone.dial(SimulatedTerminal.PEER))
        waitFor("the microphone type") {
            shadowOf(Looper.getMainLooper()).idle()
            audio.micAllowed.value
        }
        assertEquals(MIC_AND_LINK, service.foregroundServiceType)

        app.graph.mainActivityInFront.value = false // the user switches away: the call keeps the microphone
        waitFor("connected") { phone.state.value.call?.phase == CallPhase.CONNECTED }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MIC_AND_LINK, service.foregroundServiceType)

        phone.hangup()
        waitFor("ended") { phone.state.value.call?.phase == CallPhase.ENDED }
        waitFor("the microphone type given up") {
            shadowOf(Looper.getMainLooper()).idle()
            !audio.micAllowed.value
        }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        controller.destroy()
    }

    /** Without the app in front the type can't be taken: the call goes on without the microphone. */
    @Test
    fun aCallThatStartsInTheBackgroundHasNoMicrophone() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.graph.callAudio.refreshPermission(app)
        app.graph.repository.connect(demo)
        waitFor("not activated") { phone.state.value.linkUp && phone.state.value.sig == SigState.NOT_ACTIVATED }
        phone.activate((ActivationQr.parse(app.graph.simulator.demoQrText()) as QrParse.Ok).qr)
        waitFor("registered") { phone.state.value.sig == SigState.REGISTERED }
        val controller = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java)).create()
        controller.get().onStartCommand(Intent(app, LinkService::class.java), 0, 0)
        assertTrue(app.graph.simulator.incomingCall(SimulatedTerminal.PEER))
        waitFor("ringing") { phone.state.value.call?.phase == CallPhase.INCOMING }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.graph.callAudio.micAllowed.value)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, controller.get().foregroundServiceType)
        phone.reject()
        waitFor("rejected") { phone.state.value.call?.phase == CallPhase.ENDED }
        controller.destroy()
    }

    /** A link that ended in a pairing failure is remembered for a restart; a good connect forgets it. */
    @Test
    fun aPairingFailureIsRememberedForARestart() {
        app.graph.simulator.failNextConnect = PairingProblem.FAILED
        app.graph.repository.connect(demo)
        waitFor("pairing failed") { link.state.value is LinkState.PairingFailed }
        waitFor("remembered") { prefs.getBoolean("pairing_halted", false) }
        app.graph.repository.connect(demo) // Retry
        waitFor("connected") { link.state.value.isConnected }
        assertFalse(prefs.getBoolean("pairing_halted", true))
    }

    /**
     * START_STICKY brings the service back after the process died with pairing failed or
     * unfinished: it must not connect (that pops a system pairing prompt nobody answers and
     * costs one of the terminal's 3 tries), but wait for the user's Retry.
     */
    @Test
    fun aStickyRestartAfterAPairingFailureWaitsForRetry() {
        restartedWith(pairingHalted = true)
        waitFor("pairing failed") { link.state.value is LinkState.PairingFailed }
        Thread.sleep(1_000) // longer than the demo terminal takes to connect
        assertTrue("${link.state.value}", link.state.value is LinkState.PairingFailed)
        assertEquals(demo.address, app.graph.repository.wanted.value?.address)
    }

    @Test
    fun aStickyRestartOtherwiseResumesTheLink() {
        restartedWith(pairingHalted = false)
        waitFor("connected") { link.state.value.isConnected }
    }

    private fun restartedWith(pairingHalted: Boolean) {
        prefs.edit()
            .putString("last_address", demo.address)
            .putString("last_name", demo.name)
            .putBoolean("resume", true)
            .putBoolean("pairing_halted", pairingHalted)
            .commit()
        val controller = Robolectric.buildService(LinkService::class.java).create()
        controller.get().onStartCommand(null, 0, 1) // the system's restart of a sticky service
    }
}
