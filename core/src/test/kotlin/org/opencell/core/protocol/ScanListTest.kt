package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract v4 (channel-list spec §9): the SCAN characteristic, the STATUS tail and
 * COMMAND SCAN, byte for byte as the firmware's host tests (test_oled_gatt.c) and
 * tools/ble/test_oc_ble.py pin them.
 */
class ScanListTest {
    private fun hex(b: ByteArray) = Hex.format(b)
    private fun bytes(h: String) = (Hex.parse(h) as Hex.Parse.Ok).bytes

    @Test
    fun gridFrequencies() {
        assertEquals(902_250_000L, ChannelGrid.freqHz(0))
        assertEquals(927_750_000L, ChannelGrid.freqHz(51))
        assertEquals(30, ChannelGrid.channelOf(917_250_000L))
        assertNull(ChannelGrid.channelOf(917_300_000L))
        assertNull(ChannelGrid.channelOf(928_250_000L))
        assertEquals("917.25", ChannelGrid.mhz(917_250_000L))
        assertEquals("902.75", ChannelGrid.mhz(902_750_000L))
        assertEquals(52, ChannelGrid.all.size)
    }

    /** test_oled_gatt.c test_scan_packing: Part 97, net_ver 4, ch 10 FIXED user entry, then ch 0 default. */
    @Test
    fun decodesTheFirmwaresScanValue() {
        val raw = bytes("01 02 02 0d 04 02 50 89 13 36 15 10 3e c7 35 1a")
        val l = ScanList.decode(raw)!!
        assertEquals(RegMode.PART97, l.mode)
        assertEquals(2, l.fallbackAfter)
        assertEquals(13, l.fallbackChunk)
        assertEquals(4, l.netVer)
        assertEquals(
            listOf(
                ScanEntry(907_250_000L, fixed = true, sourceCode = 2, active = true),
                ScanEntry(902_250_000L, fixed = false, sourceCode = 5, active = true),
            ),
            l.entries,
        )
        assertEquals(ScanSource.USER, l.entries[0].source)
        assertEquals("yours · fixed sync (Part 97)", l.entries[0].detail)
        assertEquals(listOf(l.entries[0]), l.userEntries)
        assertEquals(hex(raw), hex(l.encode()))
        assertEquals("after 2 passes, 13 channels a round", l.fallbackLabel)
    }

    @Test
    fun inactiveEntriesAndNever() {
        val l = ScanList.decode(bytes("01 01 0f 0d 00 01 d0 1f ac 36 05"))!! // ch 30 FIXED user, Part 15: inactive
        assertEquals(false, l.entries[0].active)
        assertEquals("yours · fixed sync (Part 97) · not used in this mode", l.entries[0].detail)
        assertEquals("never", l.fallbackLabel)
    }

    @Test
    fun unknownFormatOrTruncatedIsNull() {
        assertNull(ScanList.decode(bytes("02 01 02 0d 00 00")))
        assertNull(ScanList.decode(bytes("01 01 02 0d 00 01 d0 1f ac")))
        assertNull(ScanList.decode(ByteArray(5)))
    }

    /** test_oled_gatt.c test_scan_command_codec and oc_ble.scan_set_user: 917.25 FIXED, 902.75. */
    @Test
    fun scanCommandsEncodeLikeTheFirmwareExpects() {
        assertEquals(
            "07 01 02 d0 1f ac 36 01 30 df ce 35 00",
            hex(Command.ScanSetUser(listOf(UserChannel(917_250_000L, fixed = true), UserChannel(902_750_000L))).encode()),
        )
        assertEquals("07 01 00", hex(Command.ScanSetUser(emptyList()).encode()))
        assertEquals("07 02 0f 34", hex(Command.ScanSetFallback(15, 52).encode()))
        assertEquals("07 03", hex(Command.ScanForgetLearned.encode()))
        assertEquals("SCAN SET_USER 917.25 fixed, 902.75", Command.ScanSetUser(listOf(UserChannel(917_250_000L, true), UserChannel(902_750_000L))).label)
    }

    /** test_oled_gatt.c test_status_packing: searching, 3 of 8, network, 903.25 MHz. */
    @Test
    fun statusTail() {
        val raw = bytes("00 00 02 00 8a ff e5 ff 56 34 12 75 04 03 02 01 0d f0 fe ca 03 08 03 52 c8 0d 00")
        val s = TerminalStatus.decode(raw)
        assertEquals(ScanTail(3, 8, 3, 903_250L), s.scan)
        assertEquals(ScanSource.NETWORK, s.scan?.source)
        assertEquals("Scanning 903.25 MHz (3 of 8, network)", s.scanLabel)
        assertEquals(hex(raw), hex(s.encode()))

        val onCell = s.copy(stateCode = TerminalState.IDLE.code, scan = ScanTail(0, 0, 0, 917_250L))
        assertEquals("Channel 917.25 MHz", onCell.scanLabel)
        assertEquals(27, onCell.encode().size)

        val v3 = TerminalStatus.decode(raw.copyOf(20))
        assertNull(v3.scan)
        assertNull(v3.scanLabel)
        assertEquals(20, v3.encode().size)
    }
}
