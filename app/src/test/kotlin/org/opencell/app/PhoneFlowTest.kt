package org.opencell.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
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

    @Test
    fun demoCodeActivatesAndRegisters() {
        compose.connectDemoAndOpenPhone()
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
}
