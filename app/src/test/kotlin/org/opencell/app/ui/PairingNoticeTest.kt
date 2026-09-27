package org.opencell.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class PairingNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val target = LinkTarget("44:B1:76:AD:04:8A", "OpenCell-76AD0488")
    private val retried = mutableListOf<LinkTarget>()
    private var settingsOpened = 0

    private fun show(state: LinkState) = compose.setContent {
        PairingNotice(state, onRetry = { retried += it }, onBluetoothSettings = { settingsOpened++ })
    }

    @Test
    fun pairingShowsTheCodeHint() {
        show(LinkState.Pairing(target))
        compose.onNodeWithText("Pairing with OpenCell-76AD0488").assertExists()
        compose.onNodeWithText(PairingRules.HINT).assertExists()
    }

    @Test
    fun aFailedPairingOffersRetry() {
        show(LinkState.PairingFailed(target, PairingProblem.FAILED, "pairing failed or was cancelled"))
        compose.onNodeWithText("Pairing failed").assertExists()
        compose.onNodeWithText("Bluetooth settings").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(target), retried)
    }

    @Test
    fun aStaleBondLinksToBluetoothSettings() {
        show(LinkState.PairingFailed(target, PairingProblem.STALE_BOND, "the terminal no longer knows this phone"))
        compose.onNodeWithText("The terminal forgot this phone").assertExists()
        compose.onNodeWithText("Bluetooth settings").performClick()
        assertEquals(1, settingsOpened)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(target), retried)
    }

    @Test
    fun otherStatesShowNothing() {
        show(LinkState.WaitingToReconnect(target, 1, 1.seconds, "supervision timeout"))
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText(PairingRules.HINT).assertDoesNotExist()
    }
}
