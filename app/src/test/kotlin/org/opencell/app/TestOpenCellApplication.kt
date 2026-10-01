package org.opencell.app

import org.opencell.app.audio.KeySound
import org.opencell.core.voice.AudioIo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The application under Robolectric (robolectric.properties): the real graph
 * with silent audio devices. Robolectric's AudioTrack doesn't block on write,
 * so the real output would spin; the real codec still runs. Keypad tones are
 * recorded instead of played ([RecordingKeySound]).
 */
class TestOpenCellApplication : OpenCellApplication() {
    override fun makeGraph(): AppGraph = AppGraph(this, { _, _ -> AudioIo.NONE }, { RecordingKeySound() })
}

/** The keys whose tones would have played, in order. */
class RecordingKeySound : KeySound {
    val played = CopyOnWriteArrayList<Char>()

    override fun play(key: Char) {
        played += key
    }
}
