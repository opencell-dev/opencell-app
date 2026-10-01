package org.opencell.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.protocol.PhoneNumber

/** The keypad's input, as-you-type grouping and hint (dial-and-recents spec §3). */
class DialPadTest {
    private val me = "+883160655501234"

    @Test
    fun keysAndTheirLetters() {
        assertEquals("123456789*0#", DialPad.KEYS.joinToString(""))
        assertEquals(listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""), DialPad.KEYS.map(DialPad::letters))
        assertEquals(listOf("Star", "0", "Pound"), listOf('*', '0', '#').map(DialPad::spoken))
    }

    @Test
    fun pressAndBackspaceStopAtTheEnds() {
        var s = ""
        repeat(25) { s = DialPad.press(s, '5') }
        assertEquals(DialPad.MAX_LENGTH, s.length)
        assertEquals("", DialPad.backspace(""))
        assertEquals("60", DialPad.backspace("606"))
        assertEquals("+", DialPad.press("", '+'))
    }

    @Test
    fun pasteKeepsOnlyWhatTheKeypadCouldType() {
        assertEquals("+883160655500100", DialPad.fromPaste(" +883-1-606-555-00100 "))
        assertEquals("6065550100", DialPad.fromPaste("(606) 555.0100"))
        assertEquals("+883160655500100", DialPad.fromPaste("tel:+883160655500100"))
        assertEquals("6065550100", DialPad.fromPaste("call 606 555 0100 please"))
        assertEquals("6065550100", DialPad.fromPaste("٦٠٦٥٥٥٠١٠٠")) // Arabic-Indic digits
        assertEquals("883", DialPad.fromPaste("8+8+3")) // '+' only in front
        assertEquals("*#06#", DialPad.fromPaste("*#06#"))
        assertEquals(DialPad.MAX_LENGTH, DialPad.fromPaste("1".repeat(40)).length)
        assertEquals("", DialPad.fromPaste("no digits"))
    }

    /** Paste stops at an extension or a dialling pause, so the extra digits can't make a different, valid number. */
    @Test
    fun pasteStopsAtAnExtensionOrAPause() {
        assertEquals("6065551235", DialPad.fromPaste("606-555-1235 ext 4"))
        assertEquals("6065551235", DialPad.fromPaste("606-555-1235 ext. 4"))
        assertEquals("6065551235", DialPad.fromPaste("606-555-1235 extension 12"))
        assertEquals("6065551235", DialPad.fromPaste("606-555-1235 x4"))
        assertEquals("6065551235", DialPad.fromPaste("6065551235X4"))
        assertEquals("+883160655501234", DialPad.fromPaste("+883160655501234,,123"))
        assertEquals("+883160655501234", DialPad.fromPaste("+883160655501234;123"))
        assertEquals("+883160655501234", DialPad.fromPaste("+883160655501234p123"))
        assertEquals("+883160655501234", DialPad.fromPaste("+883160655501234w123"))
        assertEquals("+883160655501234", DialPad.fromPaste("tel:+883160655501234;ext=12"))
        assertEquals("+883160655501234", DialPad.fromPaste("tel:%2B883160655501234"))
        // Words that only contain those letters don't cut anything.
        assertEquals("6065550100", DialPad.fromPaste("Phone: 606 555 0100"))
        assertEquals("6065550100", DialPad.fromPaste("call now 606 555 0100"))
        // The wrong number the extension used to make is no longer offered.
        assertEquals("Dials +883-1-606-555-01235", DialPad.hint(DialPad.fromPaste("606-555-1235 ext 4"), me).text)
    }

    @Test
    fun nationalNumbersGroupAsTheyAreTyped() {
        val typed = "60655501234"
        val shown = (1..typed.length).map { DialPad.format(typed.take(it)) }
        assertEquals(
            listOf("6", "60", "606", "606-5", "606-55", "606-555", "606-555-0", "606-555-01", "606-555-012", "606-555-0123", "606-555-01234"),
            shown,
        )
        assertEquals("606-555-1235", DialPad.format("6065551235"))
        assertEquals("1 606-555-1235", DialPad.format("16065551235"))
        assertEquals("1", DialPad.format("1"))
        assertEquals("911", DialPad.format("911"))
        assertEquals("606555012345678", DialPad.format("606555012345678")) // too long for NANP: as typed
    }

    @Test
    fun internationalNumbersGroupLikeTheDisplayForm() {
        val typed = "+883160655501234"
        val shown = (1..typed.length).map { DialPad.format(typed.take(it)) }
        assertEquals(
            listOf(
                "+", "+8", "+88", "+883", "+883-1", "+883-1-6", "+883-1-60", "+883-1-606", "+883-1-606-5", "+883-1-606-55",
                "+883-1-606-555", "+883-1-606-555-0", "+883-1-606-555-01", "+883-1-606-555-012", "+883-1-606-555-0123",
                "+883-1-606-555-01234",
            ),
            shown,
        )
        assertEquals("+883-4", DialPad.format("+8834")) // two or three digits: can't tell yet
        assertEquals("+883-44", DialPad.format("+88344"))
        assertEquals("+883-44-2", DialPad.format("+883442"))
        assertEquals("+883-38", DialPad.format("+88338"))
        assertEquals("+883-380-1", DialPad.format("+8833801"))
        assertEquals("00 883-1-606-555-01235", DialPad.format("00883160655501235"))
        assertEquals("00", DialPad.format("00"))
        assertEquals("883-1-606-555-01235", DialPad.format("883160655501235"))
        assertEquals("+49176", DialPad.format("+49176")) // not OpenCell: as typed
        assertEquals("*#06#", DialPad.format("*#06#"))
        assertEquals("60+6", DialPad.format("60+6"))
    }

    /** Typed in full, every valid number reads exactly as PhoneNumber.display shows it. */
    @Test
    fun aWholeNumberFormatsLikeItsDisplay() {
        for (n in listOf("+883160655501234", "+883442079460000", "+88371234567", "+8833801234567", "+88344123")) {
            assertEquals(PhoneNumber.display(n), DialPad.format(n))
        }
    }

    @Test
    fun stillTypingUntilNoMoreDigitsCouldHelp() {
        assertTrue(DialPad.stillTyping(""))
        assertTrue(DialPad.stillTyping("606555123")) // 9 national digits
        assertFalse(DialPad.stillTyping("2115551234")) // 10 national digits (NPA 211 is invalid): no longer
        assertTrue(DialPad.stillTyping("1606555123")) // trunk 1 + 9
        assertFalse(DialPad.stillTyping("16065551234"))
        assertTrue(DialPad.stillTyping("+"))
        assertTrue(DialPad.stillTyping("+88316065550"))
        assertFalse(DialPad.stillTyping("+883160655501234"))
        assertTrue(DialPad.stillTyping("00883160655501"))
        assertFalse(DialPad.stillTyping("*#06"))
        assertFalse(DialPad.stillTyping("60+6"))
    }

    @Test
    fun theHintSaysWhatCallWillDialAndOnlyThenEnablesIt() {
        assertEquals(DialHint("", error = false, callable = false), DialPad.hint("", me))
        assertEquals(DialHint("", error = false, callable = false), DialPad.hint("606555", me))
        assertEquals(DialHint("Dials +883-1-606-555-01235", error = false, callable = true), DialPad.hint("6065551235", me))
        assertEquals(
            DialHint("Dials 6065551235: the terminal adds the country code", error = false, callable = true),
            DialPad.hint("6065551235", null),
        )
        assertEquals(DialHint(PhoneSession.EMERGENCY, error = true, callable = false), DialPad.hint("911", me))
        assertEquals(DialHint(PhoneSession.BAD_NUMBER, error = true, callable = false), DialPad.hint("1234567890123", me))
        assertEquals(DialHint(PhoneSession.BAD_NUMBER, error = true, callable = false), DialPad.hint("*#06#", me))
    }

    @Test
    fun serviceNumbersAreValidAndNamed() {
        assertEquals(4, ServiceNumbers.ALL.size)
        assertTrue(ServiceNumbers.ALL.all { PhoneNumber.isValid(it.number) })
        assertEquals("Echo test (core 1)", ServiceNumbers.label("+883160655500100"))
        assertEquals("Playback test (core 2)", ServiceNumbers.label("+883150355500101"))
        assertEquals(null, ServiceNumbers.label("+883160655501234"))
        assertEquals(null, ServiceNumbers.label(null))
    }
}
