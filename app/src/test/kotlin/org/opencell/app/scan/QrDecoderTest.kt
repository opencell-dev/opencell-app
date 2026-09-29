package org.opencell.app.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** Plain JVM (no Robolectric): renders a QR code like `ocbench mkqr` prints and reads it back. */
class QrDecoderTest {
    private val golden = "opencell:2:AgEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyCgoaKjpKWmp7CxsrO0tba3uLm6u7y9vr-" +
        "IMWBlVQEjT3hWNBIAAD44"

    /** A camera-like frame: the code drawn [scale] px per module on a grey background, rows [stride] apart. */
    private fun frame(text: String, scale: Int, stride: Int, width: Int, height: Int): ByteArray {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
        )
        val out = ByteArray(stride * height) { 0xB0.toByte() }
        val x0 = (width - m.width * scale) / 2
        val y0 = (height - m.height * scale) / 2
        for (y in 0 until m.height * scale) {
            for (x in 0 until m.width * scale) {
                out[(y0 + y) * stride + x0 + x] = if (m.get(x / scale, y / scale)) 0x20 else 0xF0.toByte()
            }
        }
        return out
    }

    @Test
    fun readsTheActivationCode() {
        assertEquals(golden, QrDecoder.decode(frame(golden, 6, 640, 640, 480), 640, 480))
    }

    @Test
    fun honoursPaddedRows() {
        assertEquals(golden, QrDecoder.decode(frame(golden, 5, 704, 640, 480), 640, 480, rowStride = 704))
    }

    @Test
    fun noCodeGivesNull() {
        val noise = Random(7).nextBytes(320 * 240)
        assertNull(QrDecoder.decode(noise, 320, 240))
    }
}
