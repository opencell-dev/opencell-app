package org.opencell.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.opencell.app.audio.RouteKind.BLUETOOTH
import org.opencell.app.audio.RouteKind.EARPIECE
import org.opencell.app.audio.RouteKind.SPEAKER
import org.opencell.app.audio.RouteKind.WIRED
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(AndroidJUnit4::class)
class RouteTest {
    @Test
    fun theSpeakerWhenAskedOtherwiseAHeadsetOtherwiseTheEarpiece() {
        val all = setOf(EARPIECE, SPEAKER, WIRED, BLUETOOTH)
        assertEquals(SPEAKER, pickRoute(all, speaker = true))
        assertEquals(BLUETOOTH, pickRoute(all, speaker = false))
        assertEquals(WIRED, pickRoute(setOf(EARPIECE, SPEAKER, WIRED), speaker = false))
        assertEquals(EARPIECE, pickRoute(setOf(EARPIECE, SPEAKER), speaker = false))
        assertEquals(SPEAKER, pickRoute(setOf(SPEAKER), speaker = false)) // a tablet has no earpiece
        assertEquals(EARPIECE, pickRoute(setOf(EARPIECE), speaker = true))
        assertNull(pickRoute(emptySet(), speaker = false))
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val am get() = context.getSystemService(AudioManager::class.java)
    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Test
    fun aCallSetsTheModeAndTheDeviceAndPutsThemBack() {
        val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val route = CallAudioRoute(context)

        route.begin()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)
        assertEquals(earpiece, am.communicationDevice)
        assertEquals(EARPIECE, route.route.value)

        route.setSpeaker(true)
        assertEquals(speaker, am.communicationDevice)
        assertEquals(SPEAKER, route.route.value)

        route.end()
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
        assertFalse(route.speaker.value) // the next call starts on the earpiece
        assertNull(route.route.value)
    }

    @Test
    fun overlappingOutputsKeepTheCallModeUntilTheLastCloses() {
        shadowOf(am).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        val route = CallAudioRoute(context)
        route.begin() // the ringback
        route.begin() // voice, as the call connects
        route.end() // the ringback closes
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)
        assertEquals(EARPIECE, route.route.value)
        route.end()
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
        route.end() // one too many: ignored
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
    }

    @Test
    fun aBluetoothHeadsetIsPreferred() {
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(am).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), headset))
        val route = CallAudioRoute(context)
        route.begin()
        assertEquals(headset, am.communicationDevice)
        assertEquals(BLUETOOTH, route.route.value)
        route.end()
    }
}
