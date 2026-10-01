package org.opencell.core.phone

import org.opencell.core.protocol.DialCheck
import org.opencell.core.protocol.PhoneNumber

/** The line under the keypad's number: what Call will dial, or why it can't. [callable]: the Call button may be enabled. */
data class DialHint(val text: String, val error: Boolean, val callable: Boolean)

/**
 * The keypad's rules (dial-and-recents spec §3), pure so the iOS app can port
 * them with their tests. The keypad builds a string of `0-9 * #` and `+`
 * ([press], [backspace], [fromPaste]); [format] shows it grouped as it is
 * typed; [hint] says what Call will dial using [PhoneNumber.check], the same
 * dial plan as the terminal.
 */
object DialPad {
    /** The keys, row by row. */
    val KEYS = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')

    /** Longer than any dialled form [PhoneNumber.check] accepts (18 digits after `00`); more presses are ignored. */
    const val MAX_LENGTH = 20

    /** The letters under a key (ITU E.161), or `+` under 0. */
    fun letters(key: Char): String = when (key) {
        '2' -> "ABC"
        '3' -> "DEF"
        '4' -> "GHI"
        '5' -> "JKL"
        '6' -> "MNO"
        '7' -> "PQRS"
        '8' -> "TUV"
        '9' -> "WXYZ"
        '0' -> "+"
        else -> ""
    }

    /** What TalkBack says for a key. */
    fun spoken(key: Char): String = when (key) {
        '*' -> "Star"
        '#' -> "Pound"
        else -> key.toString()
    }

    /** [input] with [key] (a key, or `+` from a long press on 0) added, unless it is full. */
    fun press(input: String, key: Char): String = if (input.length >= MAX_LENGTH) input else input + key

    fun backspace(input: String): String = input.dropLast(1)

    /**
     * Pasted text as keypad input: a `tel:` prefix (percent-escapes decoded) and
     * every separator or letter dropped, any script's digits as 0-9, `+` kept only
     * in front, `*` and `#` kept, cut at [MAX_LENGTH]. It stops at an extension or
     * a dialling pause (`ext`, `x`, `,`, `;`, `p`, `w`), so the digits after it
     * can't turn into a different, valid number. Paste replaces what was typed
     * (there is no cursor).
     */
    fun fromPaste(text: String): String {
        var t = text.trim()
        if (t.startsWith("tel:", ignoreCase = true)) t = PERCENT.replace(t.substring(4)) { it.groupValues[1].toInt(16).toChar().toString() }
        EXTENSION.find(t)?.let { t = t.substring(0, it.range.first) }
        val sb = StringBuilder()
        for (c in t) {
            if (sb.length >= MAX_LENGTH) break
            val digit = c.digitToIntOrNull()
            when {
                digit != null -> sb.append('0' + digit)
                c == '+' && sb.isEmpty() -> sb.append(c)
                c == '*' || c == '#' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val PERCENT = Regex("%([0-9A-Fa-f]{2})")

    /** Where an extension or a pause starts: `,` `;`, or `ext`/`extension`/`x`/`p`/`w` (not inside a word) before a digit. */
    private val EXTENSION = Regex("""[,;]|(?<![a-z])(?:extension|ext\.?|[xpw])(?=[\s.:=#]*[0-9])""", RegexOption.IGNORE_CASE)

    /**
     * [input] grouped for the display as it is typed, the way
     * [PhoneNumber.display] shows a whole number: `+883-1-606-555-01234`,
     * `+883-44-2079460000`, national `606-555-01234` (`1 606-555-1235` with the
     * trunk 1), `00 883-1-…`. Anything else (`*`, `#`, a `+` inside, a
     * non-OpenCell international number, too many digits) is shown as typed.
     */
    fun format(input: String): String {
        val plus = input.startsWith('+')
        val d = if (plus) input.substring(1) else input
        if (d.isEmpty() || !d.all { it in '0'..'9' }) return input
        return when {
            plus -> "+" + international(d)
            d.startsWith("00") -> if (d.length == 2) d else "00 " + international(d.substring(2))
            d.startsWith("883") -> international(d)
            d.startsWith("1") -> if (d.length == 1) d else "1 " + nanp(d.substring(1))
            else -> nanp(d)
        }
    }

    private fun international(d: String): String {
        if (d.length <= 3 || !d.startsWith("883")) return d
        val rest = d.substring(3)
        val ccLen = PhoneNumber.countryCodeLength(rest) ?: return "883-$rest"
        if (rest.length <= ccLen) return "883-$rest"
        val cc = rest.substring(0, ccLen)
        val national = rest.substring(ccLen)
        return if (cc == "1") "883-1-" + nanp(national) else "883-$cc-$national"
    }

    /** NPA-NXX-subscriber, as far as typed; more than 11 digits can't be NANP: as typed. */
    private fun nanp(n: String): String = when {
        n.length <= 3 -> n
        n.length <= 6 -> "${n.substring(0, 3)}-${n.substring(3)}"
        n.length <= 11 -> "${n.substring(0, 3)}-${n.substring(3, 6)}-${n.substring(6)}"
        else -> n
    }

    /**
     * True while [input] could still become a number by typing more digits:
     * only digits after an optional leading `+`, and fewer than the longest
     * form (15 digits international, 10 national, 11 with the trunk 1).
     * While this holds, an incomplete number gets no error under it.
     */
    fun stillTyping(input: String): Boolean {
        val plus = input.startsWith('+')
        val d = if (plus) input.substring(1) else input
        if (!d.all { it in '0'..'9' }) return false
        return when {
            plus -> d.length < PhoneNumber.MAX_DIGITS
            d.startsWith("00") -> d.length - 2 < PhoneNumber.MAX_DIGITS
            d.startsWith("883") -> d.length < PhoneNumber.MAX_DIGITS
            d.startsWith("1") -> d.length < 11
            else -> d.length < 10
        }
    }

    /**
     * The line under the number. A number: "Dials +883-1-…" ([PhoneSession.dialHint]),
     * callable. Emergency: the refusal, as an error. Not (yet) a number: nothing
     * while [stillTyping], else [PhoneSession.BAD_NUMBER] as an error.
     */
    fun hint(input: String, home: String?): DialHint = when (PhoneNumber.check(input, home)) {
        DialCheck.Empty -> DialHint("", error = false, callable = false)
        is DialCheck.Number, is DialCheck.National -> DialHint(PhoneSession.dialHint(input, home), error = false, callable = true)
        DialCheck.Emergency -> DialHint(PhoneSession.EMERGENCY, error = true, callable = false)
        DialCheck.NotANumber ->
            if (stillTyping(input)) DialHint("", error = false, callable = false) else DialHint(PhoneSession.BAD_NUMBER, error = true, callable = false)
    }
}
