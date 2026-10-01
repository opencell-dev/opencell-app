package org.opencell.app

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.app.ui.NUMBER_TAG
import org.opencell.app.ui.missedCallsLabel
import org.opencell.core.calllog.CallKind
import org.opencell.core.phone.Direction
import org.opencell.core.phone.FinishedCall
import org.opencell.core.protocol.EndCause
import org.opencell.core.sim.SimulatedTerminal
import org.opencell.core.voice.VoiceCounters
import org.robolectric.annotation.Config

/** Recents and the missed-call badge against the demo terminal (dial-and-recents spec §5, §6). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RecentsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = compose.activity.graph
    private val sim get() = graph.simulator
    private val echo = "+883160655500100"

    @Before
    fun emptyLog() {
        graph.callLog.clear()
    }

    @After
    fun tearDown() {
        graph.repository.disconnect()
        graph.developerAccess.lock()
    }

    private fun seed(direction: Direction?, number: String?, connected: Boolean, rejected: Boolean = false, voice: VoiceCounters? = null) {
        val now = System.currentTimeMillis()
        graph.callLog.record(
            FinishedCall(direction, number, SimulatedTerminal.DEMO_NUMBER, now - 60_000, if (connected) now - 50_000 else null, now, EndCause.NORMAL.code, 1, rejected),
            voice,
        )
    }

    /** The Phone item or the Recents tab carrying [n] unseen missed calls (TalkBack reads it as the state). */
    private fun missedBadge(n: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, missedCallsLabel(n))

    private fun openRecents() {
        compose.onNodeWithText("Recents").performClick()
    }

    @Test
    fun aCallMadeOnTheKeypadShowsInRecents() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.dialOnKeypad("6065550100")
        compose.waitForText("Connected")
        compose.onNodeWithText("Hang up").performClick()
        compose.waitForText("Call ended")
        compose.onNodeWithText("Close").performClick()
        openRecents()
        compose.waitForText("Echo test (core 1)")
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Outgoing · 0:0", substring = true).assertExists()
        assertEquals(CallKind.OUTGOING, graph.callLog.entries.value.single().kind)
    }

    /** A missed call: the call screen closes onto Recents, which marks it seen (no badge left). */
    @Test
    fun aMissedCallLandsOnRecentsAndIsMarkedSeen() {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        assertTrue(sim.incomingCall(echo))
        compose.waitForText("Incoming call")
        assertTrue(sim.peerHangup())
        compose.waitForText("Call ended")
        compose.onNodeWithText("Close").performClick()
        compose.waitForText("Missed")
        compose.waitUntil(5_000) { graph.callLog.unseenMissed.value == 0 }
        compose.onAllNodes(missedBadge(1)).assertCountEquals(0)
    }

    @Test
    fun theBadgeCountsUnseenMissedCallsUntilRecentsIsOpened() {
        compose.waitForText("No terminal connected")
        compose.onNodeWithText("Terminal").performClick()
        seed(Direction.INCOMING, echo, connected = false)
        compose.waitUntil(5_000) { compose.onAllNodes(missedBadge(1)).fetchSemanticsNodes().isNotEmpty() }
        seed(Direction.INCOMING, null, connected = false)
        compose.waitUntil(5_000) { compose.onAllNodes(missedBadge(2)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Phone").performClick()
        compose.onAllNodes(missedBadge(2)).assertCountEquals(2) // the Phone item and the Recents tab
        openRecents()
        compose.waitForText("Unknown caller")
        compose.waitUntil(5_000) { graph.callLog.unseenMissed.value == 0 }
        compose.onAllNodes(missedBadge(2)).assertCountEquals(0)
    }

    @Test
    fun theCallButtonCallsBack() {
        seed(Direction.INCOMING, echo, connected = true)
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        openRecents()
        compose.onNodeWithContentDescription("Call Echo test (core 1)").performClick()
        compose.waitForText("Outgoing call")
        compose.onNodeWithText("+883-1-606-555-00100").assertExists()
    }

    @Test
    fun tappingARowPutsItsNumberOnTheKeypad() {
        seed(Direction.OUTGOING, "+883160655501235", connected = false)
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        openRecents()
        compose.onNodeWithText("+883-1-606-555-01235").performClick()
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("+883-1-606-555-01235")
        compose.onNodeWithText("Dials +883-1-606-555-01235").assertExists()
    }

    @Test
    fun aLongPressCopiesOrDeletes() {
        seed(Direction.OUTGOING, "+883160655501235", connected = false)
        seed(Direction.INCOMING, null, connected = false, rejected = true)
        compose.waitForText("No terminal connected")
        openRecents()
        compose.onNodeWithText("+883-1-606-555-01235").performTouchInput { longClick() }
        compose.onNodeWithText("Copy number").performClick()
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals("+883-1-606-555-01235", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        compose.onNodeWithText("Rejected").performTouchInput { longClick() }
        compose.onAllNodesWithText("Copy number").assertCountEquals(0) // no number to copy
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Rejected").assertCountEquals(0)
        assertEquals(1, graph.callLog.entries.value.size)
    }

    @Test
    fun clearingAsksFirst() {
        seed(Direction.OUTGOING, echo, connected = true)
        compose.waitForText("No terminal connected")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear call log").performClick()
        compose.onNodeWithText("Clear the call log?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, graph.callLog.entries.value.size)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear call log").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertTrue(graph.callLog.entries.value.isEmpty())
        compose.waitForText("No terminal connected") // no log, no tabs
        compose.onAllNodesWithText("Recents").assertCountEquals(0)
    }

    @Test
    fun voiceCountersShowOnlyWithDeveloperOptions() {
        seed(Direction.OUTGOING, echo, connected = true, voice = VoiceCounters(120, 2, 118, 3))
        compose.waitForText("No terminal connected")
        openRecents()
        compose.waitForText("Echo test (core 1)")
        compose.onAllNodesWithText("sent 120", substring = true).assertCountEquals(0)
        graph.developerAccess.tryUnlock(org.opencell.core.dev.DeveloperAccess.CODE)
        compose.waitForText("Codec2 1200 · sent 120 · not sent 2 · received 118 · concealed 3")
    }

    /** The Fold's inner screen: Recents beside the keypad, no tabs. */
    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun aWideScreenShowsRecentsBesideTheKeypad() {
        seed(Direction.OUTGOING, echo, connected = true)
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        compose.onNodeWithText("Echo test (core 1)").assertExists()
        compose.onNodeWithContentDescription("5").assertExists()
        compose.onAllNodesWithText("Keypad").assertCountEquals(0)
        compose.onNodeWithText("Echo test (core 1)").performClick()
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("+883-1-606-555-00100")
    }

    /** The missed-call notification's tap lands on Recents, whatever tab was open. */
    @Test
    fun theMissedCallNotificationOpensRecents() {
        seed(Direction.INCOMING, echo, connected = false)
        compose.waitForText("No terminal connected")
        compose.onNodeWithText("Terminal").performClick()
        compose.runOnUiThread {
            compose.activity.handle(Intent(compose.activity, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECENTS))
        }
        compose.waitForText("Echo test (core 1)")
        compose.waitUntil(5_000) { graph.callLog.unseenMissed.value == 0 }
    }
}
