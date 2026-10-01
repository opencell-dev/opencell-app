package org.opencell.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.core.dev.DeveloperAccess
import org.robolectric.annotation.Config

/**
 * ⋮ > Developer options (Terminal tab): the static-code gate (decided 2026-09-30, matching
 * the iOS app) in front of Console, Loopback and the demo terminal.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DeveloperOptionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun lockToStart() {
        compose.activity.graph.developerAccess.lock()
    }

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
        compose.activity.graph.developerAccess.lock()
    }

    private fun openTerminalTab() {
        compose.onNodeWithText("Terminal").performClick()
    }

    private fun openDeveloperOptionsMenu() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Developer options").performClick()
    }

    private fun openDialog() {
        openTerminalTab()
        openDeveloperOptionsMenu()
    }

    private fun enterCodeAndUnlock(code: String) {
        compose.onNodeWithText("Code").performTextReplacement(code)
        compose.onNodeWithText("Unlock").performClick()
    }

    @Test
    fun lockedHidesConsoleLoopbackAndTheDemoTerminal() {
        openTerminalTab()
        compose.onNodeWithText("Console").assertDoesNotExist()
        compose.onNodeWithText("Loopback").assertDoesNotExist()
        compose.onNodeWithText("Demo terminal").assertDoesNotExist()
    }

    @Test
    fun aWrongCodeShowsANeutralMessageAndAllowsAnImmediateRetry() {
        openDialog()
        enterCodeAndUnlock("00000000")
        compose.onNodeWithText("That's not it.").assertExists()
        // No lockout: the same dialog takes the correct code right away.
        enterCodeAndUnlock(DeveloperAccess.CODE)
        compose.onNodeWithText("Console").assertExists()
    }

    @Test
    fun theCorrectCodeUnlocksConsoleLoopbackAndTheDemoTerminal() {
        openDialog()
        enterCodeAndUnlock(DeveloperAccess.CODE)
        compose.onNodeWithText("Console").assertExists()
        compose.onNodeWithText("Loopback").assertExists()
        compose.onNodeWithText("Demo terminal").assertExists()
    }

    @Test
    fun unlockingIsRememberedAcrossActivityRecreation() {
        openDialog()
        enterCodeAndUnlock(DeveloperAccess.CODE)
        compose.onNodeWithText("Console").assertExists()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Terminal").performClick()
        compose.onNodeWithText("Console").assertExists()
    }

    @Test
    fun lockingHidesThemAgain() {
        openDialog()
        enterCodeAndUnlock(DeveloperAccess.CODE)
        compose.onNodeWithText("Console").assertExists()

        openDeveloperOptionsMenu()
        compose.onNodeWithText("Lock").performClick()
        compose.onNodeWithText("Console").assertDoesNotExist()
        compose.onNodeWithText("Demo terminal").assertDoesNotExist()
    }
}
