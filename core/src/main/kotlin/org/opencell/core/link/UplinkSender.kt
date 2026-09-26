package org.opencell.core.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencell.core.protocol.PayloadCheck
import org.opencell.core.protocol.PayloadRules

sealed interface SendOutcome {
    /** Number of UP writes made (0 if rejected locally). */
    val attempts: Int

    data class Sent(override val attempts: Int) : SendOutcome

    /** Rejected before touching the link. */
    data class Invalid(val check: PayloadCheck) : SendOutcome {
        override val attempts: Int get() = 0
    }

    /** The last write's result; [attempts] tells whether retries were exhausted. */
    data class Failed(val last: WriteResult, override val attempts: Int) : SendOutcome

    val label: String
        get() = when (this) {
            is Sent -> if (attempts == 1) "sent" else "sent after $attempts attempts"
            is Invalid -> check.message
            is Failed -> "${last.label} after $attempts attempt${if (attempts == 1) "" else "s"}"
        }
}

/**
 * Reliable-ish UP sending for control traffic: validates the payload, then
 * writes it with the [RetryPolicy] (0x80 retried with backoff, 0x0D never).
 *
 * Sends are serialized so payloads reach the terminal in call order. A
 * payload being retried holds back the ones behind it, which is right for
 * console and call-control messages. A voice codec should NOT use this: a late
 * voice frame is worthless, so the codec layer will write through
 * [TerminalLink.writeUp] once per frame and drop on 0x80 instead.
 */
class UplinkSender(
    private val link: TerminalLink,
    private val policy: RetryPolicy = RetryPolicy(),
) {
    private val mutex = Mutex()

    /**
     * @param enforceLimit check the 20-byte limit locally (turn off only to test the terminal's 0x0D).
     * @param onAttempt called right before each write, with the 1-based attempt number.
     */
    suspend fun send(
        payload: ByteArray,
        enforceLimit: Boolean = true,
        onAttempt: (Int) -> Unit = {},
    ): SendOutcome {
        val check = PayloadRules.check(payload)
        if (check is PayloadCheck.Empty || (enforceLimit && check is PayloadCheck.TooLong)) {
            return SendOutcome.Invalid(check)
        }
        val data = payload.copyOf()
        return mutex.withLock { sendLocked(data, onAttempt) }
    }

    private suspend fun sendLocked(data: ByteArray, onAttempt: (Int) -> Unit): SendOutcome {
        var attempts = 0
        while (true) {
            attempts++
            onAttempt(attempts)
            val result = link.writeUp(data)
            if (result == WriteResult.Accepted) return SendOutcome.Sent(attempts)
            when (val d = policy.decide(attempts, result)) {
                is RetryDecision.RetryAfter -> delay(d.delay)
                RetryDecision.GiveUp -> return SendOutcome.Failed(result, attempts)
            }
        }
    }
}
