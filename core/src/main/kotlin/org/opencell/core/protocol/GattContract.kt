package org.opencell.core.protocol

import java.util.UUID

/**
 * The terminal's BLE GATT contract, mirrored from
 * `firmware/components/lc_term/include/lc_term_gatt.h`.
 *
 * Keep this file in sync with the header: it is the only place the app
 * hard-codes UUIDs, sizes and ATT error codes.
 */
object GattContract {
    /** Primary service. Advertised as a complete 128-bit UUID list. */
    val SERVICE: UUID = uuid(0x01)

    /** Write / write-without-response: one upper-layer payload per write. */
    val UP: UUID = uuid(0x02)

    /** Notify: one downlink payload per notification. */
    val DOWN: UUID = uuid(0x03)

    /** Read / notify: [STATUS_LEN] bytes, decoded by [TerminalStatus.decode]. */
    val STATUS: UUID = uuid(0x04)

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** `LC_TERM_DATA_MAX_PAYLOAD`: largest UP payload, set by the 28-byte air slot. */
    const val MAX_PAYLOAD = 20

    /** `LC_TERM_RACH_MAX_PAYLOAD`: largest payload the terminal can send without a grant. */
    const val RACH_MAX_PAYLOAD = 8

    /** `LC_GATT_STATUS_LEN`. */
    const val STATUS_LEN = 20

    /** `LC_GATT_ERR_NOT_NOW`: no grant yet / queue full. Retry later. */
    const val ATT_ERR_NOT_NOW = 0x80

    /** Invalid attribute value length: the payload was too long. Never retry. */
    const val ATT_ERR_INVALID_LENGTH = 0x0D

    /** The terminal's device name prefix; the rest is the TMID in hex. */
    const val NAME_PREFIX = "OpenCell-"

    /** The radio frame: one UP and one DOWN payload per frame at most. */
    const val FRAME_MILLIS = 120L

    private fun uuid(id: Int): UUID =
        UUID.fromString("6c63%04x-7e2a-4b8e-9f2d-3c1a5e7b0d10".format(id))
}
