package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberTest {
    private fun hex(b: ByteArray) = Hex.format(b)
    private fun bytes(h: String) = (Hex.parse(h) as Hex.Parse.Ok).bytes

    /**
     * Every row of the dial-plan vectors shared with the terminal's C code
     * (`host-tests/vectors/numbers.txt` on branch `numbers-v2`, copied
     * unchanged to `src/test/resources/numbers.txt`): "dialled|home|expected".
     */
    @Test
    fun dialPlanVectorsMatchTheTerminal() {
        val text = requireNotNull(javaClass.getResourceAsStream("/numbers.txt")) { "numbers.txt missing" }
            .bufferedReader().readText()
        var rows = 0
        for (line in text.lines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val (dialled, home, want) = line.split("|").also { assertEquals(line, 3, it.size) }
            val got = PhoneNumber.normalize(dialled, home.takeIf { it != "-" })
            assertEquals("dialled \"$dialled\" home $home", want.takeIf { it != "reject" }, got)
            if (got != null) assertTrue(got, PhoneNumber.isValid(got))
            rows++
        }
        assertTrue("only $rows rows", rows >= 50)
    }

    @Test
    fun bcdMatchesTheFirmware() {
        // Golden bytes from oc_sig host test test_golden_bytes (numbering v2).
        assertEquals("88 31 60 65 55 01 23 4f", hex(PhoneNumber.toBcd("+883160655501234")))
        assertEquals("88 31 60 65 55 00 10 0f", hex(PhoneNumber.toBcd("+883160655500100")))
        assertEquals("88 34 42 07 94 60 00 0f", hex(PhoneNumber.toBcd("+883442079460000")))
        assertEquals("88 34 41 23 ff ff ff ff", hex(PhoneNumber.toBcd("+88344123")))
        assertEquals("+883160655501234", PhoneNumber.fromBcd(PhoneNumber.toBcd("+883160655501234")))
        assertEquals("+883160655500100", PhoneNumber.fromBcd(bytes("00 88 31 60 65 55 00 10 0f"), 1))
    }

    @Test
    fun validBcdIsCanonicalOnly() {
        assertTrue(PhoneNumber.isValidBcd(bytes("88 31 60 65 55 01 23 4f")))
        assertFalse(PhoneNumber.isValidBcd(bytes("98 31 60 65 55 01 23 4f"))) // not 883
        assertFalse(PhoneNumber.isValidBcd(bytes("88 31 60 6a 55 01 23 4f"))) // nibble A
        assertFalse(PhoneNumber.isValidBcd(bytes("88 31 60 65 55 01 23 40"))) // no filler
        assertFalse(PhoneNumber.isValidBcd(bytes("88 34 42 07 94 60 0f 0f"))) // a digit after the filler
        assertFalse(PhoneNumber.isValidBcd(bytes("88 31 60 65 55 12 34 ff"))) // CC 1 with 14 digits
        assertFalse(PhoneNumber.isValidBcd(ByteArray(8)))
    }

    @Test
    fun bcdTextStopsAtTheFirstFillerNibble() {
        // oc_sig_number_to_text stops at a nibble > 9; an all-filler number is just "+".
        assertEquals("+883", PhoneNumber.fromBcd(bytes("88 3f 12 34 56 78 9f ff")))
        assertEquals("+", PhoneNumber.fromBcd(ByteArray(8) { 0xFF.toByte() }))
        assertEquals("+8836065551234", PhoneNumber.fromBcd(bytes("88 36 06 55 51 23 4f"), 0, PhoneNumber.OLD_BCD_LEN))
    }

    @Test
    fun displaysTheInternationalFormWithDashes() {
        assertEquals("+883-1-606-555-01234", PhoneNumber.display("+883160655501234"))
        assertEquals("+883-1-606-555-00100", PhoneNumber.display("883160655500100"))
        assertEquals("+883-44-2079460000", PhoneNumber.display("+883442079460000"))
        assertEquals("+883-7-12345678", PhoneNumber.display("+883712345678"))
        assertEquals("+883-380-1234567", PhoneNumber.display("+8833801234567"))
        // A v1 number is well-formed under v2 (country code 60): it can't be told apart by its digits.
        assertEquals("+883-60-65551234", PhoneNumber.display("+8836065551234"))
        // Not a number: shown unchanged.
        assertEquals("+883", PhoneNumber.display("+883"))
        assertEquals("6065551235", PhoneNumber.display("6065551235"))
    }

    @Test
    fun checkSaysWhyItIsNotANumber() {
        val home = "+883160655501234"
        assertEquals(DialCheck.Empty, PhoneNumber.check("", home))
        assertEquals(DialCheck.Empty, PhoneNumber.check(" - ", home))
        assertEquals(DialCheck.Emergency, PhoneNumber.check("911", home))
        assertEquals(DialCheck.Emergency, PhoneNumber.check("1-1-2", home))
        assertEquals(DialCheck.Emergency, PhoneNumber.check("999", null))
        assertEquals(DialCheck.NotANumber, PhoneNumber.check("555-1235", home))
        assertEquals(DialCheck.Number("+883160655501235"), PhoneNumber.check("606 555 1235", home))
        // Own number not known yet: a national form goes to the terminal as digits.
        assertEquals(DialCheck.National("6065551235"), PhoneNumber.check("606-555-1235", null))
        assertEquals(DialCheck.National("16065551235"), PhoneNumber.check("1 606 555 1235", null))
        assertEquals(DialCheck.NotANumber, PhoneNumber.check("106-555-1235", null))
        assertNull(PhoneNumber.normalize("606-555-1235", null))
    }

    @Test
    fun validFullForms() {
        assertTrue(PhoneNumber.isValid("+883160655501234"))
        assertTrue(PhoneNumber.isValid("883160655501234"))
        assertFalse(PhoneNumber.isValid("+8831606555012")) // CC 1, 13 digits
        assertFalse(PhoneNumber.isValid("+883-1-606-555-01234")) // full form only
        assertFalse(PhoneNumber.isValid(""))
    }
}
