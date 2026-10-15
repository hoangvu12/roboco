package sh.roboco.android.pairing

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * The QR decoder over CameraX's `YUV_420_888` analysis frames
 * (android-ui-parity #138): the Y plane (pixel stride is always 1) read as
 * a [PlanarYUVLuminanceSource] and decoded by a reused [QRCodeReader] with
 * `TRY_HARDER` + UTF-8 hints — tens of milliseconds per 720p frame, so it
 * runs (with the Rust parse) on the single `qr-analysis` thread that owns
 * this instance. Not thread-safe.
 *
 * `NotFoundException`, `ChecksumException` and `FormatException` all mean
 * "no code in this frame": [ReaderException] is their parent, caught here.
 * Inverted codes (light-on-dark) are not decoded — `QRCodeReader` ignores
 * `ALSO_INVERTED`, and android-app#32 renders black on white.
 */
class QrDecoder {

    private val reader = QRCodeReader()

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** Reused Y-plane buffer: one allocation once frames reach full size. */
    private var buf = ByteArray(0)

    /**
     * Copies `planes[0]` (a rewound duplicate — the image's own buffer
     * dies with `image.close()`) into the reused array, then [decode] with
     * `dataWidth = rowStride`, **not** `image.width`: a padded stride with
     * the width would shear the image and never decode.
     */
    fun analyze(image: ImageProxy): String? {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val length = src.remaining()
        if (buf.size < length) buf = ByteArray(length)
        src.get(buf, 0, length)
        return decode(buf, image.width, image.height, plane.rowStride)
    }

    /**
     * Decodes a Y plane: `y` holds `height` rows at [rowStride] stride
     * (only `width` bytes of the last row are required — the source never
     * reads past `width` on it). The **whole frame** is decoded, not just
     * the reticle area: mapping the reticle through FILL_CENTER's crop
     * depends on sensor rotation, and whole-frame decode is more forgiving.
     * `null` when the plane is too short, degenerate or carries no code.
     */
    fun decode(y: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (y.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
