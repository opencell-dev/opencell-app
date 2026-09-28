package org.opencell.core.protocol

/** ACT_FAILED reasons: 1-4 come from the network (ACT_NAK), 5-6 are found by the terminal. */
enum class ActFailReason(val code: Int, val text: String) {
    UNKNOWN_TOKEN(1, "The network doesn't know this code (unknown token)"),
    TOKEN_USED(2, "This code has already been used (token used)"),
    TOKEN_EXPIRED(3, "This code has expired (token expired)"),
    BAD_TAG(4, "The network couldn't verify this terminal's request (bad tag)"),
    BAD_CONFIRM(5, "The network's answer didn't verify (bad confirm)"),
    TIMEOUT(6, "No answer from the network (timeout)");

    companion object {
        fun fromCode(code: Int): ActFailReason? = entries.firstOrNull { it.code == code }
    }
}

/** REG_FAILED reasons. The terminal retries by itself after a backoff (30 s, doubling to 10 min). */
enum class RegFailReason(val code: Int, val text: String) {
    NOT_ACTIVATED(1, "The network says this terminal isn't activated"),
    AUTH_FAILED(2, "Authentication failed"),
    NETWORK_AUTH(3, "The network failed authentication"),
    TIMEOUT(4, "No answer from the network");

    companion object {
        fun fromCode(code: Int): RegFailReason? = entries.firstOrNull { it.code == code }
    }
}

/** RELEASE causes, carried by ENDED. */
enum class EndCause(val code: Int, val text: String) {
    NORMAL(0, "Call ended"),
    REJECTED(1, "Call rejected"),
    BUSY(2, "Busy"),
    NO_ANSWER(3, "No answer"),
    UNREACHABLE(4, "Number unreachable"),
    NETWORK_FAILURE(5, "Network failure"),
    LINK_LOST(6, "Radio link lost");

    companion object {
        fun fromCode(code: Int): EndCause? = entries.firstOrNull { it.code == code }
    }
}

/** The network's operating mode, from REGISTERED. */
enum class RegMode(val code: Int, val label: String, val description: String) {
    PART15(1, "Part 15", "Signalling and voice encrypted"),
    PART97(2, "Part 97", "Amateur radio: signalling integrity-protected, voice in the clear");

    companion object {
        fun fromCode(code: Int): RegMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * EVENT notifications (`ev (1) || args`, contract v3). Numbers are 8 BCD
 * bytes (numbering v2) and call ids 4 bytes big-endian. Raw codes are kept so
 * a reason this app doesn't know still displays. The terminal does not queue
 * events while no phone is connected: after a (re)connect the app reads
 * STATUS byte 3.
 */
sealed interface TerminalEvent {
    val code: Int
    val label: String
    fun encode(): ByteArray

    /** [number] is null when the terminal sent a v3-length number that isn't canonical/valid BCD (§6.3): treated like an unknown number, never misread. */
    data class Activated(val number: String?) : TerminalEvent {
        override val code get() = ACTIVATED
        override val label get() = "activated ${number?.let(PhoneNumber::display) ?: "an unknown number"}"
        override fun encode() = byteArrayOf(code.toByte()) + (number?.let(PhoneNumber::toBcd) ?: invalidBcd())
    }

    data class ActivationFailed(val reasonCode: Int) : TerminalEvent {
        val reason: ActFailReason? get() = ActFailReason.fromCode(reasonCode)
        override val code get() = ACT_FAILED
        override val label get() = "activation failed: ${reason?.text ?: "reason $reasonCode"}"
        override fun encode() = byteArrayOf(code.toByte(), reasonCode.toByte())
    }

    /** [number] is null when the terminal sent a v3-length number that isn't canonical/valid BCD (§6.3): treated like an unknown number, never misread. */
    data class Registered(val number: String?, val modeCode: Int) : TerminalEvent {
        val mode: RegMode? get() = RegMode.fromCode(modeCode)
        override val code get() = REGISTERED
        override val label get() = "registered ${number?.let(PhoneNumber::display) ?: "an unknown number"} (${mode?.label ?: "mode $modeCode"})"
        override fun encode() = byteArrayOf(code.toByte()) + (number?.let(PhoneNumber::toBcd) ?: invalidBcd()) + modeCode.toByte()
    }

    data class RegistrationFailed(val reasonCode: Int) : TerminalEvent {
        val reason: RegFailReason? get() = RegFailReason.fromCode(reasonCode)
        override val code get() = REG_FAILED
        override val label get() = "registration failed: ${reason?.text ?: "reason $reasonCode"}"
        override fun encode() = byteArrayOf(code.toByte(), reasonCode.toByte())
    }

    /** [caller] is null when the terminal sent a v3-length number that isn't canonical/valid BCD (§6.3): treated like an unknown caller, never misread. */
    data class Incoming(val callId: Long, val caller: String?) : TerminalEvent {
        override val code get() = INCOMING
        override val label get() = "incoming call $callId from ${caller?.let(PhoneNumber::display) ?: "an unknown number"}"
        override fun encode() = byteArrayOf(code.toByte()) + u32(callId) + (caller?.let(PhoneNumber::toBcd) ?: invalidBcd())
    }

    data class Ringing(val callId: Long) : TerminalEvent {
        override val code get() = RINGING
        override val label get() = "ringing (call $callId)"
        override fun encode() = byteArrayOf(code.toByte()) + u32(callId)
    }

    data class Connected(val callId: Long, val codec: Int) : TerminalEvent {
        override val code get() = CONNECTED
        override val label get() = "connected (call $callId, codec $codec)"
        override fun encode() = byteArrayOf(code.toByte()) + u32(callId) + codec.toByte()
    }

    data class Ended(val callId: Long, val causeCode: Int) : TerminalEvent {
        val cause: EndCause? get() = EndCause.fromCode(causeCode)
        override val code get() = ENDED
        override val label get() = "ended (call $callId): ${cause?.text ?: "cause $causeCode"}"
        override fun encode() = byteArrayOf(code.toByte()) + u32(callId) + causeCode.toByte()
    }

    /**
     * ACTIVATED, REGISTERED or INCOMING at its contract-v2 length (7-byte
     * numbers): the terminal runs firmware from before numbering v2. Shown,
     * never read as a v3 event (v2's REGISTERED mode byte would land inside
     * a v3 number).
     */
    data class OldFirmware(override val code: Int, val hex: String) : TerminalEvent {
        override val label get() = "$OLD_FIRMWARE_TEXT (event $hex)"
        override fun encode() = (Hex.parse(hex) as Hex.Parse.Ok).bytes
    }

    data object Deactivated : TerminalEvent {
        override val code get() = DEACTIVATED
        override val label get() = "deactivated"
        override fun encode() = byteArrayOf(code.toByte())
    }

    /** An event code this app doesn't know, or an event too short for its code. [code] is its first byte (-1 if empty). */
    data class Unknown(val hex: String, override val code: Int) : TerminalEvent {
        override val label get() = "unknown event $hex"
        override fun encode() = (Hex.parse(hex) as Hex.Parse.Ok).bytes
    }

    companion object {
        const val ACTIVATED = 0x01
        const val ACT_FAILED = 0x02
        const val REGISTERED = 0x03
        const val REG_FAILED = 0x04
        const val INCOMING = 0x05
        const val RINGING = 0x06
        const val CONNECTED = 0x07
        const val ENDED = 0x08
        const val DEACTIVATED = 0x09

        const val OLD_FIRMWARE_TEXT = "Terminal firmware uses old numbers: update it"

        private const val N = PhoneNumber.BCD_LEN
        private const val OLD_N = PhoneNumber.OLD_BCD_LEN

        /**
         * Decodes one EVENT value. ACTIVATED, REGISTERED and INCOMING must have
         * their exact v3 length (9, 10 and 13 bytes): that is how the app tells
         * v3 firmware from v2 ([OldFirmware]). Other events ignore extra
         * trailing bytes (a newer firmware may add fields).
         */
        fun decode(raw: ByteArray): TerminalEvent {
            val unknown = Unknown(Hex.format(raw), raw.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
            if (raw.isEmpty()) return unknown
            val n = raw.size - 1
            fun u8(i: Int) = raw[i].toInt() and 0xFF
            val old = OldFirmware(u8(0), Hex.format(raw))
            fun number(at: Int) = PhoneNumber.fromBcd(raw, at).takeIf { PhoneNumber.isValidBcd(raw, at) }
            return when (u8(0)) {
                ACTIVATED -> when (n) {
                    N -> Activated(number(1))
                    OLD_N -> old
                    else -> unknown
                }
                ACT_FAILED -> if (n >= 1) ActivationFailed(u8(1)) else unknown
                REGISTERED -> when (n) {
                    N + 1 -> Registered(number(1), u8(1 + N))
                    OLD_N + 1 -> old
                    else -> unknown
                }
                REG_FAILED -> if (n >= 1) RegistrationFailed(u8(1)) else unknown
                INCOMING -> when (n) {
                    4 + N -> Incoming(u32(raw, 1), number(5))
                    4 + OLD_N -> old
                    else -> unknown
                }
                RINGING -> if (n >= 4) Ringing(u32(raw, 1)) else unknown
                CONNECTED -> if (n >= 5) Connected(u32(raw, 1), u8(5)) else unknown
                ENDED -> if (n >= 5) Ended(u32(raw, 1), u8(5)) else unknown
                DEACTIVATED -> Deactivated
                else -> unknown
            }
        }

        private fun u32(raw: ByteArray, at: Int): Long =
            (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (raw[at + i].toLong() and 0xFF) }

        private fun u32(v: Long): ByteArray = ByteArray(4) { i -> (v shr (24 - 8 * i)).toByte() }

        /** A placeholder for [encode] when the number is unknown (invalid on decode): all filler, itself not a valid number. */
        private fun invalidBcd(): ByteArray = ByteArray(N) { 0xFF.toByte() }
    }
}
