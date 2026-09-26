package org.opencell.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {
    @Test
    fun limitIsTwentyBytes() {
        assertEquals(PayloadCheck.Ok, PayloadRules.check(ByteArray(1)))
        assertEquals(PayloadCheck.Ok, PayloadRules.check(ByteArray(20)))
        assertEquals(PayloadCheck.TooLong(21), PayloadRules.check(ByteArray(21)))
        assertEquals(PayloadCheck.Empty, PayloadRules.check(ByteArray(0)))
        assertEquals(GattContract.MAX_PAYLOAD, (PayloadRules.check(ByteArray(64)) as PayloadCheck.TooLong).max)
    }

    @Test
    fun textIsCountedInUtf8Bytes() {
        // 11 characters but 22 bytes: too long although it "looks" short.
        val text = "α".repeat(11)
        assertEquals(11, text.length)
        assertEquals(PayloadCheck.TooLong(22), PayloadRules.check(text.encodeToByteArray()))
    }

    @Test
    fun rachLimitIsEightBytes() {
        assertTrue(PayloadRules.fitsRach(8))
        assertFalse(PayloadRules.fitsRach(9))
    }

    @Test
    fun parsesHexInCommonForms() {
        val hello = "HELLO".encodeToByteArray()
        for (s in listOf("48454c4c4f", "48 45 4c 4c 4f", "48-45-4C-4C-4F", "48:45:4c:4c:4f", "0x48 0x45 0x4c 0x4c 0x4f", " 48,45,4c,4c,4f ")) {
            val r = Hex.parse(s)
            assertTrue(s, r is Hex.Parse.Ok)
            assertArrayEquals(s, hello, (r as Hex.Parse.Ok).bytes)
        }
        assertArrayEquals(byteArrayOf(0x0a, 0x0b), (Hex.parse("a b") as Hex.Parse.Ok).bytes)
        assertArrayEquals(ByteArray(0), (Hex.parse("") as Hex.Parse.Ok).bytes)
    }

    @Test
    fun rejectsBadHex() {
        assertEquals(Hex.Parse.Error("Odd number of hex digits"), Hex.parse("123"))
        assertEquals(Hex.Parse.Error("'g' is not a hex digit"), Hex.parse("4g"))
    }

    @Test
    fun formatsHexAndAscii() {
        val b = byteArrayOf(0x48, 0x49, 0x00, 0xFF.toByte(), 0x7E)
        assertEquals("48 49 00 ff 7e", Hex.format(b))
        assertEquals("48-49-00-ff-7e", Hex.format(b, "-"))
        assertEquals("HI..~", Hex.ascii(b))
    }

    @Test
    fun tmidFromDeviceName() {
        assertEquals(0x76AD0488L, Tmid.fromDeviceName("OpenCell-76AD0488"))
        assertEquals(0xFFFFFFFFL, Tmid.fromDeviceName("OpenCell-ffffffff"))
        assertEquals(null, Tmid.fromDeviceName("OpenCell-76AD048"))
        assertEquals(null, Tmid.fromDeviceName("LoRaCell-76AD0488"))
        assertEquals(null, Tmid.fromDeviceName(null))
        assertEquals("0000ABCD", Tmid.format(0xABCD))
    }

    @Test
    fun uuidsMatchTheHeader() {
        assertEquals("6c630001-7e2a-4b8e-9f2d-3c1a5e7b0d10", GattContract.SERVICE.toString())
        assertEquals("6c630002-7e2a-4b8e-9f2d-3c1a5e7b0d10", GattContract.UP.toString())
        assertEquals("6c630003-7e2a-4b8e-9f2d-3c1a5e7b0d10", GattContract.DOWN.toString())
        assertEquals("6c630004-7e2a-4b8e-9f2d-3c1a5e7b0d10", GattContract.STATUS.toString())
    }
}
