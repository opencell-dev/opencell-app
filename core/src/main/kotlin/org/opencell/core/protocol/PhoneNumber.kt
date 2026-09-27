package org.opencell.core.protocol

/**
 * OpenCell numbers: +883 E.164, exactly 13 digits starting 883 (`numbering-plan.md`).
 * On the wire (EVENT, QR code) they are 7 BCD bytes, high nibble first, with a
 * 0xF filler nibble (`lc_sig_number_to_bcd` / `lc_sig_number_to_text`).
 */
object PhoneNumber {
    const val BCD_LEN = 7
    private const val DIGITS = 13

    /**
     * The canonical form `+883…` of what a user typed, or null if it isn't an
     * OpenCell number. Spaces, dashes, dots and parentheses are ignored; the
     * `+` is optional and `00` works as the international prefix.
     */
    fun parse(input: String): String? {
        val t = input.filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' || it == ' ' }
        val digits = when {
            t.startsWith("+") -> t.substring(1)
            t.startsWith("00") -> t.substring(2)
            else -> t
        }
        if (digits.length != DIGITS || !digits.startsWith("883") || !digits.all { it in '0'..'9' }) return null
        return "+$digits"
    }

    /** BCD of a number [parse] accepts. */
    fun toBcd(number: String): ByteArray {
        val canonical = requireNotNull(parse(number)) { "not an OpenCell number: $number" }
        val d = canonical.substring(1)
        return ByteArray(BCD_LEN) { i ->
            val hi = d[2 * i] - '0'
            val lo = if (2 * i + 1 < DIGITS) d[2 * i + 1] - '0' else 0x0F
            ((hi shl 4) or lo).toByte()
        }
    }

    /** `+` and the digits up to the first nibble that isn't 0-9, like `lc_sig_number_to_text`. */
    fun fromBcd(bytes: ByteArray, offset: Int = 0): String {
        val sb = StringBuilder("+")
        for (i in 0 until 2 * BCD_LEN) {
            val b = bytes[offset + i / 2].toInt() and 0xFF
            val d = if (i % 2 == 0) b shr 4 else b and 0x0F
            if (d > 9) break
            sb.append('0' + d)
        }
        return sb.toString()
    }

    /** `+883 606 555 1234` for display; anything [parse] rejects is shown unchanged. */
    fun display(number: String): String {
        val c = parse(number) ?: return number
        return "+${c.substring(1, 4)} ${c.substring(4, 7)} ${c.substring(7, 10)} ${c.substring(10)}"
    }
}
