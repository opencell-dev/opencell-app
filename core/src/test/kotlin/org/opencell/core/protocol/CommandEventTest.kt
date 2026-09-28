package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden bytes for COMMAND and EVENT (contract v3: 8-byte numbers), consistent
 * with lc_term_gatt.h, lc_sig.h (op and event codes), lc_sig_term.c (event
 * layouts) and tools/ble/oc_ble.py on branch numbers-v2.
 */
class CommandEventTest {
    private fun hex(b: ByteArray) = Hex.format(b)
    private fun bytes(h: String) = (Hex.parse(h) as Hex.Parse.Ok).bytes

    @Test
    fun commandsEncodeLikeTheLaptopClient() {
        // oc_ble.py: b"\x01" + text, b"\x02" + number, bytes([3|4|5]), b"\x06\xa5".
        assertEquals("01 6f 70 65 6e 63 65 6c 6c 3a 32 3a 41", hex(Command.Activate("  opencell:2:A\n").encode()))
        assertEquals(
            "02 2b 38 38 33 31 36 30 36 35 35 35 30 30 31 30 30",
            hex(Command.Dial("+883160655500100").encode()),
        )
        assertEquals("03", hex(Command.Answer.encode()))
        assertEquals("04", hex(Command.Reject.encode()))
        assertEquals("05", hex(Command.Hangup.encode()))
        assertEquals("06 a5", hex(Command.Deactivate.encode()))
    }

    @Test
    fun activateCarriesTheWholeQrText() {
        val qr = "opencell:2:" + "A".repeat(100)
        val raw = Command.Activate(qr).encode()
        assertEquals(112, raw.size)
        assertEquals(qr, raw.copyOfRange(1, raw.size).decodeToString())
    }

    @Test
    fun decodesEveryEvent() {
        val cases = listOf(
            "01 88 31 60 65 55 01 23 4f" to TerminalEvent.Activated("+883160655501234"),
            "02 02" to TerminalEvent.ActivationFailed(2),
            "03 88 31 60 65 55 01 23 4f 01" to TerminalEvent.Registered("+883160655501234", 1),
            "04 04" to TerminalEvent.RegistrationFailed(4),
            "05 00 00 00 01 88 31 60 65 55 00 10 0f" to TerminalEvent.Incoming(1, "+883160655500100"),
            "06 01 02 03 04" to TerminalEvent.Ringing(0x01020304),
            "07 00 00 00 05 01" to TerminalEvent.Connected(5, 1),
            "08 ff ff ff fe 01" to TerminalEvent.Ended(0xFFFFFFFEL, 1),
            "09" to TerminalEvent.Deactivated,
        )
        for ((h, ev) in cases) {
            assertEquals(h, ev, TerminalEvent.decode(bytes(h)))
            assertEquals(h, hex(ev.encode()))
        }
    }

    @Test
    fun reasonsAndCausesMapToText() {
        assertEquals(ActFailReason.TOKEN_USED, TerminalEvent.ActivationFailed(2).reason)
        assertEquals("This code has already been used (token used)", TerminalEvent.ActivationFailed(2).reason?.text)
        assertEquals(ActFailReason.TOKEN_EXPIRED, TerminalEvent.ActivationFailed(3).reason)
        assertEquals(ActFailReason.UNKNOWN_TOKEN, TerminalEvent.ActivationFailed(1).reason)
        assertEquals(ActFailReason.BAD_TAG, TerminalEvent.ActivationFailed(4).reason)
        assertEquals(RegMode.PART97, TerminalEvent.Registered("+883160655501234", 2).mode)
        assertEquals(EndCause.REJECTED, TerminalEvent.Ended(1, 1).cause)
        assertEquals(EndCause.LINK_LOST, TerminalEvent.Ended(1, 6).cause)
        assertNull(TerminalEvent.Ended(1, 42).cause)
        assertEquals("ended (call 1): cause 42", TerminalEvent.Ended(1, 42).label)
        assertEquals("registered +883-1-606-555-01234 (Part 15)", TerminalEvent.Registered("+883160655501234", 1).label)
    }

    @Test
    fun shortOrUnknownEventsAreKeptNotThrown() {
        assertEquals(TerminalEvent.Unknown("05 00 00 00 01", 0x05), TerminalEvent.decode(bytes("05 00 00 00 01")))
        assertEquals(TerminalEvent.Unknown("7f 01", 0x7F), TerminalEvent.decode(bytes("7f 01")))
        assertEquals(TerminalEvent.Unknown("", -1), TerminalEvent.decode(ByteArray(0)))
        assertEquals(0x7F, TerminalEvent.decode(bytes("7f 01")).code)
        assertEquals(-1, TerminalEvent.decode(ByteArray(0)).code)
        // The code is the raw byte, kept at decode time: nothing re-parses the hex text.
        assertEquals(0x05, TerminalEvent.Unknown("not hex", 0x05).code)
    }

    /** Numbering v2 §6.3: v2 firmware's lengths (7-byte numbers) are recognised, never misread. */
    @Test
    fun oldFirmwareEventsAreRecognisedByLength() {
        for (h in listOf("01 88 36 06 55 51 23 4f", "03 88 36 06 55 51 23 4f 01", "05 00 00 00 01 88 36 06 55 50 10 0f")) {
            val ev = TerminalEvent.decode(bytes(h))
            assertEquals(h, TerminalEvent.OldFirmware(bytes(h)[0].toInt(), h), ev)
            assertEquals(h, hex(ev.encode()))
        }
        assertEquals(
            "Terminal firmware uses old numbers: update it (event 03 88 36 06 55 51 23 4f 01)",
            TerminalEvent.decode(bytes("03 88 36 06 55 51 23 4f 01")).label,
        )
        // Any other length of these three is just unknown.
        assertEquals(TerminalEvent.Unknown("01 88 31", 0x01), TerminalEvent.decode(bytes("01 88 31")))
        assertEquals(
            TerminalEvent.Unknown("03 88 31 60 65 55 01 23 4f 01 00", 0x03),
            TerminalEvent.decode(bytes("03 88 31 60 65 55 01 23 4f 01 00")),
        )
    }

    /** M2: a v3-length number that isn't canonical/valid BCD is treated like an unknown number, never misread. */
    @Test
    fun invalidNumbersInV3EventsAreTreatedAsUnknown() {
        // NPA "106" (first digit 1, not 2-9): not a valid NANP area code, even though the byte
        // length is exactly a v3 number's (only the NPA differs from the golden "606" vector).
        val badActivated = TerminalEvent.decode(bytes("01 88 31 10 65 55 01 23 4f"))
        assertEquals(TerminalEvent.Activated(null), badActivated)
        assertEquals("activated an unknown number", badActivated.label)

        val badRegistered = TerminalEvent.decode(bytes("03 88 31 10 65 55 01 23 4f 01"))
        assertEquals(TerminalEvent.Registered(null, 1), badRegistered)
        assertEquals("registered an unknown number (Part 15)", badRegistered.label)

        val badIncoming = TerminalEvent.decode(bytes("05 00 00 00 01 88 31 10 65 55 01 23 4f"))
        assertEquals(TerminalEvent.Incoming(1, null), badIncoming)
        assertEquals("incoming call 1 from an unknown number", badIncoming.label)
    }

    @Test
    fun dialCarriesAnyDialledForm() {
        assertEquals("02 36 30 36 35 35 35 31 32 33 35", hex(Command.Dial("6065551235").encode()))
    }

    @Test
    fun trailingBytesAreIgnored() {
        assertEquals(TerminalEvent.Ringing(7), TerminalEvent.decode(bytes("06 00 00 00 07 99 99")))
    }
}
