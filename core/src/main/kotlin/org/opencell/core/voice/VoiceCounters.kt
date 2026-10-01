package org.opencell.core.voice

/**
 * The four voice counters the call screen shows behind Developer options, and
 * the call log keeps per call: blocks sent, not sent (refused with 0x80, too
 * late, or failed), received, and concealed (a lost block replaced).
 */
data class VoiceCounters(
    val sent: Int = 0,
    val notSent: Int = 0,
    val received: Int = 0,
    val concealed: Int = 0,
) {
    operator fun plus(o: VoiceCounters) =
        VoiceCounters(sent + o.sent, notSent + o.notSent, received + o.received, concealed + o.concealed)

    companion object {
        fun of(s: VoiceStats) = VoiceCounters(s.sent, s.dropped + s.late + s.failed, s.received, s.jitter.concealed)
    }
}
