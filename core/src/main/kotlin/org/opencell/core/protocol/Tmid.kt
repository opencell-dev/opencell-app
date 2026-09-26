package org.opencell.core.protocol

/** Terminal identity (TMID): a u32 shown as 8 hex digits, also carried in the BLE name. */
object Tmid {
    private val NAME = Regex("^${Regex.escape(GattContract.NAME_PREFIX)}([0-9A-Fa-f]{8})$")

    /** The TMID from an `OpenCell-XXXXXXXX` device name, or null if the name doesn't match. */
    fun fromDeviceName(name: String?): Long? =
        name?.let { NAME.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLong(16)

    fun format(tmid: Long): String = "%08X".format(tmid and 0xFFFF_FFFFL)
}
