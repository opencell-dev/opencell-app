package org.opencell.core.protocol

/**
 * COMMAND writes (`op (1) || args`, contract v3). The terminal answers each
 * write with success or an ATT error: 0x80 not in the right state, 0x0D bad
 * length, 0x81 malformed argument. Commands are never retried automatically:
 * 0x80 means the state is wrong, and the app resyncs from STATUS instead.
 */
sealed interface Command {
    val op: Int
    val label: String
    fun encode(): ByteArray

    /** ACTIVATE with the QR text (ASCII, at most [GattContract.QR_TEXT_MAX] bytes; a long write is fine). */
    data class Activate(val qrText: String) : Command {
        override val op get() = ACTIVATE
        override val label get() = "ACTIVATE"
        override fun encode() = byteArrayOf(op.toByte()) + qrText.trim().toByteArray(Charsets.US_ASCII)
    }

    /**
     * DIAL with the number in ASCII (at most [GattContract.DIAL_MAX] bytes): the
     * full form `+883…` from [PhoneNumber.normalize], or national digits the
     * terminal completes from its own number ([DialCheck.National]).
     */
    data class Dial(val number: String) : Command {
        override val op get() = DIAL
        override val label get() = "DIAL $number"
        override fun encode() = byteArrayOf(op.toByte()) + number.toByteArray(Charsets.US_ASCII)
    }

    data object Answer : Command {
        override val op get() = ANSWER
        override val label get() = "ANSWER"
        override fun encode() = byteArrayOf(op.toByte())
    }

    data object Reject : Command {
        override val op get() = REJECT
        override val label get() = "REJECT"
        override fun encode() = byteArrayOf(op.toByte())
    }

    data object Hangup : Command {
        override val op get() = HANGUP
        override val label get() = "HANGUP"
        override fun encode() = byteArrayOf(op.toByte())
    }

    /** DEACTIVATE wipes the terminal's keys; the 0xA5 byte confirms it. */
    data object Deactivate : Command {
        override val op get() = DEACTIVATE
        override val label get() = "DEACTIVATE"
        override fun encode() = byteArrayOf(op.toByte(), GattContract.DEACTIVATE_CONFIRM.toByte())
    }

    companion object {
        const val ACTIVATE = 0x01
        const val DIAL = 0x02
        const val ANSWER = 0x03
        const val REJECT = 0x04
        const val HANGUP = 0x05
        const val DEACTIVATE = 0x06
    }
}
