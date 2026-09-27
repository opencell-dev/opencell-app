package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberTest {
    private fun hex(b: ByteArray) = Hex.format(b)

    @Test
    fun parsesWhatPeopleType() {
        for (s in listOf("+8836065551234", "8836065551234", "+883 606 555 1234", "+883-606-555-1234", "00883 606 555 1234", " (+883) 606.555.1234 ")) {
            assertEquals(s, "+8836065551234", PhoneNumber.parse(s))
        }
    }

    @Test
    fun rejectsWhatTheTerminalRejects() {
        // lc_sig_number_to_bcd: exactly 13 digits starting 883.
        for (s in listOf("", "+", "+883606555123", "+88360655512345", "+4917612345678", "+883606555123x", "++8836065551234")) {
            assertNull(s, PhoneNumber.parse(s))
        }
    }

    @Test
    fun bcdMatchesTheFirmware() {
        // Golden bytes from lc_sig host test test_golden_bytes (CALL_SETUP called number).
        assertEquals("88 36 06 55 51 23 4f", hex(PhoneNumber.toBcd("+8836065551234")))
        assertEquals("88 36 06 55 50 10 0f", hex(PhoneNumber.toBcd("+8836065550100")))
        assertEquals("+8836065551234", PhoneNumber.fromBcd(PhoneNumber.toBcd("+8836065551234")))
        assertEquals("+8836065550100", PhoneNumber.fromBcd(byteArrayOf(0, 0x88.toByte(), 0x36, 0x06, 0x55, 0x50, 0x10, 0x0F), 1))
    }

    @Test
    fun bcdStopsAtTheFirstFillerNibble() {
        // lc_sig_number_to_text stops at a nibble > 9; an all-filler number is just "+".
        assertEquals("+883", PhoneNumber.fromBcd(byteArrayOf(0x88.toByte(), 0x3F, 0x12, 0x34, 0x56, 0x78, 0x9F.toByte())))
        assertEquals("+", PhoneNumber.fromBcd(ByteArray(7) { 0xFF.toByte() }))
    }

    @Test
    fun displaysInGroups() {
        assertEquals("+883 606 555 1234", PhoneNumber.display("+8836065551234"))
        assertEquals("+883", PhoneNumber.display("+883"))
    }
}
