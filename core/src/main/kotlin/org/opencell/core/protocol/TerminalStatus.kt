package org.opencell.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `lc_term_state_t`. */
enum class TerminalState(val code: Int, val label: String, val description: String) {
    SEARCH(0, "Search", "Looking for a cell's sync beacon"),
    SYNCED(1, "Synced", "Tracking frame timing, waiting to attach"),
    ATTACHING(2, "Attaching", "RACH attach sent, waiting for a grant"),
    IDLE(3, "Idle", "Attached, no slots (UP is refused until a grant)"),
    GRANTED(4, "Granted", "DL/UL slots running every frame");

    companion object {
        fun fromCode(code: Int): TerminalState? = entries.firstOrNull { it.code == code }
    }
}

/** `lc_band_t`. */
enum class Band(val code: Int, val label: String) {
    BAND_915(0, "915 MHz"),
    BAND_2G4(1, "2.4 GHz");

    companion object {
        fun fromCode(code: Int): Band? = entries.firstOrNull { it.code == code }
    }
}

/** `lc_tier_t`: the link's modulation tier (near = fastest, edge = most robust). */
enum class Tier(val code: Int, val label: String) {
    NEAR(0, "Near"),
    MID(1, "Mid"),
    EDGE(2, "Edge");

    companion object {
        fun fromCode(code: Int): Tier? = entries.firstOrNull { it.code == code }
    }
}

/**
 * `lc_sig_state_t`, STATUS byte 3: what the terminal's signalling is doing.
 * The terminal runs activation, registration and calls; the app only shows this.
 */
enum class SigState(val code: Int, val label: String) {
    NOT_ACTIVATED(0, "Not activated"),
    ACTIVATING(1, "Activating"),
    REGISTERING(2, "Registering"),
    REGISTERED(3, "Registered"),
    CALLING(4, "Calling"),
    RINGING_OUT(5, "Ringing"),
    RINGING_IN(6, "Incoming call"),
    IN_CALL(7, "In call"),
    RELEASING(8, "Ending call");

    /** True from CALLING to RELEASING: a call exists on the terminal. */
    val hasCall: Boolean get() = code >= CALLING.code

    companion object {
        fun fromCode(code: Int): SigState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One decoded STATUS characteristic value (20 bytes, little-endian):
 *
 * ```
 *  0 u8  state      1 u8 band      2 u8 tier      3 u8 signalling state (SigState)
 *  4 i16 rssi_dbm   6 i16 snr_qdb (0.25 dB; 0 on FLRC links)
 *  8 u32 tmid      12 u32 frame   16 u32 cell_seed
 * ```
 *
 * Raw codes are kept so that values this app version does not know
 * (a newer firmware) still display instead of failing to decode.
 * Unsigned 32-bit fields are widened to [Long].
 */
data class TerminalStatus(
    val stateCode: Int,
    val bandCode: Int,
    val tierCode: Int,
    val rssiDbm: Int,
    val snrQuarterDb: Int,
    val tmid: Long,
    val frame: Long,
    val cellSeed: Long,
    /** STATUS byte 3 ([SigState]); contract v1 firmware sent 0 here. */
    val sigCode: Int = 0,
) {
    val state: TerminalState? get() = TerminalState.fromCode(stateCode)
    val sig: SigState? get() = SigState.fromCode(sigCode)
    val band: Band? get() = Band.fromCode(bandCode)
    val tier: Tier? get() = Tier.fromCode(tierCode)

    /** SNR in dB. The terminal reports 0 on FLRC links, which have no SNR. */
    val snrDb: Double get() = snrQuarterDb / 4.0

    val stateLabel: String get() = state?.label ?: "Unknown ($stateCode)"
    val bandLabel: String get() = band?.label ?: "Unknown ($bandCode)"
    val tierLabel: String get() = tier?.label ?: "Unknown ($tierCode)"
    val sigLabel: String get() = sig?.label ?: "Unknown ($sigCode)"
    val tmidHex: String get() = Tmid.format(tmid)

    /**
     * The terminal's radio signal, for display alongside [stateLabel]. While
     * searching, rssi is the strongest packet heard in the terminal's recent
     * scan, with [snrDb]; but BLE STATUS carries no noise floor or "heard"
     * flag, so 0 there is the only way to say nothing was heard, and would
     * otherwise show as a misleadingly strong "0 dBm". Every other state
     * always has a real reading (the terminal is synced to a cell).
     */
    val signalLabel: String get() = if (noSignal) "No signal" else "$rssiDbm dBm"

    private val noSignal: Boolean get() = state == TerminalState.SEARCH && rssiDbm == 0

    /** [snrDb] for display: none with "No signal" ([signalLabel]), and none on FLRC links. */
    val snrLabel: String
        get() = when {
            noSignal -> "–"
            snrQuarterDb == 0 && band == Band.BAND_2G4 -> "– (FLRC)"
            else -> "%.2f dB".format(snrDb)
        }

    /** Packs this status exactly like `lc_term_pack_status()`. Used by the simulator and tests. */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(GattContract.STATUS_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(stateCode.toByte())
        buf.put(bandCode.toByte())
        buf.put(tierCode.toByte())
        buf.put(sigCode.toByte())
        buf.putShort(rssiDbm.toShort())
        buf.putShort(snrQuarterDb.toShort())
        buf.putInt(tmid.toInt())
        buf.putInt(frame.toInt())
        buf.putInt(cellSeed.toInt())
        return buf.array()
    }

    companion object {
        /**
         * Decodes a STATUS value. Longer values are accepted (a future firmware
         * may append fields); shorter ones are rejected.
         *
         * @throws IllegalArgumentException if [raw] is shorter than [GattContract.STATUS_LEN].
         */
        fun decode(raw: ByteArray): TerminalStatus {
            require(raw.size >= GattContract.STATUS_LEN) {
                "STATUS is ${raw.size} bytes, expected ${GattContract.STATUS_LEN}"
            }
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return TerminalStatus(
                stateCode = buf.get(0).toInt() and 0xFF,
                bandCode = buf.get(1).toInt() and 0xFF,
                tierCode = buf.get(2).toInt() and 0xFF,
                rssiDbm = buf.getShort(4).toInt(),
                snrQuarterDb = buf.getShort(6).toInt(),
                tmid = buf.getInt(8).toLong() and 0xFFFF_FFFFL,
                frame = buf.getInt(12).toLong() and 0xFFFF_FFFFL,
                cellSeed = buf.getInt(16).toLong() and 0xFFFF_FFFFL,
                sigCode = buf.get(GattContract.STATUS_SIG).toInt() and 0xFF,
            )
        }

        /** [decode], or null if the value is too short. */
        fun decodeOrNull(raw: ByteArray): TerminalStatus? =
            if (raw.size >= GattContract.STATUS_LEN) decode(raw) else null
    }
}
