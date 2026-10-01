package org.opencell.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPlanTest {
    @Test
    fun takenInFrontDuringACallKeptInTheBackgroundDroppedAfter() {
        // callActive, appInFront, granted, holding -> hold
        assertEquals(true, micPlan(callActive = true, appInFront = true, granted = true, holding = false))
        assertEquals(false, micPlan(callActive = true, appInFront = false, granted = true, holding = false)) // Android would refuse
        assertEquals(true, micPlan(callActive = true, appInFront = false, granted = true, holding = true))
        assertEquals(false, micPlan(callActive = true, appInFront = true, granted = false, holding = false))
        assertEquals(false, micPlan(callActive = true, appInFront = true, granted = false, holding = true)) // revoked mid-call
        assertEquals(false, micPlan(callActive = false, appInFront = true, granted = true, holding = true))
    }
}
