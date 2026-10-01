package org.opencell.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * COMMAND writes (`op (1) || args`, contract v4). The terminal answers each
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

    /**
     * SCAN SET_USER: the user's scan-list entries, replacing the old ones (at most
     * [ScanList.MAX_USER]; an empty list clears them). The terminal refuses (0x81)
     * a frequency off the 915 MHz grid ([ChannelGrid]).
     */
    data class ScanSetUser(val channels: List<UserChannel>) : Command {
        override val op get() = SCAN
        override val label get() =
            "SCAN SET_USER " + channels.joinToString { ChannelGrid.mhz(it.freqHz) + if (it.fixed) " fixed" else "" }
                .ifEmpty { "(none)" }

        override fun encode(): ByteArray {
            val b = ByteBuffer.allocate(3 + 5 * channels.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put(op.toByte()).put(SCAN_SET_USER.toByte()).put(channels.size.toByte())
            for (c in channels) {
                b.putInt(c.freqHz.toInt())
                b.put(if (c.fixed) 1 else 0)
            }
            return b.array()
        }
    }

    /** SCAN SET_FALLBACK: search outside the list after [after] passes (15 never), [chunk] channels a round (1-52). */
    data class ScanSetFallback(val after: Int, val chunk: Int) : Command {
        override val op get() = SCAN
        override val label get() = "SCAN SET_FALLBACK $after/$chunk"
        override fun encode() = byteArrayOf(op.toByte(), SCAN_SET_FALLBACK.toByte(), after.toByte(), chunk.toByte())
    }

    /** SCAN FORGET_LEARNED: drop the cells the terminal learned. */
    data object ScanForgetLearned : Command {
        override val op get() = SCAN
        override val label get() = "SCAN FORGET_LEARNED"
        override fun encode() = byteArrayOf(op.toByte(), SCAN_FORGET_LEARNED.toByte())
    }

    companion object {
        const val ACTIVATE = 0x01
        const val DIAL = 0x02
        const val ANSWER = 0x03
        const val REJECT = 0x04
        const val HANGUP = 0x05
        const val DEACTIVATE = 0x06
        const val SCAN = 0x07
        const val SCAN_SET_USER = 0x01
        const val SCAN_SET_FALLBACK = 0x02
        const val SCAN_FORGET_LEARNED = 0x03
    }
}

/** A user scan-list entry: a 915 MHz grid frequency, [fixed] for a Part 97 cell with fixed sync. */
data class UserChannel(val freqHz: Long, val fixed: Boolean = false)
