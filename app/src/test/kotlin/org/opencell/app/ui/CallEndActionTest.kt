package org.opencell.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencell.core.phone.CallPhase

/** [CallActivity]'s pure close-itself decision, from the call's phase alone. */
class CallEndActionTest {
    @Test
    fun noCallFinishesNow() {
        assertEquals(CallEndAction.FinishNow, callEndAction(null))
    }

    @Test
    fun endedFinishesAfterADelay() {
        assertEquals(CallEndAction.FinishAfter(3_000L), callEndAction(CallPhase.ENDED))
    }

    @Test
    fun anyOtherActivePhaseWaits() {
        for (phase in listOf(CallPhase.CALLING, CallPhase.RINGING, CallPhase.INCOMING, CallPhase.CONNECTED, CallPhase.RELEASING)) {
            assertEquals("phase $phase", CallEndAction.Wait, callEndAction(phase))
        }
    }
}
