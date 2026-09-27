package org.opencell.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencell.core.phone.CallPhase

/** [LinkService]'s pure ring/notify decision, from the phone's state alone. */
class RingPlanTest {
    @Test
    fun incomingAndLinkUpRingsAndNotifies() {
        assertEquals(
            RingPlan(ring = true, notify = true),
            ringPlan(phase = CallPhase.INCOMING, answering = false, linkUp = true, mainInFront = false),
        )
    }

    @Test
    fun mainActivityInFrontStillRingsButDoesNotNotify() {
        assertEquals(
            RingPlan(ring = true, notify = false),
            ringPlan(phase = CallPhase.INCOMING, answering = false, linkUp = true, mainInFront = true),
        )
    }

    @Test
    fun linkDownNeitherRingsNorNotifies() {
        assertEquals(
            RingPlan(ring = false, notify = false),
            ringPlan(phase = CallPhase.INCOMING, answering = false, linkUp = false, mainInFront = false),
        )
    }

    @Test
    fun answeringStopsTheRingEvenWhileStillIncoming() {
        assertEquals(
            RingPlan(ring = false, notify = false),
            ringPlan(phase = CallPhase.INCOMING, answering = true, linkUp = true, mainInFront = false),
        )
    }

    @Test
    fun noCallNeitherRingsNorNotifies() {
        assertEquals(
            RingPlan(ring = false, notify = false),
            ringPlan(phase = null, answering = false, linkUp = true, mainInFront = false),
        )
    }

    @Test
    fun connectedReleasingAndEndedNeitherRingNorNotify() {
        for (phase in listOf(CallPhase.CALLING, CallPhase.RINGING, CallPhase.CONNECTED, CallPhase.RELEASING, CallPhase.ENDED)) {
            assertEquals(
                "phase $phase",
                RingPlan(ring = false, notify = false),
                ringPlan(phase = phase, answering = false, linkUp = true, mainInFront = false),
            )
        }
    }
}
