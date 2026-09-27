package org.opencell.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.protocol.RegFailReason
import org.opencell.core.sim.SimulatedTerminal
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Phone tab against the demo terminal (SimulatedTerminal in the app
 * graph): activation by code, the registered home screen, a used code,
 * and deactivation. Real session, link manager and UI; no BLE.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class PhoneFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
    }

    private fun activateWith(code: String) {
        compose.onNodeWithText("Activation code (opencell:1:…)").performTextReplacement(code)
        compose.onNodeWithText("Check code").performClick()
        compose.waitForText("Valid until")
        compose.onNodeWithText("Activate").performClick()
    }

    /**
     * The service notification's "Pairing failed: open the app to retry" lands on the Phone
     * tab: it must offer Retry there, not a spinner that never ends.
     */
    @Test
    fun aFailedPairingOffersRetryOnThePhoneTab() {
        compose.waitForText("No terminal connected")
        compose.activity.graph.simulator.failNextConnect = PairingProblem.FAILED
        compose.activity.graph.repository.connect(LinkTarget(SimulatedTerminal.ADDRESS, "Demo terminal"))
        compose.waitForText("Pairing failed")
        compose.onNodeWithText("Retry").performClick()
        compose.waitForText("Activate your terminal")
    }

    @Test
    fun demoCodeActivatesAndRegisters() {
        compose.connectDemoAndOpenPhone()
        compose.onNodeWithText("Scan QR code").assertExists()
        compose.onNodeWithText("Use a demo code").performClick()
        compose.waitForText("Valid until")
        compose.onNodeWithText("+883 606 555 1234").assertExists()
        compose.onNodeWithText("Activate").performClick()
        compose.waitForText("Your number")
        compose.onNodeWithText("+883 606 555 1234").assertExists()
        compose.onNodeWithText("Registered").assertExists()
        compose.onNodeWithText("Part 15 · Signalling and voice encrypted", substring = true).assertExists()
    }

    @Test
    fun badCodeIsExplainedBeforeAnythingIsSent() {
        compose.connectDemoAndOpenPhone()
        compose.onNodeWithText("Activation code (opencell:1:…)").performTextReplacement("opencell:1:nope")
        compose.onNodeWithText("Check code").performClick()
        compose.waitForText("Incomplete code: expected 96 characters after opencell:1:, found 4")
    }

    @Test
    fun sameCodeTwiceFailsWithTokenUsed() {
        compose.connectDemoAndOpenPhone()
        val code = compose.activity.graph.simulator.demoQrText()
        activateWith(code)
        compose.waitForText("Your number")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Activate with a new code").performClick()
        activateWith(code)
        compose.waitForText("Activation failed")
        compose.onNodeWithText("This code has already been used (token used)").assertExists()
        compose.onNodeWithText("Try another code").performClick()
        compose.waitForText("Your number")
    }

    @Test
    fun deactivateAsksFirstThenWipes() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Deactivate terminal").performClick()
        compose.onNodeWithText("Deactivate this terminal?").assertExists()
        compose.onNodeWithText("Deactivate").performClick()
        compose.waitForText("Activate your terminal")
    }

    /** Triage: the Phone menu and the deactivate confirmation survive recreation (fold/unfold). */
    @Test
    fun menuAndDeactivateConfirmationSurviveRecreation() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithContentDescription("More").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForText("Deactivate terminal")
        compose.onNodeWithText("Deactivate terminal").performClick()
        compose.onNodeWithText("Deactivate this terminal?").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitForText("Deactivate this terminal?")
    }

    /** M8: a registration failure right after activation shows on the "Activated" screen, with Continue. */
    @Test
    fun registrationFailureAfterActivationIsShownWithContinue() {
        compose.connectDemoAndOpenPhone()
        compose.activity.graph.simulator.failNextRegistration = RegFailReason.TIMEOUT
        compose.onNodeWithText("Use a demo code").performClick()
        compose.waitForText("Valid until")
        compose.onNodeWithText("Activate").performClick()
        compose.waitForText("Registration failed: No answer from the network")
        compose.onNodeWithText("Activated").assertExists()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForText("Your number")
    }

    /** Triage: POST_NOTIFICATIONS granted but notifications off: "Allow" goes straight to the app's notification settings. */
    @Test
    fun notificationsAllowOpensSettingsWhenThePermissionIsAlreadyGranted() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(compose.activity.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // re-checks the environment
        compose.waitForText("Notifications are off")
        compose.onNodeWithText("Allow").performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started?.action)
    }

    /** Triage: camera permanently denied: explain, and link to the app's settings instead of asking again. */
    @Test
    fun cameraPermanentlyDeniedExplainsAndLinksToSettings() {
        compose.connectDemoAndOpenPhone()
        compose.onNodeWithText("Scan QR code").performClick()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertEquals(Manifest.permission.CAMERA, request.requestedPermissions.single())
        compose.runOnUiThread {
            @Suppress("DEPRECATION")
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        compose.waitForText("Camera access is off")
        compose.onNodeWithText("Open Settings").performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started?.action)
    }
}
