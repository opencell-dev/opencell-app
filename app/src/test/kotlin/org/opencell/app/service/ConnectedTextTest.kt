package org.opencell.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencell.core.phone.Call
import org.opencell.core.phone.CallPhase
import org.opencell.core.phone.Direction
import org.opencell.core.phone.PhoneState
import org.opencell.core.protocol.SigState

/** What the ongoing notification says while the link is up: a silent call (waiting for its codecs, or turned down) is not announced. */
class ConnectedTextTest {
    private val me = "+883160655501234"
    private val peer = "+883160655500100"
    private val idle = PhoneState(linkUp = true, sig = SigState.REGISTERED, number = me)
    private fun ringing(awaitingCaps: Boolean = false, declined: Boolean = false) = idle.copy(
        sig = SigState.RINGING_IN,
        call = Call(7, Direction.INCOMING, peer, CallPhase.INCOMING, awaitingCaps = awaitingCaps, declined = declined),
    )

    @Test
    fun aRingingCallIsAnnouncedWithItsCaller() {
        val text = connectedText(ringing(), null)
        assertTrue(text, text.startsWith("Incoming call · "))
    }

    @Test
    fun aCallWaitingForItsCodecsIsNotAnnounced() {
        val text = connectedText(ringing(awaitingCaps = true), null)
        assertFalse(text, text.contains("Incoming"))
        assertEquals(connectedText(idle, null), text)
    }

    @Test
    fun aCallBeingTurnedDownIsNotAnnounced() {
        val text = connectedText(ringing(declined = true), null)
        assertFalse(text, text.contains("Incoming"))
        assertEquals(connectedText(idle, null), text)
    }

    @Test
    fun withoutAPhoneStateItFallsBackToConnected() {
        assertEquals("Connected", connectedText(null, null))
    }
}
