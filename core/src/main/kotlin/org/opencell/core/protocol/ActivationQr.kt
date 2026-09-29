package org.opencell.core.protocol

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR (`oc_crc16`). */
object Crc16 {
    fun ccittFalse(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

/** Result of checking scanned or pasted text as an activation code. */
sealed interface QrParse {
    data class Ok(val qr: ActivationQr) : QrParse
    data class Invalid(val reason: String) : QrParse
}

/**
 * A one-time activation code, v2 (numbering-v2 spec §6.1): `opencell:2:` +
 * base64url (no padding) of a 75-byte blob. The app checks it exactly like the
 * terminal's `oc_sig_qr_parse` (prefix, length, alphabet, version, reserved
 * bytes, CRC, number) so it can show the number and expiry before sending
 * ACTIVATE, but it keeps no secrets: only [text] goes to the terminal, and the
 * token secret is never decoded into a field.
 *
 * Blob: version 2 | key id (2, LE) | PKn (32) | token id (8) | token secret (16) |
 * number (8, BCD) | expiry (4, LE, unix s) | reserved (2, zero) | CRC-16 over
 * bytes 0-72 (2, LE). A v1 code (`opencell:1:`, 7-byte number) is refused.
 */
data class ActivationQr(
    /** The code as sent in ACTIVATE: surrounding whitespace removed. */
    val text: String,
    val keyId: Int,
    /** The token id in hex: identifies the code, not a secret. */
    val tokenId: String,
    val number: String,
    /** Unix seconds. Display only: the network enforces it. */
    val expiryUnix: Long,
) {
    fun isExpired(nowUnix: Long): Boolean = expiryUnix <= nowUnix

    companion object {
        const val PREFIX = "opencell:2:"
        const val BLOB_LEN = 75
        private const val BODY_LEN = BLOB_LEN / 3 * 4 // 100: 75 is a multiple of 3, so no padding
        const val TEXT_LEN = PREFIX.length + BODY_LEN
        const val OLD_CODE = "This is an old activation code (numbering v1). Ask for a new code."
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val TRIM = charArrayOf(' ', '\t', '\r', '\n')

        fun parse(input: String): QrParse {
            val text = input.trim(*TRIM)
            if (!text.startsWith("opencell:")) return QrParse.Invalid("Not an OpenCell activation code")
            if (text.startsWith("opencell:1:")) return QrParse.Invalid(OLD_CODE)
            if (!text.startsWith(PREFIX)) return QrParse.Invalid("Unsupported activation code version")
            val body = text.substring(PREFIX.length)
            if (body.length != BODY_LEN) {
                return QrParse.Invalid("Incomplete code: expected $BODY_LEN characters after $PREFIX, found ${body.length}")
            }
            val blob = ByteArray(BLOB_LEN)
            for (i in 0 until BODY_LEN step 4) {
                var v = 0
                for (j in 0 until 4) {
                    val d = ALPHABET.indexOf(body[i + j])
                    if (d < 0) return QrParse.Invalid("The code contains a character that isn't base64url: '${body[i + j]}'")
                    v = (v shl 6) or d
                }
                val o = i / 4 * 3
                blob[o] = (v shr 16).toByte()
                blob[o + 1] = (v shr 8).toByte()
                blob[o + 2] = v.toByte()
            }
            fun u8(i: Int) = blob[i].toInt() and 0xFF
            if (u8(0) != 2) return QrParse.Invalid("Unsupported activation code version ${u8(0)}")
            if (Crc16.ccittFalse(blob, 73) != (u8(73) or (u8(74) shl 8))) {
                return QrParse.Invalid("The code is damaged (checksum mismatch)")
            }
            if (u8(71) != 0 || u8(72) != 0) return QrParse.Invalid("Unsupported activation code (reserved bytes set)")
            if (!PhoneNumber.isValidBcd(blob, 59)) return QrParse.Invalid("The code's number isn't a valid OpenCell number")
            return QrParse.Ok(
                ActivationQr(
                    text = text,
                    keyId = u8(1) or (u8(2) shl 8),
                    tokenId = Hex.format(blob.copyOfRange(35, 43), ""),
                    number = PhoneNumber.fromBcd(blob, 59),
                    expiryUnix = (0 until 4).fold(0L) { acc, i -> acc or (u8(67 + i).toLong() shl (8 * i)) },
                ),
            )
        }

        /** Builds the text `ocbench mkqr` prints. Used by the simulated terminal and by tests. */
        fun format(
            keyId: Int,
            networkKey: ByteArray,
            tokenId: ByteArray,
            tokenSecret: ByteArray,
            number: String,
            expiryUnix: Long,
        ): String {
            require(networkKey.size == 32 && tokenId.size == 8 && tokenSecret.size == 16)
            val b = ByteArray(BLOB_LEN) // reserved bytes 71-72 stay zero
            b[0] = 2
            b[1] = keyId.toByte()
            b[2] = (keyId shr 8).toByte()
            networkKey.copyInto(b, 3)
            tokenId.copyInto(b, 35)
            tokenSecret.copyInto(b, 43)
            PhoneNumber.toBcd(number).copyInto(b, 59)
            for (i in 0 until 4) b[67 + i] = (expiryUnix shr (8 * i)).toByte()
            val crc = Crc16.ccittFalse(b, 73)
            b[73] = crc.toByte()
            b[74] = (crc shr 8).toByte()
            val sb = StringBuilder(PREFIX)
            for (i in 0 until BLOB_LEN step 3) { // 75 is a multiple of 3: no padding
                val v = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)
                for (shift in intArrayOf(18, 12, 6, 0)) sb.append(ALPHABET[(v shr shift) and 63])
            }
            return sb.toString()
        }
    }
}
