package org.opencell.core.phone

/** A network test service the keypad offers and the call log names. */
data class ServiceNumber(val label: String, val number: String)

/**
 * The core test services (core-test-services spec): an echo (00100: your own
 * voice back about a second later) and a playback (00101: a recording) on
 * each core. Offered under the keypad's "Test numbers"; the call log shows
 * these labels instead of the bare numbers.
 */
object ServiceNumbers {
    val ALL = listOf(
        ServiceNumber("Echo test (core 1)", "+883160655500100"),
        ServiceNumber("Playback test (core 1)", "+883160655500101"),
        ServiceNumber("Echo test (core 2)", "+883150355500100"),
        ServiceNumber("Playback test (core 2)", "+883150355500101"),
    )

    /** The label for [number] (full form), or null if it isn't one of [ALL]. */
    fun label(number: String?): String? = ALL.firstOrNull { it.number == number }?.label
}
