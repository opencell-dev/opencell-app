package org.opencell.core.voice

/**
 * The keypad's key tones (dial-and-recents spec §3.4): DTMF, ITU-T Q.23, the
 * row frequency plus the column frequency, made by the same [ToneGenerator]
 * as the call progress tones. Local feedback only: OpenCell sends no DTMF.
 */
object DtmfTones {
    private val ROWS = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
    private val COLS = doubleArrayOf(1209.0, 1336.0, 1477.0)
    private const val LAYOUT = "123456789*0#"

    /** How long one key press sounds, like the stock dialer. */
    const val KEY_MILLIS = 150

    /** Each of the two frequencies, a comfortable local level (signalling DTMF is about -7 dBm0). */
    const val LEVEL_DBM0 = -12.0

    /** Fade in and out, so a burst doesn't click. */
    const val FADE_MILLIS = 5

    /** The (row, column) frequencies of [key], or null if it isn't a keypad key. */
    fun freqs(key: Char): Pair<Double, Double>? {
        val i = LAYOUT.indexOf(key)
        if (i < 0) return null
        return ROWS[i / 3] to COLS[i % 3]
    }

    fun tone(key: Char, millis: Int = KEY_MILLIS): Tone? {
        val (row, col) = freqs(key) ?: return null
        return Tone(listOf(ToneSegment.on(millis, row, col)), repeat = false, levelDbm0 = LEVEL_DBM0)
    }

    /** One key's burst as 16-bit PCM at [sampleRate], faded in and out over [FADE_MILLIS]. */
    fun pcm(key: Char, sampleRate: Int, millis: Int = KEY_MILLIS): ShortArray? {
        val tone = tone(key, millis) ?: return null
        val out = ShortArray(millis * sampleRate / 1000)
        ToneGenerator(tone, sampleRate).fill(out)
        val fade = FADE_MILLIS * sampleRate / 1000
        for (i in 0 until fade) {
            val g = i.toDouble() / fade
            out[i] = (out[i] * g).toInt().toShort()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * g).toInt().toShort()
        }
        return out
    }
}
