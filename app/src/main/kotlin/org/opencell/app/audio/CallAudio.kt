package org.opencell.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencell.core.voice.VoiceSession

/**
 * What the call screen needs for audio: the voice session (mute, what is
 * running), the route (speaker), whether RECORD_AUDIO is granted, and whether
 * the microphone may be used now ([micAllowed]: set by
 * [org.opencell.app.service.LinkService] while it holds the microphone type).
 */
class CallAudio(
    val voice: VoiceSession,
    val route: CallAudioRoute,
    val micGranted: MutableStateFlow<Boolean>,
    val micAllowed: MutableStateFlow<Boolean>,
) {
    /** Reads the permission again (on resume, and after the system dialog). */
    fun refreshPermission(context: Context) {
        micGranted.value = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}
