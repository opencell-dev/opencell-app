package org.opencell.app.audio

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(AndroidJUnit4::class)
class AndroidAudioTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val audio get() = AndroidAudio(app, CallAudioRoute(app))

    @After
    fun tearDown() = ShadowAudioRecord.clearSource()

    @Test
    fun noMicrophoneWithoutThePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertNull(audio.openMic())
    }

    @Test
    fun theMicrophoneReadsWhole120MsBlocks() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            // Hands out at most 100 samples per read, like a device mid-buffer.
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                val n = minOf(sizeInShorts, 100)
                for (i in 0 until n) audioData[offsetInShorts + i] = 7
                return n
            }
        })
        val mic = checkNotNull(audio.openMic())
        val block = ShortArray(960)
        assertTrue(mic.read(block))
        assertTrue(block.all { it == 7.toShort() })
        mic.close()
        mic.close() // idempotent
    }

    @Test
    fun aMicrophoneThatStopsDeliveringEndsTheRead() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean) = -3 // ERROR_INVALID_OPERATION
        })
        val mic = checkNotNull(audio.openMic())
        assertEquals(false, mic.read(ShortArray(960)))
        mic.close()
    }
}
