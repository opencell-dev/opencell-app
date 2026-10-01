package org.opencell.app

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.CHANNEL_PICKER
import org.opencell.app.ui.MainActivity
import org.opencell.core.dev.DeveloperAccess
import org.opencell.core.session.ChannelSession
import org.robolectric.annotation.Config

/** The Channels tab against the demo terminal (channel-list spec §9): live scan line, list, user channels. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ChannelsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
    }

    private fun openChannelsOnTheDemoTerminal() {
        compose.activity.graph.developerAccess.tryUnlock(DeveloperAccess.CODE)
        compose.waitForText("No terminal connected")
        compose.onNodeWithText("Terminal").performClick()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Demo terminal"))
        compose.onNodeWithText("Demo terminal").performClick()
        compose.waitForText("Granted")
        compose.onNodeWithText("Channels").performClick()
    }

    @Test
    fun addsAndRemovesAChannelOfYourOwn() {
        openChannelsOnTheDemoTerminal()
        compose.waitForText("Channel 903.25 MHz") // STATUS: the demo cell's anchor
        compose.waitForText("last cell") // SCAN: it attached there
        compose.waitForText("Your channels: 0 of 4")

        compose.onNodeWithText("Add channel").performClick()
        compose.onNodeWithTag(CHANNEL_PICKER).performScrollToNode(hasText("917.25 MHz"))
        compose.onNodeWithText("917.25 MHz").performClick()
        compose.waitForText("Your channels: 1 of 4")
        compose.waitForText("yours")
        val users = compose.activity.graph.session.channels.list.value!!.userEntries
        assertEquals(listOf(917_250_000L), users.map { it.freqHz })

        compose.onNodeWithContentDescription("Remove 917.25 MHz").performClick()
        compose.waitForText("Your channels: 0 of 4")
    }

    @Test
    fun changesTheSearchOutsideTheList() {
        openChannelsOnTheDemoTerminal()
        compose.waitForText("last cell")
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Apply"))
        compose.waitForText("After 2 passes")
        compose.onAllNodesWithText("+")[0].performClick()
        compose.waitForText("After 3 passes")
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(10_000) { compose.activity.graph.session.channels.list.value?.fallbackAfter == 3 }
        assertEquals(13, compose.activity.graph.session.channels.list.value!!.fallbackChunk)
    }

    /** Connected, v3 firmware (no SCAN): the update-it problem, not the "connect a terminal" line too. */
    @Test
    fun aV3TerminalShowsOnlyTheUpdateLine() {
        compose.activity.graph.simulator.scanSupported = false
        openChannelsOnTheDemoTerminal()
        compose.waitForText(ChannelSession.NO_SCAN)
        compose.onNodeWithText("No scan list yet: connect a terminal on the Terminal tab.").assertDoesNotExist()
    }

    /** Not connected: the "connect a terminal" line, not the firmware problem (there is none to show). */
    @Test
    fun disconnectedShowsOnlyTheConnectLine() {
        compose.waitForText("No terminal connected")
        compose.onNodeWithText("Channels").performClick()
        compose.waitForText("No scan list yet: connect a terminal on the Terminal tab.")
        compose.onNodeWithText(ChannelSession.NO_SCAN).assertDoesNotExist()
    }
}
