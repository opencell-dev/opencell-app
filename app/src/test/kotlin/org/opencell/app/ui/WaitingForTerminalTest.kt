package org.opencell.app.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.core.link.LinkState
import org.opencell.core.link.LinkTarget
import org.opencell.core.link.PairingProblem
import org.opencell.core.link.PairingRules

/** The Phone tab while the terminal isn't up: pairing needs the user, so it shows what to do, not a spinner. */
@RunWith(AndroidJUnit4::class)
class WaitingForTerminalTest {
    @get:Rule
    val compose = createComposeRule()

    private val target = LinkTarget("44:B1:76:AD:04:8A", "OpenCell-76AD0488")
    private val retried = mutableListOf<LinkTarget>()

    private fun show(state: LinkState) = compose.setContent {
        WaitingForTerminal(state, onRetry = { retried += it }, onBluetoothSettings = {})
    }

    private fun spinners() = compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))

    @Test
    fun pairingShowsTheCodeHintNotASpinner() {
        show(LinkState.Pairing(target))
        compose.onNodeWithText(PairingRules.HINT).assertExists()
        spinners().assertCountEquals(0)
    }

    @Test
    fun aFailedPairingOffersRetryNotASpinner() {
        show(LinkState.PairingFailed(target, PairingProblem.FAILED, "pairing was cancelled"))
        spinners().assertCountEquals(0)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(target), retried)
    }

    @Test
    fun connectingStillWaits() {
        show(LinkState.Connecting(target, 1))
        compose.onNodeWithText("Connecting…").assertExists()
        spinners().assertCountEquals(1)
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }
}
