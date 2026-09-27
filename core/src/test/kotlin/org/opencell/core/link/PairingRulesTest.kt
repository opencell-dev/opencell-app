package org.opencell.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRulesTest {
    @Test
    fun authFailuresAreRecognised() {
        for (status in listOf(0x05, 0x0F, 0x89, 0x06, 0x3D)) assertTrue("0x%02X".format(status), PairingRules.isAuthFailure(status))
        for (status in listOf(0x00, 0x08, 0x13, 0x80, 0x81, 0x0D, 133)) assertFalse("0x%02X".format(status), PairingRules.isAuthFailure(status))
    }

    @Test
    fun anAuthFailureAfterPairingJustNowIsAFailedPairing() {
        assertEquals(PairingProblem.FAILED, PairingRules.classify(0x05, bondedBefore = false))
    }

    @Test
    fun anAuthFailureWithAnOldBondIsAStaleBond() {
        assertEquals(PairingProblem.STALE_BOND, PairingRules.classify(0x05, bondedBefore = true))
        assertEquals(PairingProblem.STALE_BOND, PairingRules.classify(0x06, bondedBefore = true))
    }

    @Test
    fun otherFailuresAreNotAboutPairing() {
        assertNull(PairingRules.classify(0x08, bondedBefore = true))
        assertNull(PairingRules.classify(133, bondedBefore = false))
    }

    @Test
    fun theHintNamesTheCodeAndTheButton() {
        assertTrue(PairingRules.HINT.contains("6-digit code"))
        assertTrue(PairingRules.HINT.contains("PRG"))
    }
}
