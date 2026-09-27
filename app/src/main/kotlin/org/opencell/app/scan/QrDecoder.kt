package org.opencell.app.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in an 8-bit luminance image, such as the Y plane of a
 * camera frame. Pure ZXing (no Android), so it is unit-tested on the JVM.
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * @param luminance one byte per pixel, rows [rowStride] bytes apart (camera
     *   planes often pad rows beyond [width]).
     * @return the code's text, or null if no QR code was found.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        }
    }
}
