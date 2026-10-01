package org.opencell.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.ui.MainActivity
import org.opencell.app.ui.NUMBER_TAG
import org.opencell.core.phone.DialPad
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The whole Phone tab (top bar, tabs or Recents pane, line card, readiness card,
 * number, keypad, Call and Delete) at the Galaxy Z Fold 7's sizes, in normal and
 * large font: every key, the number, Delete and Call are on screen, at least
 * 48 dp each way, without scrolling (dial-and-recents spec §3.1, §7; Review Focus 2).
 *
 * The readiness card shows under Robolectric (notifications or full-screen calls
 * aren't granted), as it does on a newly installed phone: the worst case.
 * Robolectric draws no status or navigation bars, so [coverSidewaysUnderSystemBars]
 * also takes them off the sideways cover screen, the tightest of the three.
 */
@RunWith(AndroidJUnit4::class)
class PhoneLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        compose.activity.graph.repository.disconnect()
    }

    /** Activates the demo terminal in the starting window, then (if [qualifiers]) turns or unfolds to that one. */
    private fun registeredHome(qualifiers: String? = null) {
        compose.connectDemoAndOpenPhone()
        compose.activateDemo()
        if (qualifiers != null) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            compose.activityRule.scenario.recreate()
            compose.waitForText("Your number")
        }
        compose.typeOnKeypad("6065550") // so Delete shows
        compose.waitForIdle()
    }

    private fun assertUsable(node: SemanticsNodeInteraction, what: String, others: MutableList<Pair<String, DpRect>>) {
        node.assertIsDisplayed()
        val b = node.getBoundsInRoot()
        val u = node.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        val w = b.right - b.left
        val h = b.bottom - b.top
        assertTrue("$what is ${w}x$h ($b)", w >= 48.dp && h >= 48.dp)
        assertTrue("$what is clipped: $u shows as $b", u == b)
        assertTrue("$what is off screen: $b in $root", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
        node.assert(!hasAnyAncestor(hasScrollAction()))
        for ((name, r) in others) {
            val overlaps = b.left < r.right && r.left < b.right && b.top < r.bottom && r.top < b.bottom
            assertFalse("$what $b overlaps $name $r", overlaps)
        }
        others += what to b
    }

    private fun assertDialScreenUsable() {
        val seen = mutableListOf<Pair<String, DpRect>>()
        assertUsable(compose.onNodeWithTag(NUMBER_TAG), "the number", seen)
        DialPad.KEYS.forEach { assertUsable(compose.onNodeWithContentDescription(DialPad.spoken(it)), "key $it", seen) }
        assertUsable(compose.onNodeWithContentDescription("Call"), "Call", seen)
        assertUsable(compose.onNodeWithContentDescription("Delete"), "Delete", seen)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun coverUpright() {
        registeredHome()
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun coverSideways() {
        registeredHome("w891dp-h411dp")
        assertDialScreenUsable()
    }

    /** The sideways cover screen less a status bar and a navigation bar (about 48 dp together). */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun coverSidewaysUnderSystemBars() {
        registeredHome("w891dp-h363dp")
        assertDialScreenUsable()
    }

    /** The inner screen held upright (1968 x 2184 px, about 750 x 832 dp). */
    @Test
    @Config(qualifiers = "w750dp-h832dp")
    fun innerUpright() {
        registeredHome()
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w750dp-h832dp")
    fun innerSideways() {
        registeredHome("w832dp-h750dp")
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp", fontScale = 2.0f)
    fun coverUprightLargeFont() {
        registeredHome()
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp", fontScale = 2.0f)
    fun coverSidewaysLargeFont() {
        registeredHome("w891dp-h411dp")
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w750dp-h832dp", fontScale = 2.0f)
    fun innerUprightLargeFont() {
        registeredHome()
        assertDialScreenUsable()
    }

    @Test
    @Config(qualifiers = "w750dp-h832dp", fontScale = 2.0f)
    fun innerSidewaysLargeFont() {
        registeredHome("w832dp-h750dp")
        assertDialScreenUsable()
    }

    /** The one-line card opened to the whole card: it scrolls in the room above the keypad, which stays whole. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun theFullLineCardDoesNotPushTheKeypadOff() {
        registeredHome()
        compose.onNodeWithText("Your number").performClick()
        compose.waitForText("Mode")
        assertDialScreenUsable()
    }
}
