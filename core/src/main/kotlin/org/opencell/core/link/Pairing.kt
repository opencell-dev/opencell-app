package org.opencell.core.link

import org.opencell.core.protocol.GattContract

/**
 * Why a connect needs the user instead of another automatic retry
 * (spec 2026-09-27-ble-pairing-design.md §4).
 */
enum class PairingProblem {
    /**
     * Pairing didn't complete: the system dialog was cancelled, the code was
     * wrong, it timed out, or the terminal refused it (3 wrong codes lock
     * pairing for 60 s). Retrying shows the dialog again.
     */
    FAILED,

    /**
     * The phone holds a bond the terminal no longer has (its bonds were
     * cleared by holding PRG for 5 s on the Pairing screen). Only the user can
     * remove the phone's side: Bluetooth settings > the terminal > Forget.
     */
    STALE_BOND,
}

/** Thrown by a [Connector] when a connect failed for a [PairingProblem]; [LinkManager] then stops retrying. */
class PairingException(val problem: PairingProblem, message: String) : Exception(message)

/** How the GATT transport reads pairing failures. Pure, so it is unit-tested here. */
object PairingRules {
    /** Android's GATT_INSUF_AUTHENTICATION when the stack's own pairing attempt failed. */
    const val GATT_AUTH_FAIL = 0x89

    /** HCI "PIN or key missing": the peer has no key for the phone's bond (a disconnect reason). */
    const val HCI_PIN_OR_KEY_MISSING = 0x06

    /** HCI "authentication failure": also how the terminal drops a phone while pairing is locked. */
    const val HCI_AUTH_FAILURE = 0x05

    /** Shown while the system pairing dialog is up. */
    const val HINT = "Enter the 6-digit code shown on the terminal's screen (press PRG to reach the Pairing screen)."

    /**
     * Whether a GATT status (an operation's result or the disconnect status)
     * means the link lacked the authenticated encryption the terminal requires.
     */
    fun isAuthFailure(status: Int): Boolean = when (status) {
        GattContract.ATT_ERR_INSUFFICIENT_AUTHENTICATION, // == HCI_AUTH_FAILURE
        GattContract.ATT_ERR_INSUFFICIENT_ENCRYPTION,
        GATT_AUTH_FAIL,
        HCI_PIN_OR_KEY_MISSING,
        -> true
        else -> false
    }

    /**
     * What an auth failure during connection setup means. [bondedBefore]: the
     * phone already held a bond when this connect began, so it didn't just
     * pair — the terminal must have forgotten it.
     */
    fun classify(status: Int, bondedBefore: Boolean): PairingProblem? = when {
        !isAuthFailure(status) -> null
        bondedBefore -> PairingProblem.STALE_BOND
        else -> PairingProblem.FAILED
    }
}
