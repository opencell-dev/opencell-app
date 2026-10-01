package org.opencell.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.core.phone.DialPad
import org.opencell.core.phone.PhoneSession
import org.robolectric.annotation.Config

/** The keypad on its own (dial-and-recents spec §3): typing, deleting, +, paste, Call's enabling, test numbers, layout. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class KeypadTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = "+883160655501234"
    private var input by mutableStateOf("")
    private val calls = mutableListOf<String>()
    private val testNumbers = mutableListOf<String>()

    private fun show(canDial: Boolean = true, home: String? = me) {
        compose.setContent {
            MaterialTheme {
                Keypad(
                    input = input,
                    hint = DialPad.hint(input, home),
                    canDial = canDial,
                    notice = null,
                    actions = KeypadActions(
                        onKey = { input = DialPad.press(input, it) },
                        onPlus = { input = DialPad.press(input, '+') },
                        onBackspace = { input = DialPad.backspace(input) },
                        onClear = { input = "" },
                        onPaste = { input = DialPad.fromPaste(it) },
                        onCall = { calls += input },
                        onTestNumber = { testNumbers += it },
                    ),
                )
            }
        }
    }

    private fun type(s: String) = s.forEach { compose.onNodeWithContentDescription(DialPad.spoken(it)).performClick() }

    @Test
    fun theNumberGroupsAsItIsTyped() {
        show()
        compose.onNodeWithText("Enter a number").assertExists()
        type("6065550")
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("606-555-0")
        type("100")
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("606-555-0100")
        compose.onNodeWithText("Dials +883-1-606-555-00100").assertExists()
    }

    @Test
    fun deleteRemovesOneAndALongPressClears() {
        show()
        type("606")
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("60")
        compose.onNodeWithContentDescription("Delete").performTouchInput { longClick() }
        compose.onNodeWithText("Enter a number").assertExists()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist() // nothing to delete
    }

    @Test
    fun aLongPressOnZeroIsPlus() {
        show()
        compose.onNodeWithContentDescription("0").performTouchInput { longClick() }
        type("883160655500100")
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("+883-1-606-555-00100")
        val zero = compose.onNodeWithContentDescription("0").fetchSemanticsNode()
        assertEquals("Plus", zero.config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun callIsEnabledOnlyForANumberAndOnlyWhenRegistered() {
        show()
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
        type("606555")
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
        type("0100")
        compose.onNodeWithContentDescription("Call").assertIsEnabled().performClick()
        assertEquals(listOf("6065550100"), calls)
        type("99") // 12 national digits: not a number any more
        compose.onNodeWithText(PhoneSession.BAD_NUMBER).assertExists()
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
    }

    @Test
    fun withoutRegistrationCallStaysOffAndSaysWhy() {
        show(canDial = false)
        type("6065550100")
        compose.onNodeWithText("Calls need the terminal registered.").assertExists()
        compose.onNodeWithContentDescription("Call").assertIsNotEnabled()
        compose.onNodeWithText("Test numbers").assertIsNotEnabled()
    }

    @Test
    fun pasteReplacesTheNumberWithWhatTheKeypadCouldType() {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("n", "tel:(606) 555-0100"))
        show()
        type("1")
        compose.onNodeWithTag(NUMBER_TAG).performTouchInput { longClick() }
        compose.onNodeWithText("Paste").performClick()
        compose.onNodeWithTag(NUMBER_TAG).assertTextEquals("606-555-0100")
    }

    @Test
    fun copyPutsTheTypedNumberOnTheClipboard() {
        show()
        type("6065550100")
        compose.onNodeWithTag(NUMBER_TAG).performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("6065550100", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun aTestNumberIsCalledFromTheMenu() {
        show()
        compose.onNodeWithText("Test numbers").performClick()
        compose.onNodeWithText("+883-1-503-555-00101").assertExists()
        compose.onNodeWithText("Echo test (core 1)").performClick()
        assertEquals(listOf("+883160655500100"), testNumbers)
    }

    @Test
    fun keysMeetTheTouchMinimum() {
        show()
        DialPad.KEYS.forEach { compose.onNodeWithContentDescription(DialPad.spoken(it)).assertWidthIsAtLeast(48.dp) }
        compose.onNodeWithContentDescription("Call").assertWidthIsAtLeast(48.dp)
    }

    /** The Fold's cover screen turned sideways: the number beside the keys, every key and Call on screen. */
    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun aShortWideWindowPutsTheNumberBesideTheKeys() {
        show()
        type("606")
        DialPad.KEYS.forEach { compose.onNodeWithContentDescription(DialPad.spoken(it)).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Call").assertIsDisplayed()
        compose.onNodeWithTag(NUMBER_TAG).assertIsDisplayed()
    }

    /** The longest input still fits: the number shrinks (down to 20 sp) rather than running off the screen. */
    @Test
    fun aLongNumberShrinksToFit() {
        show()
        type("00883160655501234567")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(NUMBER_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().didOverflowWidth)
    }
}
