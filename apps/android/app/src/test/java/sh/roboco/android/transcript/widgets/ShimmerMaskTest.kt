package sh.roboco.android.transcript.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The DST_IN mask's own pixels (ticket 122): a 1000 × 4 opaque-black
 * bitmap with the mask rect applied, read back through real Skia
 * (Robolectric NATIVE graphics). W = 1000 px, so the numbers are the
 * ticket's: at t = 1/3 the k = 1 peak sits at x 500 with its 0.5-alpha
 * shoulders at x 320 and 680; at t = 0 every column is transparent; at
 * t = 0.25 the k = 1 peak touches x 0 and the band ends by x 360.
 *
 * The runner is the repo's Robolectric convention
 * (`RobolectricTestRunner`); the ticket's `AndroidJUnit4` note maps onto
 * it (the ext-junit runner is an androidTest-only dependency here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShimmerMaskTest {

    /** The 1000 × 4 opaque-black bitmap with the mask applied at phase [t]. */
    private fun masked(t: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt())
        Canvas(bitmap).drawRect(0f, 0f, 1000f, 4f, ShimmerMask().update(1000f, t))
        return bitmap
    }

    /** The mask's alpha at column x (row 2, mid-height). */
    private fun alphaAt(bitmap: Bitmap, x: Int): Int = Color.alpha(bitmap.getPixel(x, 2))

    @Test
    fun atOneThirdTheBandSitsOnTheTitleCentre() {
        val mask = masked(1f / 3f)
        assertEquals(255f, alphaAt(mask, 500).toFloat(), 2f)
        assertEquals(128f, alphaAt(mask, 320).toFloat(), 3f)
        assertEquals(128f, alphaAt(mask, 680).toFloat(), 3f)
        // Outside the shoulders: fully transparent.
        for (x in intArrayOf(0, 70, 140, 860, 930, 999)) {
            assertEquals("alpha at x $x", 0f, alphaAt(mask, x).toFloat(), 0f)
        }
    }

    @Test
    fun atZeroNothingIsMasked() {
        val mask = masked(0f)
        for (x in 0..999 step 20) {
            assertEquals("alpha at x $x", 0f, alphaAt(mask, x).toFloat(), 0f)
        }
        // The CLAMP edges are the transparent stop colours, not black.
        assertEquals(0f, alphaAt(mask, 0).toFloat(), 0f)
        assertEquals(0f, alphaAt(mask, 999).toFloat(), 0f)
    }

    @Test
    fun atAQuarterThePeakTouchesTheLeftEdge() {
        val mask = masked(0.25f)
        assertEquals(255f, alphaAt(mask, 0).toFloat(), 2f)
        // The k = 1 band is spent by x 360; nothing else reaches the title.
        for (x in intArrayOf(360, 500, 700, 999)) {
            assertEquals("alpha at x $x", 0f, alphaAt(mask, x).toFloat(), 0f)
        }
    }

    @Test
    fun theShaderTravelsWithThePhase() {
        // The same mask instance re-shaded from t = 0 to t = 1/3: the
        // peak moves from x 1500 (off the bitmap, invisible) to x 500 —
        // the local-matrix translate, not a rebuilt shader.
        val mask = ShimmerMask()
        val bitmap = Bitmap.createBitmap(1000, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt())
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 1000f, 4f, mask.update(1000f, 0f))
        assertEquals(0, alphaAt(bitmap, 500))
        // DST_IN clears the destination where the mask is transparent,
        // so re-prime the black bitmap before the second pass.
        bitmap.eraseColor(0xFF000000.toInt())
        canvas.drawRect(0f, 0f, 1000f, 4f, mask.update(1000f, 1f / 3f))
        assertEquals(255, alphaAt(bitmap, 500))
    }
}
