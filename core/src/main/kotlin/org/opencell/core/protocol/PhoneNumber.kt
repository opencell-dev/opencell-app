package org.opencell.core.protocol

/** What a dialled string means (numbering v2, `numbering-plan.md` v0.2 "Dial Plan"). */
sealed interface DialCheck {
    /** Nothing typed yet (only spaces and separators). */
    data object Empty : DialCheck

    /** A number: [full] is its full form, `+883…`, which DIAL sends. */
    data class Number(val full: String) : DialCheck

    /**
     * A national form (`606-555-1235`) while this terminal's own number isn't
     * known yet: the app can't complete it, so DIAL sends [digits] and the
     * terminal completes them from its own number (BLE contract v3).
     */
    data class National(val digits: String) : DialCheck

    /** 911, 112 or 999: OpenCell carries no emergency calls. */
    data object Emergency : DialCheck

    /** Anything else. */
    data object NotANumber : DialCheck
}

/**
 * OpenCell numbers, numbering v2 (`numbering-plan.md` v0.2): 883 · country
 * code · national number, 8-15 digits; country code 1 (NANP) is exactly 15:
 * NPA (3) · NXX (3) · subscriber (5). The full form is `+883160655501234`.
 *
 * On the wire (EVENT, QR code) numbers are 8 BCD bytes, high nibble first,
 * then 0xF in every remaining nibble. This is a port of the terminal's
 * `lc_sig_number.c` (branch `numbers-v2`); the shared vectors in
 * `src/test/resources/numbers.txt` keep the two in step.
 */
object PhoneNumber {
    const val BCD_LEN = 8
    const val MAX_DIGITS = 15
    private const val MIN_DIGITS = 8

    /** EVENT number length of firmware from before numbering v2 (7 BCD bytes, 13 digits). */
    const val OLD_BCD_LEN = 7

    /** ITU-T E.164 two-digit country codes; 1 and 7 are the one-digit ones, the rest have three. */
    private val CC2 = setOf(
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66", "81", "82", "84",
        "86", "90", "91", "92", "93", "94", "95", "98",
    )
    private val EMERGENCY = setOf("911", "112", "999")
    private const val SEPARATORS = " -.()"

    private fun nanpCodeOk(c: String) = c[0] in '2'..'9' && !(c[1] == '1' && c[2] == '1')

    /** The rules every number obeys, on its digits (no `+`). */
    private fun digitsOk(d: String): Boolean {
        if (d.length !in MIN_DIGITS..MAX_DIGITS || !d.startsWith("883") || !d.all { it in '0'..'9' }) return false
        if (d[3] == '1') { // NANP: 883 1 NPA NXX subscriber(5)
            val npa = d.substring(4, 7)
            return d.length == 15 && nanpCodeOk(npa) && npa != "883" && nanpCodeOk(d.substring(7, 10))
        }
        return true
    }

    /** True if [number] is a full form (`+` optional, no separators) that obeys every rule. */
    fun isValid(number: String): Boolean = digitsOk(number.removePrefix("+"))

    /**
     * The full form of what a user dialled, completed from [home] (the
     * caller's own number, full form) for national forms; null if it isn't a
     * number. A port of `lc_sig_number_normalize`.
     */
    fun normalize(dialled: String, home: String?): String? = (check(dialled, home) as? DialCheck.Number)?.full

    /** [normalize], with the reason when it isn't a number. */
    fun check(dialled: String, home: String?): DialCheck {
        val d = StringBuilder()
        var plus = false
        for (c in dialled) { // 1. strip separators; '+' only first
            when {
                c in SEPARATORS -> Unit
                c == '+' && d.isEmpty() && !plus -> plus = true
                c in '0'..'9' && d.length < MAX_DIGITS + 3 -> d.append(c)
                else -> return DialCheck.NotANumber
            }
        }
        if (d.isEmpty()) return if (plus) DialCheck.NotANumber else DialCheck.Empty
        var digits = d.toString()
        if (!plus && digits in EMERGENCY) return DialCheck.Emergency
        if (!plus && digits.startsWith("00")) { // 2. "00" international prefix
            plus = true
            digits = digits.substring(2)
        }
        var full: String
        if (plus || (digits.length >= 12 && digits.startsWith("883"))) { // 2-3. international
            if (digits.length > MAX_DIGITS) return DialCheck.NotANumber
            full = digits
        } else { // 4. in-country, by the caller's country
            var national = digits
            if ((national.length == 11 || national.length == 12) && national[0] == '1') national = national.substring(1)
            if (national.length != 10 && national.length != 11) return DialCheck.NotANumber
            if (home == null) {
                // Only NANP has a national plan: check the shape, and let the terminal complete it.
                return if (digitsOk(complete("8831$national"))) DialCheck.National(digits) else DialCheck.NotANumber
            }
            val h = home.removePrefix("+")
            if (!digitsOk(h) || h[3] != '1') return DialCheck.NotANumber
            full = "8831$national"
        }
        full = complete(full)
        return if (digitsOk(full)) DialCheck.Number("+$full") else DialCheck.NotANumber // 6.
    }

    /** 5. A NANP national number of 10 digits gets its subscriber's 0 back. */
    private fun complete(full: String): String =
        if (full.length == 14 && full.startsWith("8831")) full.substring(0, 10) + "0" + full.substring(10) else full

    /** BCD of a valid full form. */
    fun toBcd(number: String): ByteArray {
        val d = number.removePrefix("+")
        require(digitsOk(d)) { "not an OpenCell number: $number" }
        val b = ByteArray(BCD_LEN) { 0xFF.toByte() }
        for (i in d.indices) {
            val v = d[i] - '0'
            val cur = b[i / 2].toInt() and 0xFF
            b[i / 2] = (if (i % 2 == 0) (v shl 4) or 0x0F else (cur and 0xF0) or v).toByte()
        }
        return b
    }

    /** `+` and the digits up to the first nibble that isn't 0-9, like `lc_sig_number_to_text`. */
    fun fromBcd(bytes: ByteArray, offset: Int = 0, length: Int = BCD_LEN): String {
        val sb = StringBuilder("+")
        for (i in 0 until minOf(2 * length, MAX_DIGITS)) {
            val b = bytes[offset + i / 2].toInt() and 0xFF
            val v = if (i % 2 == 0) b shr 4 else b and 0x0F
            if (v > 9) break
            sb.append('0' + v)
        }
        return sb.toString()
    }

    /** True if the 8 bytes at [offset] are a canonical number: digits, then only 0xF (`lc_sig_number_valid`). */
    fun isValidBcd(bytes: ByteArray, offset: Int = 0): Boolean {
        val d = StringBuilder()
        var filler = false
        for (i in 0 until 2 * BCD_LEN) {
            val b = bytes[offset + i / 2].toInt() and 0xFF
            val v = if (i % 2 == 0) b shr 4 else b and 0x0F
            when {
                v == 0x0F -> filler = true
                v > 9 || filler -> return false
                else -> d.append('0' + v)
            }
        }
        return filler && digitsOk(d.toString())
    }

    /**
     * For people: `+883-1-606-555-01234`, or `+883-44-2079460000` for a country
     * without a national plan (`lc_sig_number_format`). Anything that isn't a
     * valid number is shown unchanged.
     */
    fun display(number: String): String {
        val d = number.removePrefix("+")
        if (!digitsOk(d)) return number
        val ccLen = when {
            d[3] == '1' || d[3] == '7' -> 1
            d.substring(3, 5) in CC2 -> 2
            else -> 3
        }
        val cc = d.substring(3, 3 + ccLen)
        val national = d.substring(3 + ccLen)
        return if (cc == "1") {
            "+883-1-${national.substring(0, 3)}-${national.substring(3, 6)}-${national.substring(6)}"
        } else {
            "+883-$cc-$national"
        }
    }
}
