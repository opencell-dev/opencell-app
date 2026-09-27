package org.opencell.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.robolectric.annotation.Config

/** Dialer, outgoing, incoming and in-call screens against the demo terminal (spec §1 call flows). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class CallScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
    }

    private val sim get() = compose.activity.graph.simulator

    @Test
    fun outgoingCallRingsConnectsCarriesDataAndHangsUp() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithText("Number (+883…)").performTextReplacement("+883 606 555 0100")
        compose.onNodeWithText("Call").performClick()
        compose.waitForText("Outgoing call")
        compose.onNodeWithText("+883 606 555 0100").assertExists()
        compose.waitForText("Ringing…")
        compose.waitForText("Connected")
        compose.onNodeWithText("Send 5 test frames").performClick()
        compose.waitForText("Sent 5 · received 5 (test frames 5)")
        compose.onNodeWithText("Hang up").performClick()
        compose.waitForText("Call ended")
        compose.onNodeWithText("Close").performClick()
        compose.waitForText("Your number")
    }

    @Test
    fun badNumberIsRefusedInTheDialer() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithText("Number (+883…)").performTextReplacement("12345")
        compose.onNodeWithText("Call").performClick()
        compose.waitForText("OpenCell numbers are +883 and 10 digits")
    }

    @Test
    fun incomingCallIsAnsweredAndEndedByTheFarEnd() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall("+8836065550100"))
        compose.waitForText("Incoming call")
        compose.onNodeWithText("+883 606 555 0100").assertExists()
        compose.onNodeWithText("Answer").performClick()
        compose.waitForText("Since ")
        assertTrue(sim.peerHangup())
        compose.waitForText("Call ended")
    }

    @Test
    fun incomingCallIsRejected() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        sim.incomingCall("+8836065550100")
        compose.waitForText("Incoming call")
        compose.onNodeWithText("Reject").performClick()
        compose.waitForText("Call rejected")
    }
}
