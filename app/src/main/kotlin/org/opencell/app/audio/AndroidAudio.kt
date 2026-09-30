package org.opencell.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.opencell.core.voice.AudioIo
import org.opencell.core.voice.BlockCodec
import org.opencell.core.voice.MicInput
import org.opencell.core.voice.SpeakerOutput
import java.util.concurrent.Executors

/**
 * The phone's microphone and call audio output for the voice session (voice
 * spec §5.5): AudioRecord from VOICE_COMMUNICATION (the platform's echo
 * cancellation and noise suppression) and AudioTrack for voice communication,
 * both 8 kHz mono 16-bit, each on its own urgent-audio thread because their
 * reads and writes block. Opening the output starts [route]; closing it ends it.
 */
class AndroidAudio(private val context: Context, private val route: CallAudioRoute) : AudioIo {
    private val inThread = audioThread("oc-voice-in")
    private val outThread = audioThread("oc-voice-out")

    @SuppressLint("MissingPermission") // checked first
    override fun openMic(): MicInput? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(maxOf(min, 4 * BLOCK_BYTES))
                .build()
        } catch (e: Exception) { // SecurityException, UnsupportedOperationException (8 kHz refused)
            Log.w(TAG, "no microphone", e)
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return null
        }
        return object : MicInput {
            private var open = true

            override suspend fun read(block: ShortArray): Boolean = withContext(inThread) {
                var off = 0
                while (off < block.size) {
                    val n = rec.read(block, off, block.size - off)
                    if (n <= 0) return@withContext false
                    off += n
                }
                true
            }

            @Synchronized
            override fun close() {
                if (!open) return
                open = false
                rec.stop()
                rec.release()
            }
        }
    }

    override fun openSpeaker(): SpeakerOutput? {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(CallAudioRoute.ATTRIBUTES)
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                // One block: a blocking write returns once the block is in, leaving 120 ms queued.
                .setBufferSizeInBytes(maxOf(min, BLOCK_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "no audio output", e)
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        route.begin()
        track.play()
        return object : SpeakerOutput {
            private var open = true
            private val bytes = ByteArray(BLOCK_BYTES)

            override suspend fun write(block: ShortArray) = withContext(outThread) {
                for (i in block.indices) { // little-endian PCM
                    bytes[2 * i] = block[i].toByte()
                    bytes[2 * i + 1] = (block[i].toInt() shr 8).toByte()
                }
                writeBlock(bytes) { b, off, len -> track.write(b, off, len, AudioTrack.WRITE_BLOCKING) }
            }

            @Synchronized
            override fun close() {
                if (!open) return
                open = false
                track.pause()
                track.flush()
                track.release()
                route.end()
            }
        }
    }

    private fun format(channels: Int): AudioFormat = AudioFormat.Builder()
        .setSampleRate(RATE)
        .setChannelMask(channels)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    companion object {
        private const val TAG = "AndroidAudio"
        const val RATE = BlockCodec.SAMPLE_RATE
        const val BLOCK_BYTES = BlockCodec.SAMPLES * 2

        private fun audioThread(name: String) = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                r.run()
            }, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}

/**
 * Writes one whole block to [write] (partial writes are looped). An output that
 * refuses it (AudioTrack's ERROR_DEAD_OBJECT after an audio server restart, say)
 * still takes a block's time, so the playout loop keeps its 120 ms clock instead
 * of spinning for the rest of the call.
 */
internal suspend fun writeBlock(bytes: ByteArray, write: (ByteArray, Int, Int) -> Int) {
    var off = 0
    while (off < bytes.size) {
        val n = write(bytes, off, bytes.size - off)
        if (n <= 0) {
            delay(BlockCodec.BLOCK_MILLIS)
            return
        }
        off += n
    }
}
