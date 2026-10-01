package org.opencell.core.calllog

import org.opencell.core.voice.VoiceCounters

/**
 * The call log as text, for the phone's preferences (dial-and-recents spec
 * §4.3): a header line `oc-calllog 1`, then one entry per line, newest first,
 * ten tab-separated fields, `-` for null:
 *
 * `id  kind  number  startedAt  connectedAt  endedAt  cause  codec  seen(0/1)  sent,notSent,received,concealed`
 *
 * Numbers hold only `+` and digits, so nothing needs escaping. [decode] skips
 * a line it can't read rather than losing the whole log, keeps only the first
 * (newest) line of an id that repeats, and returns nothing for a header it
 * doesn't know (a later version's log, after a downgrade: see [isNewerVersion]).
 */
object CallLogCodec {
    const val HEADER = "oc-calllog 1"
    private val NUMBER = Regex("""\+?[0-9]{1,18}""")
    private val ANY_HEADER = Regex("""oc-calllog ([0-9]{1,9})""")
    private const val VERSION = 1

    /**
     * True when [text] is a call log written by a later version of the app (a
     * header `oc-calllog N` with N above this one's): it can't be read here, and
     * must not be written over either, so upgrading again finds it whole.
     */
    fun isNewerVersion(text: String?): Boolean {
        val first = text?.substringBefore('\n') ?: return false
        val v = ANY_HEADER.matchEntire(first)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return v > VERSION
    }

    fun encode(entries: List<CallLogEntry>): String = buildString {
        append(HEADER)
        for (e in entries) {
            append('\n')
            append(
                listOf(
                    e.id.toString(),
                    e.kind.name,
                    e.number ?: "-",
                    e.startedAt.toString(),
                    e.connectedAt?.toString() ?: "-",
                    e.endedAt.toString(),
                    e.causeCode?.toString() ?: "-",
                    e.codec?.toString() ?: "-",
                    if (e.seen) "1" else "0",
                    e.voice?.let { "${it.sent},${it.notSent},${it.received},${it.concealed}" } ?: "-",
                ).joinToString("\t"),
            )
        }
    }

    fun decode(text: String?): List<CallLogEntry> {
        if (text == null) return emptyList()
        val lines = text.split('\n')
        if (lines.first() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull(::line).distinctBy { it.id }
    }

    private fun line(l: String): CallLogEntry? {
        val f = l.split('\t')
        if (f.size != 10) return null
        return try {
            CallLogEntry(
                id = f[0].toLong(),
                kind = CallKind.valueOf(f[1]),
                number = f[2].orNull()?.also { require(NUMBER.matches(it)) },
                startedAt = f[3].toLong(),
                connectedAt = f[4].orNull()?.toLong(),
                endedAt = f[5].toLong(),
                causeCode = f[6].orNull()?.toInt(),
                codec = f[7].orNull()?.toInt(),
                seen = when (f[8]) {
                    "1" -> true
                    "0" -> false
                    else -> throw IllegalArgumentException("seen: ${f[8]}")
                },
                voice = f[9].orNull()?.let { v ->
                    val n = v.split(',').map(String::toInt)
                    require(n.size == 4)
                    VoiceCounters(n[0], n[1], n[2], n[3])
                },
            )
        } catch (e: IllegalArgumentException) { // NumberFormatException too: a line this can't read is skipped
            null
        }
    }

    private fun String.orNull(): String? = takeIf { it != "-" }
}
