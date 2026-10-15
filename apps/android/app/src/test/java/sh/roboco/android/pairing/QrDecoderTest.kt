package sh.roboco.android.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner's decode pipeline (android-ui-parity #138, plain JVM): a QR
 * built with ZXing's [QRCodeWriter] (version 5 — the payload is 89 bytes,
 * past version 4-L's 78 and within version 5-L's 106) rendered 400 × 400
 * and placed in a **padded** 640 × 480 Y plane with `rowStride = 704` and
 * a short last row (the plane is exactly `rowStride × (height − 1) +
 * width` bytes, like CameraX's real buffers) decodes to the exact input;
 * a blank plane and a too-short/narrow-stride plane decode nothing.
 *
 * Set bits are `0x00` and the background `0xFF`, offset by
 * `row * rowStride` — the real `ImageAnalysis` Y-plane layout the
 * activity's `QrDecoder.analyze` feeds `decode`. The version check uses
 * the writer's module-count behaviour (a tiny requested size comes back
 * as `modules + 2 × 4` quiet zone): version 5 is 37 + 8 = 45.
 */
class QrDecoderTest {

    private val text =
        "https://engine.example.ts.net:27655/pair#token=AbC-123_XyZ0987654321AbC-123_XyZ0987654321"

    private val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")

    @Test
    fun aPaddedPlaneWithAShortLastRowDecodesTheExactInput() {
        // Version ≥ 5, as the acceptance table pins: a requested size
        // below the code's own width comes back as the module count plus
        // the 4-module quiet zone — version 5 is 37 + 8 = 45.
        assertEquals(45, QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 10, 10, hints).width)

        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400, hints)
        // rowStride 704 > width 640: 64 bytes of padding per row; the last
        // row only needs `width` bytes.
        val plane = ByteArray(ROW_STRIDE * (PLANE_HEIGHT - 1) + PLANE_WIDTH) { 0xFF.toByte() }
        // The 400 × 400 code, centred in the 640 × 480 plane.
        for (y in 0 until 400) {
            for (x in 0 until 400) {
                if (matrix.get(x, y)) {
                    plane[(40 + y) * ROW_STRIDE + (120 + x)] = 0x00
                }
            }
        }
        assertEquals(text, QrDecoder().decode(plane, PLANE_WIDTH, PLANE_HEIGHT, ROW_STRIDE))
    }

    @Test
    fun aBlankPlaneDecodesNothing() {
        val plane = ByteArray(ROW_STRIDE * (PLANE_HEIGHT - 1) + PLANE_WIDTH) { 0xFF.toByte() }
        assertNull(QrDecoder().decode(plane, PLANE_WIDTH, PLANE_HEIGHT, ROW_STRIDE))
    }

    @Test
    fun aTooShortPlaneIsRejectedWithoutThrowing() {
        // The last row would be read past the buffer.
        assertNull(QrDecoder().decode(ByteArray(10), PLANE_WIDTH, PLANE_HEIGHT, ROW_STRIDE))
    }

    @Test
    fun aStrideNarrowerThanTheWidthIsRejected() {
        // A stride below the width is a malformed plane; the
        // PlanarYUVLuminanceSource crop check would throw, so decode
        // refuses it up front.
        assertNull(
            QrDecoder().decode(
                ByteArray(PLANE_WIDTH * PLANE_HEIGHT),
                PLANE_WIDTH,
                PLANE_HEIGHT,
                PLANE_WIDTH - 1,
            ),
        )
    }

    private companion object {
        const val PLANE_WIDTH = 640
        const val PLANE_HEIGHT = 480
        const val ROW_STRIDE = 704
    }
}
