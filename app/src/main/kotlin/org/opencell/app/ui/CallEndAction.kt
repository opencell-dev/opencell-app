package org.opencell.app.ui

import org.opencell.core.phone.CallPhase

/** What [CallActivity] should do about the call's current phase. */
sealed interface CallEndAction {
    /** No call left to show: finish right away. */
    data object FinishNow : CallEndAction

    /** The call just ended: let "Call ended" show for a moment, then finish. */
    data class FinishAfter(val delayMillis: Long) : CallEndAction

    /** An active call: stay open. */
    data object Wait : CallEndAction
}

/**
 * [CallActivity]'s pure decision, from the call's phase alone (unit-testable
 * on its own, the same way as [org.opencell.app.service.ringPlan]).
 */
fun callEndAction(phase: CallPhase?): CallEndAction = when (phase) {
    null -> CallEndAction.FinishNow
    CallPhase.ENDED -> CallEndAction.FinishAfter(END_FINISH_DELAY_MS)
    else -> CallEndAction.Wait
}

private const val END_FINISH_DELAY_MS = 3_000L
