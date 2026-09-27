package org.opencell.app

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import org.opencell.app.ui.MainActivity

/** Shared steps of the Robolectric UI tests, driving the app against the demo terminal. */
typealias AppRule = AndroidComposeTestRule<*, MainActivity>

/** Waits (real time: the demo terminal runs on its own clock) until some node's text contains [text]. */
fun AppRule.waitForText(text: String, timeoutMillis: Long = 10_000) {
    try {
        waitUntil(timeoutMillis) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        println(onRoot(useUnmergedTree = false).printToString())
        throw e
    }
}

/** From the start screen: connect the demo terminal on the Terminal tab and come back to Phone. */
fun AppRule.connectDemoAndOpenPhone() {
    waitForText("No terminal connected")
    onNodeWithText("Terminal").performClick()
    onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Demo terminal"))
    onNodeWithText("Demo terminal").performClick()
    waitForText("Granted")
    onNodeWithText("Phone").performClick()
    waitForText("Activate your terminal")
}

/** Activates the demo terminal with a demo code and waits for the registered home screen. */
fun AppRule.activateDemo() {
    onNodeWithText("Use a demo code").performClick()
    waitForText("Valid until")
    onNodeWithText("Activate").performClick()
    waitForText("Your number")
}
