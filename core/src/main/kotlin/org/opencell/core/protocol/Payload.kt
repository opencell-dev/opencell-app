package org.opencell.core.protocol

/** Result of checking an UP payload against the terminal's limits. */
sealed interface PayloadCheck {
    data object Ok : PayloadCheck

    /** The terminal would accept an empty write, but empty payloads are never delivered upward. */
    data object Empty : PayloadCheck

    data class TooLong(val size: Int, val max: Int = GattContract.MAX_PAYLOAD) : PayloadCheck

    val isOk: Boolean get() = this == Ok

    val message: String
        get() = when (this) {
            Ok -> "OK"
            Empty -> "Payload is empty"
            is TooLong -> "Payload is $size bytes; the limit is $max"
        }
}

object PayloadRules {
    fun check(payload: ByteArray): PayloadCheck = when {
        payload.isEmpty() -> PayloadCheck.Empty
        payload.size > GattContract.MAX_PAYLOAD -> PayloadCheck.TooLong(payload.size)
        else -> PayloadCheck.Ok
    }

    /**
     * True if the terminal can send [size] bytes while merely attached
     * (IDLE, no grant): those go out as RACH UPPER, limited to 8 bytes.
     */
    fun fitsRach(size: Int): Boolean = size <= GattContract.RACH_MAX_PAYLOAD
}

/** Hex parsing and formatting for the console. */
object Hex {
    sealed interface Parse {
        class Ok(val bytes: ByteArray) : Parse
        data class Error(val message: String) : Parse
    }

    /**
     * Parses hex like `48 45 4c`, `48-45-4C`, `48:45:4c`, `0x48 0x45` or `48454c`.
     * Separators (space, `-`, `:`, `,`) and `0x` prefixes are ignored.
     */
    fun parse(text: String): Parse {
        val digits = StringBuilder()
        val tokens = text.trim().split(Regex("[\\s,:\\-]+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            val t = if (token.startsWith("0x") || token.startsWith("0X")) token.substring(2) else token
            if (tokens.size > 1 && t.length == 1) {
                digits.append('0') // "a b c" -> 0a 0b 0c
            }
            digits.append(t)
        }
        val bad = digits.firstOrNull { Character.digit(it, 16) < 0 }
        if (bad != null) return Parse.Error("'$bad' is not a hex digit")
        if (digits.length % 2 != 0) return Parse.Error("Odd number of hex digits")
        val out = ByteArray(digits.length / 2) {
            ((Character.digit(digits[2 * it], 16) shl 4) or Character.digit(digits[2 * it + 1], 16)).toByte()
        }
        return Parse.Ok(out)
    }

    fun format(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { "%02x".format(it.toInt() and 0xFF) }

    /** Printable-ASCII view, one character per byte (`.` for anything else), like `hexdump -C`. */
    fun ascii(bytes: ByteArray): String =
        String(CharArray(bytes.size) { i ->
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E) b.toChar() else '.'
        })
}
