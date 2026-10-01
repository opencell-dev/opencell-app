package org.opencell.core.voice

import java.util.Locale

/** The call progress tones the phone plays itself (voice spec §6). */
enum class CallTone {
    /** The far end is being alerted (ALERTING: the phone's RINGING phase). */
    RINGBACK,

    /** The called party is busy or rejected the call. */
    BUSY,

    /** Reorder / congestion: the network couldn't complete the call. */
    REORDER,

    /** The number can't be reached: SIT in North America, number unobtainable in the UK. */
    UNOBTAINABLE,
}

/** [millis] of the [freqs] together (none: silence). */
data class ToneSegment(val freqs: List<Double>, val millis: Int) {
    companion object {
        fun on(millis: Int, vararg freqs: Double) = ToneSegment(freqs.toList(), millis)
        fun off(millis: Int) = ToneSegment(emptyList(), millis)
    }
}

/**
 * One tone: its [segments] in order, over and over if [repeat], each frequency
 * at [levelDbm0] (0 dBm0 is a sine 3.14 dB below digital full scale, G.711).
 */
data class Tone(val segments: List<ToneSegment>, val repeat: Boolean, val levelDbm0: Double)

/** A national tone plan: the same four tones, as data. */
data class TonePlan(val id: String, val label: String, val tones: Map<CallTone, Tone>) {
    init {
        require(tones.keys == CallTone.entries.toSet()) { "$id lacks ${CallTone.entries - tones.keys}" }
    }

    operator fun get(t: CallTone): Tone = tones.getValue(t)
}

/**
 * The tone plans (voice spec §6.2 for the sources). North American: the
 * Precise Tone Plan (Telcordia SR-2275) with the Intercept SIT. UK: ITU-T
 * E.180 national tones (ITU OB 781 annex, 2003), the BT network tones.
 */
object TonePlans {
    private const val SIT_LEVEL = -24.0

    val NORTH_AMERICA = TonePlan(
        id = "na",
        label = "North American",
        tones = mapOf(
            CallTone.RINGBACK to Tone(listOf(ToneSegment.on(2000, 440.0, 480.0), ToneSegment.off(4000)), repeat = true, levelDbm0 = -19.0),
            CallTone.BUSY to Tone(listOf(ToneSegment.on(500, 480.0, 620.0), ToneSegment.off(500)), repeat = true, levelDbm0 = -24.0),
            CallTone.REORDER to Tone(listOf(ToneSegment.on(250, 480.0, 620.0), ToneSegment.off(250)), repeat = true, levelDbm0 = -24.0),
            // Intercept (IC) SIT: low 913.8 Hz and 1370.6 Hz short (274 ms), 1776.7 Hz long (380 ms), once.
            CallTone.UNOBTAINABLE to Tone(
                listOf(ToneSegment.on(274, 913.8), ToneSegment.on(274, 1370.6), ToneSegment.on(380, 1776.7)),
                repeat = false,
                levelDbm0 = SIT_LEVEL,
            ),
        ),
    )

    val UK = TonePlan(
        id = "uk",
        label = "United Kingdom",
        tones = mapOf(
            CallTone.RINGBACK to Tone(
                listOf(ToneSegment.on(400, 400.0, 450.0), ToneSegment.off(200), ToneSegment.on(400, 400.0, 450.0), ToneSegment.off(2000)),
                repeat = true,
                levelDbm0 = -19.0,
            ),
            CallTone.BUSY to Tone(listOf(ToneSegment.on(375, 400.0), ToneSegment.off(375)), repeat = true, levelDbm0 = -24.0),
            CallTone.REORDER to Tone(
                listOf(ToneSegment.on(400, 400.0), ToneSegment.off(350), ToneSegment.on(225, 400.0), ToneSegment.off(525)),
                repeat = true,
                levelDbm0 = -24.0,
            ),
            CallTone.UNOBTAINABLE to Tone(listOf(ToneSegment.on(1000, 400.0)), repeat = true, levelDbm0 = -24.0),
        ),
    )

    val ALL = listOf(NORTH_AMERICA, UK)

    fun byId(id: String?): TonePlan? = ALL.firstOrNull { it.id == id }

    /** The default: the UK plan for a phone set to the United Kingdom, North American otherwise. */
    fun forLocale(locale: Locale): TonePlan = if (locale.country == "GB") UK else NORTH_AMERICA
}
