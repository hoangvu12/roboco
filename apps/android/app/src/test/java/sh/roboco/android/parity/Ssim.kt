package sh.roboco.android.parity

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Windowed SSIM over luminance (android-app#30's G4) in plain Kotlin, no
 * dependencies: 8×8 non-overlapping box windows and the standard SSIM
 * constants (K1 = 0.01, K2 = 0.03, L = 255) — the ratchet's floors must be
 * computed with *this* code, so the algorithm is frozen here: change it
 * and every floor in the manifest has to be re-measured (a manifest note
 * per the G4 rule).
 */
internal object Ssim {

    private const val K1 = 0.01
    private const val K2 = 0.03
    private const val DYNAMIC_RANGE = 255.0
    private const val WINDOW = 8

    private val C1 = (K1 * DYNAMIC_RANGE) * (K1 * DYNAMIC_RANGE)
    private val C2 = (K2 * DYNAMIC_RANGE) * (K2 * DYNAMIC_RANGE)

    /** One image as luminance samples, 0…255. */
    class Luminance(val width: Int, val height: Int, val values: FloatArray) {
        operator fun get(x: Int, y: Int): Float = values[y * width + x]
    }

    /** Rec. 709 luminance of the RGB image (alpha ignored). */
    fun luminance(image: BufferedImage): Luminance {
        val w = image.width
        val h = image.height
        val values = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16 and 0xFF).toFloat()
                val g = (rgb shr 8 and 0xFF).toFloat()
                val b = (rgb and 0xFF).toFloat()
                values[y * w + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return Luminance(w, h, values)
    }

    /**
     * The mean windowed SSIM between two equally-sized images. Windows
     * that fall off the edges are dropped, not wrapped.
     */
    fun score(a: Luminance, b: Luminance): Double {
        check(a.width == b.width && a.height == b.height) {
            "SSIM inputs must be the same size (${a.width}×${a.height} vs ${b.width}×${b.height})"
        }
        var total = 0.0
        var windows = 0
        val maxX = a.width - WINDOW
        val maxY = a.height - WINDOW
        var wy = 0
        while (wy <= maxY) {
            var wx = 0
            while (wx <= maxX) {
                total += windowScore(a, b, wx, wy)
                windows++
                wx += WINDOW
            }
            wy += WINDOW
        }
        check(windows > 0) { "SSIM image smaller than one window (${a.width}×${a.height})" }
        return total / windows
    }

    /** SSIM of one 8×8 window: the standard formula, population variance. */
    private fun windowScore(a: Luminance, b: Luminance, x0: Int, y0: Int): Double {
        var sumX = 0.0
        var sumY = 0.0
        var sumXx = 0.0
        var sumYy = 0.0
        var sumXy = 0.0
        val n = (WINDOW * WINDOW).toDouble()
        for (y in 0 until WINDOW) {
            for (x in 0 until WINDOW) {
                val px = a[x0 + x, y0 + y].toDouble()
                val py = b[x0 + x, y0 + y].toDouble()
                sumX += px
                sumY += py
                sumXx += px * px
                sumYy += py * py
                sumXy += px * py
            }
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val varX = sumXx / n - meanX * meanX
        val varY = sumYy / n - meanY * meanY
        val covXY = sumXy / n - meanX * meanY
        val numerator = (2 * meanX * meanY + C1) * (2 * covXY + C2)
        val denominator = (meanX * meanX + meanY * meanY + C1) * (varX + varY + C2)
        return numerator / denominator
    }

    // ── normalization (the G4 contract: both sides at 393 × 764) ────────

    /**
     * The Android golden, normalized: cropped like the iOS capture (top
     * 54 dp of status bar, bottom 34 dp of home-indicator area) and scaled
     * to 393 px wide, then clipped to 393 × 764.
     *
     * [pxPerDp] is the golden's pixel density (3 for the xxhdpi phone
     * goldens; pass the Roborazzi device's density).
     */
    fun golden393(file: File, pxPerDp: Float): BufferedImage =
        normalize(read(file), topPx = 54f * pxPerDp, bottomPx = 34f * pxPerDp)

    /**
     * The iOS reference, normalized: cropped (its own status bar 0–54 pt
     * and home indicator bottom 34 pt, proportional to its height) and
     * scaled by 393 / 460 to 393 px wide, then clipped to 393 × 764.
     */
    fun ios393(file: File): BufferedImage {
        val image = read(file)
        // The references are full-device captures; 54/34 pt of an 874 pt
        // canvas, proportional, handles both the 460×1000 PR shots and
        // the 1206×2622 (393×852 pt @3x) pre-rewrite ones.
        val top = 54f * image.height / 874f
        val bottom = 34f * image.height / 874f
        return normalize(image, top, bottom)
    }

    /** Crop, scale to 393 wide, clip to 764 tall. */
    private fun normalize(image: BufferedImage, topPx: Float, bottomPx: Float): BufferedImage {
        val top = topPx.toInt().coerceIn(0, image.height / 2)
        val bottom = bottomPx.toInt().coerceIn(0, image.height / 2)
        val cropped = image.getSubimage(0, top, image.width, image.height - top - bottom)
        // Exactly 393 px wide. `width * (393f / width)` truncated to 392 for
        // the 1206 px (402 dp @3x) goldens (1206 × 0.32587 = 392.99997), and
        // the 393-wide getSubimage below then threw RasterFormatException
        // (verify run 38622776189). The height scales by the same ratio.
        val height = max(1, (cropped.height.toDouble() * 393.0 / cropped.width).roundToInt())
        val scaled = scale(cropped, 393, height)
        return scaled.getSubimage(0, 0, 393, min(scaled.height, 764))
    }

    private fun scale(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.drawImage(source, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun read(file: File): BufferedImage =
        checkNotNull(ImageIO.read(file)) { "could not read ${file.path}" }
}
