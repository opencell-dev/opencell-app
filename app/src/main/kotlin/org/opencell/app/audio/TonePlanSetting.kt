package org.opencell.app.audio

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencell.core.voice.TonePlan
import org.opencell.core.voice.TonePlans
import java.util.Locale

/**
 * Which call progress tones the phone plays (voice spec §6.4): automatic (the
 * UK plan for a phone set to the United Kingdom, North American otherwise), or
 * one plan by its id. Kept in the app's preferences; set from the Phone tab's menu.
 */
class TonePlanSetting(private val prefs: SharedPreferences, private val locale: () -> Locale = Locale::getDefault) {
    private val _choice = MutableStateFlow(prefs.getString(KEY, AUTO) ?: AUTO)

    /** [AUTO] or a [TonePlan.id]. */
    val choice: StateFlow<String> = _choice.asStateFlow()

    private val _plan = MutableStateFlow(resolve(_choice.value))
    val plan: StateFlow<TonePlan> = _plan.asStateFlow()

    /** What [AUTO] picks on this phone. */
    val automatic: TonePlan get() = TonePlans.forLocale(locale())

    fun set(choice: String) {
        prefs.edit { putString(KEY, choice) }
        _choice.value = choice
        _plan.value = resolve(choice)
    }

    private fun resolve(choice: String): TonePlan = TonePlans.byId(choice) ?: automatic

    companion object {
        const val AUTO = "auto"
        private const val KEY = "tone_plan"
    }
}
