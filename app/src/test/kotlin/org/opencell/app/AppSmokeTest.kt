package org.opencell.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.core.dev.DeveloperAccess
import org.robolectric.annotation.Config

/**
 * UI smoke tests on Robolectric (no phone): the app starts on the cover-screen
 * and inner-screen sizes, and the demo terminal attaches and echoes through the
 * real session, link manager and UI. BLE itself is not exercised here.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMillis) {
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            println(compose.onRoot(useUnmergedTree = false).printToString())
            throw e
        }
    }

    private fun openTerminalTab() {
        compose.onNodeWithText("Terminal").performClick()
    }

    private fun openDemoTerminal() {
        // The demo terminal (and Console/Loopback below) are developer features: unlock
        // directly rather than driving the code dialog in every test that needs them.
        compose.activity.graph.developerAccess.tryUnlock(DeveloperAccess.CODE)
        openTerminalTab()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Demo terminal"))
        compose.onNodeWithText("Demo terminal").performClick()
    }

    /** Galaxy Z Fold 7 cover screen class: compact width, one pane at a time. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun coverScreenDemoTerminalReachesGranted() {
        compose.onNodeWithText("OpenCell").assertExists()
        openDemoTerminal()
        // Compact: the status pane replaces the list.
        waitForText("Granted")
        compose.onNodeWithText("76AD0488").assertExists()
        compose.onNodeWithText(" dBm", substring = true).assertExists()
    }

    /** Inner screen class: list and status side by side. */
    @Test
    @Config(qualifiers = "w884dp-h824dp")
    fun innerScreenShowsListAndStatusTogether() {
        compose.onNodeWithText("OpenCell").assertExists()
        openTerminalTab()
        compose.onNodeWithText("Status").assertExists()
        openDemoTerminal()
        waitForText("Granted")
        compose.onNodeWithText("Terminals").assertExists()
    }

    /**
     * The demo terminal is Part 15 (the default). The media gate refuses UP
     * with 0x80 anywhere outside a connected call in Part 15, so the console's
     * quick HELLO write is refused rather than echoed back on DOWN.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun consoleUpIsRefusedOutsideACallOnTheDemoTerminal() {
        openDemoTerminal()
        waitForText("Granted")
        compose.onNodeWithText("Console").performClick()
        compose.onNodeWithText("HELLO").performClick()
        waitForText("not now (0x80)")
        // Logged once: the UP write. No DOWN echo follows.
        compose.onAllNodesWithText("48 45 4c 4c 4f").assertCountEquals(1)
    }

    /**
     * Same media gate as above: outside a call, the demo terminal refuses
     * every loopback probe. Decision #25 (2026-10-01): this is now true in
     * every mode, not just Part 15 - the Part 97 out-of-call diagnostic
     * loopback is gone (docs/ios-app-handoff.md §3.5).
     */
    @Test
    @Config(qualifiers = "w884dp-h824dp")
    fun loopbackIsRefusedOutsideACallOnTheDemoTerminal() {
        openDemoTerminal()
        waitForText("Granted")
        compose.onNodeWithText("Loopback").performClick()
        compose.onNodeWithText("20").performTextReplacement("1")
        compose.onNodeWithText("Start").performClick()
        waitForText("Finished: 1 probes")
        compose.onNodeWithText("Refused").assertExists()
    }
}
