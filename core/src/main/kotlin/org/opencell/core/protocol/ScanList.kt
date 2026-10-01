package org.opencell.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The terminal's scan list (contract v4, channel-list spec §5 and §9): where it
 * looks for a cell, in order. Mirrors `oc_term_scan.h` and the SCAN
 * characteristic in `oc_term_gatt.h`.
 */
enum class ScanSource(val code: Int, val label: String, val letter: Char) {
    LAST(1, "last cell", 'L'),
    USER(2, "yours", 'U'),
    NETWORK(3, "network", 'N'),
    LEARNED(4, "learned", 'K'),
    DEFAULT(5, "default", 'D'),
    SWEEP(6, "search outside the list", 'S');

    companion object {
        fun fromCode(code: Int): ScanSource? = entries.firstOrNull { it.code == code }
    }
}

/** The 915 MHz grid the anchors sit on: 52 channels, 902.25 to 927.75 MHz, 500 kHz apart. */
object ChannelGrid {
    const val COUNT = 52
    const val BASE_HZ = 902_250_000L
    const val SPACING_HZ = 500_000L

    fun freqHz(channel: Int): Long {
        require(channel in 0 until COUNT) { "channel $channel is not 0-51" }
        return BASE_HZ + channel * SPACING_HZ
    }

    /** The channel of [freqHz], or null if it is not exactly on the grid. */
    fun channelOf(freqHz: Long): Int? {
        val d = freqHz - BASE_HZ
        return if (d >= 0 && d % SPACING_HZ == 0L && d / SPACING_HZ < COUNT) (d / SPACING_HZ).toInt() else null
    }

    /** "917.25" (MHz, two decimals). */
    fun mhz(freqHz: Long): String = "%d.%02d".format(freqHz / 1_000_000, freqHz % 1_000_000 / 10_000)

    /** Every grid frequency, low to high. */
    val all: List<Long> get() = (0 until COUNT).map(::freqHz)
}

/** One entry: [active] is false when the terminal's mode doesn't allow it (it is kept, not scanned). */
data class ScanEntry(val freqHz: Long, val fixed: Boolean, val sourceCode: Int, val active: Boolean) {
    val source: ScanSource? get() = ScanSource.fromCode(sourceCode)
    val mhzLabel: String get() = ChannelGrid.mhz(freqHz)

    /** "yours · fixed · not used in this mode" */
    val detail: String
        get() = listOfNotNull(
            source?.label ?: "source $sourceCode",
            if (fixed) "fixed sync (Part 97)" else null,
            if (!active) "not used in this mode" else null,
        ).joinToString(" · ")
}

/** The SCAN characteristic: `fmt 1 ‖ mode ‖ fallback_after ‖ fallback_chunk ‖ net_ver ‖ count ‖ count × {freq_hz u32 LE, flags}`. */
data class ScanList(
    val modeCode: Int,
    val fallbackAfter: Int,
    val fallbackChunk: Int,
    val netVer: Int,
    val entries: List<ScanEntry>,
) {
    val mode: RegMode? get() = RegMode.fromCode(modeCode)

    /** The user's own entries, in order (what SET_USER replaces). */
    val userEntries: List<ScanEntry> get() = entries.filter { it.source == ScanSource.USER }

    /** "after 2 passes, 13 channels a round", or "never". */
    val fallbackLabel: String
        get() = if (fallbackAfter >= NEVER) "never" else
            "after $fallbackAfter pass${if (fallbackAfter == 1) "" else "es"}, $fallbackChunk channels a round"

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(6 + 5 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(FORMAT.toByte()).put(modeCode.toByte()).put(fallbackAfter.toByte()).put(fallbackChunk.toByte())
        buf.put(netVer.toByte()).put(entries.size.toByte())
        for (e in entries) {
            buf.putInt(e.freqHz.toInt())
            buf.put(((if (e.fixed) 1 else 0) or (e.sourceCode shl 1) or (if (e.active) 0x10 else 0)).toByte())
        }
        return buf.array()
    }

    companion object {
        const val FORMAT = 1

        /** `fallback_after` value meaning "never search outside the list". */
        const val NEVER = 15

        /** At most this many user entries (`OC_SCAN_MAX_USER`). */
        const val MAX_USER = 4

        /** A SCAN value, or null if its format is unknown or it is truncated. */
        fun decode(raw: ByteArray): ScanList? {
            if (raw.size < 6 || raw[0].toInt() != FORMAT) return null
            val count = raw[5].toInt() and 0xFF
            if (raw.size < 6 + 5 * count) return null
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val entries = (0 until count).map { i ->
                val at = 6 + 5 * i
                val flags = raw[at + 4].toInt() and 0xFF
                ScanEntry(
                    freqHz = buf.getInt(at).toLong() and 0xFFFF_FFFFL,
                    fixed = flags and 1 != 0,
                    sourceCode = (flags shr 1) and 7,
                    active = flags and 0x10 != 0,
                )
            }
            return ScanList(
                modeCode = raw[1].toInt() and 0xFF,
                fallbackAfter = raw[2].toInt() and 0xFF,
                fallbackChunk = raw[3].toInt() and 0xFF,
                netVer = raw[4].toInt() and 0xFF,
                entries = entries,
            )
        }
    }
}

/** STATUS bytes 20-26 (contract v4): where the terminal is looking, or its serving cell's anchor. */
data class ScanTail(val pos: Int, val len: Int, val sourceCode: Int, val freqKhz: Long) {
    val source: ScanSource? get() = ScanSource.fromCode(sourceCode)
    val mhzLabel: String get() = ChannelGrid.mhz(freqKhz * 1000)
}
