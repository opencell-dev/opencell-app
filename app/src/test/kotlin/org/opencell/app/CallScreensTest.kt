package org.opencell.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.app.ui.NUMBER_TAG
import org.opencell.core.link.PairingProblem
import org.opencell.core.phone.PhoneSession
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
    fun outgoingCallRingsConnectsHasVoiceControlsAndHangsUp() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.dialOnKeypad("6065550100")
        compose.waitForText("Outgoing call")
        compose.onNodeWithText("+883-1-606-555-00100").assertExists()
        compose.waitForText("Ringing…")
        compose.waitForText("Connected")
        compose.waitForText("Codec2 1200")
        compose.waitForText("Microphone not allowed") // nothing is granted under Robolectric
        compose.onNodeWithText("Mute").performClick()
        compose.waitForText("Unmute")
        compose.onNodeWithText("Unmute").performClick()
        compose.onNodeWithText("Mute").assertExists()
        // The demo peer echoes the voice frames back.
        compose.waitUntil(10_000) { compose.onAllNodes(textMatches(Regex("received [1-9]"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hang up").performClick()
        compose.waitForText("Call ended")
        compose.onNodeWithText("Close").performClick()
        compose.waitForText("Your number")
    }

    /** Call stays disabled for what can't be a number, and the line under it says why (spec §3.2). */
    @Test
    fun aNumberThatCantBeDialledLeavesCallDisabled() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.typeOnKeypad("1234567890123")
        compose.waitForText(PhoneSession.BAD_NUMBER)
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
    }

    @Test
    fun emergencyNumberIsExplainedAndNotDialled() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.typeOnKeypad("911")
        compose.waitForText(PhoneSession.EMERGENCY) // the hint under the number, as typed
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
        compose.onNodeWithText("Your number").assertExists() // no call screen
    }

    /** The line under the number follows the keys: the full form once it's a number, nothing while it could still become one. */
    @Test
    fun theHintFollowsTheKeys() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.typeOnKeypad("6065551235")
        compose.waitForText("Dials +883-1-606-555-01235")
        compose.onNodeWithContentDescription("Call").assertIsEnabled()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onAllNodesWithText("Dials", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(PhoneSession.BAD_NUMBER).assertCountEquals(0)
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
    }

    /** The keypad clears once the call screen opens: the number is in Recents now (spec §6). */
    @Test
    fun theKeypadClearsWhenTheCallStarts() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.dialOnKeypad("6065550100")
        compose.waitForText("Outgoing call")
        compose.onNodeWithText("Hang up").performClick()
        compose.waitForText("Call ended")
        compose.onNodeWithText("Close").performClick()
        compose.waitForText("Enter a number")
    }

    /** Each key sounds its DTMF tone; ⋮ > Keypad tones turns them off (spec §3.4). */
    @Test
    fun keyTonesPlayUntilTurnedOff() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        val sound = compose.activity.graph.keySound as RecordingKeySound
        compose.typeOnKeypad("5#")
        assertEquals(listOf('5', '#'), sound.played)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Keypad tones").performClick()
        compose.typeOnKeypad("1")
        assertEquals(listOf('5', '#'), sound.played)
        assertFalse(compose.activity.graph.keypadTones.enabled.value)
        compose.activity.graph.keypadTones.set(true)
    }

    @Test
    fun incomingCallIsAnsweredAndEndedByTheFarEnd() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall("+883160655500100"))
        compose.waitForText("Incoming call")
        compose.onNodeWithText("+883-1-606-555-00100").assertExists()
        compose.onNodeWithText("Answer").performClick()
        compose.waitForText("Since ")
        assertTrue(sim.peerHangup())
        compose.waitForText("Call ended")
    }

    @Test
    fun incomingCallIsRejected() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        sim.incomingCall("+883160655500100")
        compose.waitForText("Incoming call")
        compose.onNodeWithText("Reject").performClick()
        compose.waitForText("Call rejected")
    }

    /**
     * C1: while the link is down the call's buttons can't reach the terminal, so the call screen
     * offers Close; closing drops the local call, and the next resync brings it back if it's still up.
     */
    @Test
    fun callScreenOffersCloseWhileTheLinkIsDown() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall("+883160655500100"))
        compose.waitForText("Incoming call")
        sim.dropLink()
        compose.waitForText("The phone lost the terminal")
        compose.onNodeWithText("Close").performClick()
        compose.onAllNodesWithText("Incoming call").assertCountEquals(0)
        // The call is still ringing in the terminal: the reconnect's resync shows it again.
        compose.waitForText("Incoming call")
        compose.onNodeWithText("Unknown caller").assertExists()
    }

    /** A link that ended in a pairing failure isn't "reconnecting": the call screen says pairing is needed and offers Retry. */
    @Test
    fun callScreenSaysTheTerminalNeedsPairingAgainAfterAPairingFailure() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall("+883160655500100"))
        compose.waitForText("Incoming call")
        sim.failNextConnect = PairingProblem.FAILED
        sim.dropLink()
        compose.waitForText("needs pairing again")
        compose.onAllNodesWithText("reconnecting").assertCountEquals(0)
        compose.onNodeWithText("Close").assertExists()
        compose.onNodeWithText("Retry").performClick()
        // Paired again: the resync finds the call still ringing and its buttons come back.
        compose.waitForText("Answer")
    }

    /** C1: Disconnect during a call (e.g. from the link notification) ends it on the phone; Close leaves the call screen. */
    @Test
    fun disconnectingDuringACallEndsItAndCloseLeavesTheCallScreen() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall("+883160655500100"))
        compose.waitForText("Incoming call")
        compose.activity.graph.repository.disconnect()
        compose.waitForText("Call ended (the phone was disconnected from the terminal)")
        compose.onNodeWithText("Close").performClick()
        compose.waitForText("No terminal connected")
    }

    private fun textMatches(re: Regex) = SemanticsMatcher("text matches $re") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { re.containsMatchIn(it.text) } == true
    }

    /** Folding or unfolding keeps what was typed (the number lives in the view model). */
    @Test
    fun theTypedNumberSurvivesFolding() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.typeOnKeypad("606555")
        compose.activityRule.scenario.recreate()
        compose.waitForText("Your number")
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("606-555")
    }
}
