package org.opencell.core.protocol

import java.util.UUID

/**
 * The terminal's BLE GATT contract v3 (v2 with numbering-v2 numbers: 8 BCD
 * bytes in EVENTs, any dialled form in DIAL), mirrored from
 * `firmware/components/oc_term/include/oc_term_gatt.h` and the constants in
 * `firmware/components/oc_sig/include/oc_sig.h` (branch `numbers-v2`).
 *
 * Keep this file in sync with those headers: it is the only place the app
 * hard-codes UUIDs, sizes and ATT error codes.
 */
object GattContract {
    /** Primary service. Advertised as a complete 128-bit UUID list. */
    val SERVICE: UUID = uuid(0x01)

    /** Write / write-without-response: one app data frame (at most [MAX_PAYLOAD] bytes) per write. */
    val UP: UUID = uuid(0x02)

    /** Notify: one app data frame per notification. */
    val DOWN: UUID = uuid(0x03)

    /** Read / notify: [STATUS_LEN] bytes, decoded by [TerminalStatus.decode]. */
    val STATUS: UUID = uuid(0x04)

    /** Write with response: `op (1) || args`, built by [Command.encode]. */
    val COMMAND: UUID = uuid(0x05)

    /** Notify: `ev (1) || args`, decoded by [TerminalEvent.decode]. */
    val EVENT: UUID = uuid(0x06)

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** `OC_SIG_APP_MAX`: largest app data frame on UP and DOWN (contract v1 allowed 20). */
    const val MAX_PAYLOAD = 18

    /** `OC_GATT_STATUS_LEN`. */
    const val STATUS_LEN = 20

    /** `OC_GATT_STATUS_SIG`: the STATUS byte that holds the signalling state ([SigState]). */
    const val STATUS_SIG = 3

    /** `OC_GATT_COMMAND_MAX`: op byte plus 120 bytes of QR text. */
    const val COMMAND_MAX = 121

    /** Longest QR text ACTIVATE takes. */
    const val QR_TEXT_MAX = 120

    /** `OC_SIG_DIAL_MAX`: longest DIAL argument. */
    const val DIAL_MAX = 24

    /** `OC_GATT_EVENT_MAX`. */
    const val EVENT_MAX = 16

    /** DEACTIVATE's confirmation byte. */
    const val DEACTIVATE_CONFIRM = 0xA5

    /**
     * ATT 0x80. On UP: no grant, not in a connected call, in Part 15 also
     * refused outside a connected call even with a grant (the media gate:
     * there's no key for it yet), or the UL queue is full; retry later.
     * On COMMAND: the terminal is not in the right state for it.
     */
    const val ATT_ERR_NOT_NOW = 0x80

    /** ATT 0x0D, invalid attribute value length: UP too long, or COMMAND of the wrong length. Never retry. */
    const val ATT_ERR_INVALID_LENGTH = 0x0D

    /** ATT 0x81 (`OC_SIG_ATT_BAD_ARG`): a malformed COMMAND argument (QR text, number, confirmation byte). */
    const val ATT_ERR_BAD_ARG = 0x81

    /**
     * ATT 0x05, insufficient authentication: every characteristic (and every
     * CCCD) needs a link encrypted with a passkey-authenticated key. An
     * unpaired phone gets it; Android then pairs.
     */
    const val ATT_ERR_INSUFFICIENT_AUTHENTICATION = 0x05

    /** ATT 0x0F, insufficient encryption. */
    const val ATT_ERR_INSUFFICIENT_ENCRYPTION = 0x0F

    /** The terminal's device name prefix; the rest is the TMID in hex. */
    const val NAME_PREFIX = "OpenCell-"

    /** The radio frame: one UP and one DOWN payload per frame at most. */
    const val FRAME_MILLIS = 120L

    /** 6c63 is ASCII "lc", from the project's LoRaCell days: frozen, never renamed (terminals, bonded phones and their GATT caches know these UUIDs). */
    private fun uuid(id: Int): UUID =
        UUID.fromString("6c63%04x-7e2a-4b8e-9f2d-3c1a5e7b0d10".format(id))
}
