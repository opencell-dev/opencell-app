package org.opencell.app

import org.opencell.core.voice.AudioIo

/**
 * The application under Robolectric (robolectric.properties): the real graph
 * with silent audio devices. Robolectric's AudioTrack doesn't block on write,
 * so the real output would spin; the real codec still runs.
 */
class TestOpenCellApplication : OpenCellApplication() {
    override fun makeGraph(): AppGraph = AppGraph(this) { _, _ -> AudioIo.NONE }
}
