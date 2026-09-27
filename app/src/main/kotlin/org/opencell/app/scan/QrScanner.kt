package org.opencell.app.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Feeds each camera frame's luminance (Y) plane to [QrDecoder] and reports a
 * code's text once while it stays in view (a repeated identical text, e.g. an
 * invalid code held in front of the camera, isn't re-reported every frame).
 */
class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var last: String? = null

    override fun analyze(image: ImageProxy) {
        // image.imageInfo.rotationDegrees is deliberately ignored: ZXing finds a QR code in any
        // orientation (the finder patterns fix it), so the unrotated Y plane decodes the same.
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            report(QrDecoder.decode(data, it.width, it.height, plane.rowStride))
        }
    }

    /** One frame's result: the code's text, or null if the frame had none. Called on the analysis thread only. */
    fun report(text: String?) {
        if (text == null || text == last) return
        last = text
        onText(text)
    }
}

/**
 * Back-camera viewfinder that reports each QR code it reads to [onText]
 * (on the main thread; once per code while it stays in view).
 * Needs the CAMERA permission before it is shown.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    DisposableEffect(lifecycleOwner) {
        val analysis = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(analysis, QrAnalyzer { text -> main.execute { latest(text) } })
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysis.shutdown()
        }
    }
    AndroidView(factory = { PreviewView(it).apply { this.controller = controller } }, modifier = modifier)
}
