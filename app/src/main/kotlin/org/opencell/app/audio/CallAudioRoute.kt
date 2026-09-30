package org.opencell.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the call's audio goes. */
enum class RouteKind(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    WIRED("Headset"),
    BLUETOOTH("Bluetooth"),
}

/**
 * The route for a call (voice spec §5.6), as a pure function: the speaker when
 * the user turned it on, otherwise a Bluetooth headset, a wired headset, or the
 * earpiece, in that order; the speaker if nothing else is there.
 */
fun pickRoute(available: Set<RouteKind>, speaker: Boolean): RouteKind? = when {
    speaker && RouteKind.SPEAKER in available -> RouteKind.SPEAKER
    RouteKind.BLUETOOTH in available -> RouteKind.BLUETOOTH
    RouteKind.WIRED in available -> RouteKind.WIRED
    RouteKind.EARPIECE in available -> RouteKind.EARPIECE
    RouteKind.SPEAKER in available -> RouteKind.SPEAKER
    else -> null
}

/**
 * The call's audio mode, focus and output device, without Android Telecom
 * (voice spec D7 ruling): [begin] when an audio output for the call opens
 * (a progress tone's or voice's), [end] when it closes. Counted: the ringback's
 * output can close just after voice's opened, and only the last [end] puts
 * things back. The device follows [pickRoute] through `setCommunicationDevice`
 * (API 31+), again whenever a headset comes or goes.
 */
class CallAudioRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    private val active get() = users > 0

    private val _speaker = MutableStateFlow(false)

    /** The user's speaker toggle; it goes back to off when the call's audio ends. */
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    private val _route = MutableStateFlow<RouteKind?>(null)

    /** Where audio goes now; null outside a call. */
    val route: StateFlow<RouteKind?> = _route.asStateFlow()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ATTRIBUTES)
        .build()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = apply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = apply()
    }

    @Synchronized
    fun begin() {
        if (users++ > 0) return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.requestAudioFocus(focus)
        am.registerAudioDeviceCallback(devices, main)
        apply()
    }

    @Synchronized
    fun setSpeaker(on: Boolean) {
        _speaker.value = on
        apply()
    }

    @Synchronized
    fun end() {
        if (users == 0 || --users > 0) return
        am.unregisterAudioDeviceCallback(devices)
        am.clearCommunicationDevice()
        am.abandonAudioFocusRequest(focus)
        am.mode = AudioManager.MODE_NORMAL
        _speaker.value = false
        _route.value = null
    }

    @Synchronized
    private fun apply() {
        if (!active) return
        val available = am.availableCommunicationDevices
        val pick = pickRoute(available.mapNotNull { kindOf(it) }.toSet(), _speaker.value)
        available.firstOrNull { kindOf(it) == pick }?.let { am.setCommunicationDevice(it) }
        _route.value = pick
    }

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun kindOf(d: AudioDeviceInfo): RouteKind? = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> RouteKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> RouteKind.BLUETOOTH
            else -> null
        }
    }
}
