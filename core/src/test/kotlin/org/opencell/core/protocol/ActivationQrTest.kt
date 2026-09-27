package org.opencell.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationQrTest {
    /**
     * The golden code of lc_sig's host test (host-tests/test_sig_msg.c, computed
     * independently in Python): key id 1, PKn = 1..32, token id a0..a7,
     * secret b0..bf, +8836065551234, expiry 0x12345678.
     */
    private val golden = "opencell:1:AQEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyCgoaKjpKWmp7CxsrO0tba3uLm6u7y9vr-" +
        "INgZVUSNPeFY0EoZ3"

    private fun ok(text: String) = (ActivationQr.parse(text) as QrParse.Ok).qr
    private fun invalid(text: String) = (ActivationQr.parse(text) as QrParse.Invalid).reason

    @Test
    fun crcIsCcittFalse() {
        // The CRC-16/CCITT-FALSE check value.
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".encodeToByteArray()))
    }

    @Test
    fun parsesTheGoldenCode() {
        val qr = ok(golden)
        assertEquals(golden, qr.text)
        assertEquals(1, qr.keyId)
        assertEquals("a0a1a2a3a4a5a6a7", qr.tokenId)
        assertEquals("+8836065551234", qr.number)
        assertEquals(0x12345678L, qr.expiryUnix)
        assertEquals(ActivationQr.TEXT_LEN, golden.length)
    }

    @Test
    fun formatsTheGoldenCode() {
        val text = ActivationQr.format(
            keyId = 1,
            networkKey = ByteArray(32) { (it + 1).toByte() },
            tokenId = ByteArray(8) { (0xa0 + it).toByte() },
            tokenSecret = ByteArray(16) { (0xb0 + it).toByte() },
            number = "+8836065551234",
            expiryUnix = 0x12345678L,
        )
        assertEquals(golden, text)
    }

    @Test
    fun trimsWhitespaceLikeTheTerminal() {
        assertEquals(golden, ok("  $golden\r\n").text)
        assertEquals(golden, ok("\t$golden ").text)
    }

    @Test
    fun rejectsWhatTheTerminalRejects() {
        val oneCharChanged = golden.toCharArray().also { it[40] = if (it[40] == 'A') 'B' else 'A' }.concatToString()
        assertEquals("The code is damaged (checksum mismatch)", invalid(oneCharChanged))
        assertEquals("Unsupported activation code version", invalid("opencell:2:AAAA"))
        assertEquals("Incomplete code: expected 96 characters after opencell:1:, found 95", invalid(golden.dropLast(1)))
        assertEquals("Not an OpenCell activation code", invalid("https://example.org"))
        assertEquals("The code contains a character that isn't base64url: '+'", invalid(golden.replaceRange(20, 21, "+")))
        // Version byte 2 with a correct CRC is still refused.
        val v2 = ActivationQr.format(1, ByteArray(32), ByteArray(8), ByteArray(16), "+8836065551234", 0)
        val blob = java.util.Base64.getUrlDecoder().decode(v2.removePrefix(ActivationQr.PREFIX)).also { it[0] = 2 }
        val crc = Crc16.ccittFalse(blob, 70)
        blob[70] = crc.toByte(); blob[71] = (crc shr 8).toByte()
        val text = ActivationQr.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
        assertEquals("Unsupported activation code version 2", invalid(text))
    }

    @Test
    fun expiryIsForDisplay() {
        val qr = ok(golden)
        assertTrue(qr.isExpired(0x12345678L))
        assertFalse(qr.isExpired(0x12345677L))
    }
}
