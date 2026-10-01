package org.opencell.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import org.opencell.core.voice.DtmfTones
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The keypad's DTMF key tones ([DtmfTones]) as short static AudioTracks with
 * sonification attributes: they never touch the call audio mode or route.
 * A new key cuts the previous tone. Silent and vibrate modes mute them, like
 * the stock dialer. Everything runs on one background thread; a tone that
 * can't play is skipped.
 */
class KeyTonePlayer(context: Context) : KeySound {
    private val am = context.getSystemService(AudioManager::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oc-key-tones").apply { isDaemon = true } }
    private val pcm = HashMap<Char, ShortArray>()
    private var current: AudioTrack? = null

    override fun play(key: Char) {
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        executor.execute {
            try {
                start(key)
            } catch (e: Exception) {
                // Never the key: the keys typed would spell the number out in the system log.
                Log.w(TAG, "a key tone failed", e)
            }
        }
    }

    private fun start(key: Char) {
        val samples = pcm.getOrPut(key) { DtmfTones.pcm(key, RATE) ?: return }
        current?.release()
        current = null
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        current = track
        executor.schedule({
            if (current === track) {
                track.release()
                current = null
            }
        }, DtmfTones.KEY_MILLIS + 100L, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val TAG = "KeyTonePlayer"
        const val RATE = 16_000
    }
}
