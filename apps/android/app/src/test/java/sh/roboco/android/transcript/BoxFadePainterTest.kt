package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.BoxPrim
import uniffi.roboco_core.BoxStyle
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Fade
import uniffi.roboco_core.FadeEdge

/**
 * Ticket 64's pixel tests for [BoxPainter] and [FadePainter] (native
 * graphics so Skia really rasterises): the scaled fill rect and circular
 * corners, the 1-px inside hairline, emission-order z-order, and the two
 * alpha ramps with their clips — the exact pixel tables from the ticket's
 * Spec, tolerance ±3 alpha for gradient rasterisation and ±1 per channel
 * for colour.
 *
 * The fades draw an opaque stand-in rect (0xFF2050A0) instead of text, so
 * no font is involved; the last test goes through the real [RowModel.draw]
 * to re-check ticket 62's pass gating with the finished painter bodies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BoxFadePainterTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** The opaque stand-in "run" of the fade tests: plain pixels, no fonts. */
    private val standIn = Paint().apply { color = RUN_COLOR }

    @Before
    fun loadFonts() {
        Fonts.resetForTest()
        Fonts.load(context)
    }

    @After
    fun resetFonts() {
        Fonts.resetForTest()
    }

    private fun blank(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    private fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, style: BoxStyle): BoxPrim =
        BoxPrim(x, y, w, h, radius, style, ColorRole.CODE_BACKGROUND, null)

    private fun alpha(bitmap: Bitmap, x: Int, y: Int): Int = Color.alpha(bitmap.getPixel(x, y))

    /** ±3 alpha — gradient rasterisation (the eraser is anti-aliased). */
    private fun assertAlpha(expected: Int, bitmap: Bitmap, x: Int, y: Int) {
        val a = alpha(bitmap, x, y)
        assertTrue("alpha at ($x, $y): expected $expected ±3, was $a", kotlin.math.abs(a - expected) <= 3)
    }

    /** ±1 per channel, alpha included. */
    private fun assertColor(expected: Int, actual: Int, at: String) {
        assertTrue(
            "$at: expected #${Integer.toHexString(expected)}, was #${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.alpha(expected) - Color.alpha(actual)) <= 1 &&
                kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 1 &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 1 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 1,
        )
    }

    // ------------------------------------------------------------ boxes

    @Test
    fun fillPaintsTheScaledRectInRoleColour() {
        val (bitmap, canvas) = blank(42, 42)
        BoxPainter.draw(canvas, box(2f, 2f, 10f, 10f, 0f, BoxStyle.FILL), CODE_BACKGROUND, 3f)
        // (2,2,10,10)·3 → [6,36]²: interior corners fully coloured …
        assertColor(CODE_BACKGROUND, bitmap.getPixel(6, 6), "(6, 6)")
        assertColor(CODE_BACKGROUND, bitmap.getPixel(35, 35), "(35, 35)")
        // … one pixel outside on each side transparent.
        assertEquals(0, bitmap.getPixel(5, 6))
        assertEquals(0, bitmap.getPixel(36, 35))
    }

    @Test
    fun fillRoundsCornersWithCircularArcs() {
        val (bitmap, canvas) = blank(64, 64)
        // r 6 on (0,0,20,20) at d 3 → an 18 px arc on a 60 px rect.
        BoxPainter.draw(canvas, box(0f, 0f, 20f, 20f, 6f, BoxStyle.FILL), TEXT_COLOR, 3f)
        assertEquals("pixel (0, 0) sits outside the corner arc", 0, bitmap.getPixel(0, 0))
        assertColor(TEXT_COLOR, bitmap.getPixel(1, 30), "(1, 30)") // straight left edge
    }

    @Test
    fun hairlineIsAOnePxInsideStroke() {
        val (bitmap, canvas) = blank(42, 42)
        // (2,2,10,10)·3 = [6,36]², inset 0.5 → the stroke hugs [6,7] and
        // [35,36] on every side: pixel-aligned, so exact colours, no AA.
        BoxPainter.draw(canvas, box(2f, 2f, 10f, 10f, 0f, BoxStyle.HAIRLINE), TEXT_COLOR, 3f)
        for (y in 6..35) {
            assertEquals("column 6, row $y", TEXT_COLOR, bitmap.getPixel(6, y))
            assertEquals("column 35, row $y", TEXT_COLOR, bitmap.getPixel(35, y))
        }
        // Columns 5, 7, 34 and 36 (rows clear of the top and bottom strokes)
        // and the whole interior stay transparent.
        for (y in 10..30) {
            assertEquals("column 5, row $y", 0, bitmap.getPixel(5, y))
            assertEquals("column 7, row $y", 0, bitmap.getPixel(7, y))
            assertEquals("column 34, row $y", 0, bitmap.getPixel(34, y))
            assertEquals("column 36, row $y", 0, bitmap.getPixel(36, y))
        }
        for (x in 9..32) {
            for (y in 9..32) assertEquals("interior ($x, $y)", 0, bitmap.getPixel(x, y))
        }
    }

    @Test
    fun boxesPaintInListOrderSoALaterBoxCoversAnEarlierOne() {
        val (bitmap, canvas) = blank(48, 48)
        BoxPainter.draw(canvas, box(0f, 0f, 20f, 20f, 0f, BoxStyle.FILL), RED, 1f)
        BoxPainter.draw(canvas, box(10f, 10f, 20f, 20f, 0f, BoxStyle.FILL), BLUE, 1f)
        assertEquals(RED, bitmap.getPixel(5, 5)) // first box only
        assertEquals(BLUE, bitmap.getPixel(15, 15)) // the overlap: later wins
        assertEquals(BLUE, bitmap.getPixel(25, 25)) // second box only
    }

    // ------------------------------------------------------------ fades

    @Test
    fun trailingFadeErasesAlongTheRampAndClipsAtTheRightEdge() {
        // f = (10, 0, 28, 20) at d 1; the "run" covers x ∈ [0, 60].
        val (bitmap, canvas) = blank(64, 32)
        FadePainter.draw(canvas, Fade(10f, 0f, 28f, 20f, FadeEdge.TRAILING, null), 1f) {
            canvas.drawRect(0f, 0f, 60f, 20f, standIn)
        }
        assertAlpha(255, bitmap, 5, 10) // left of the ramp: untouched
        assertAlpha(250, bitmap, 10, 10) // 255 × (1 − 0.5/28)
        assertAlpha(123, bitmap, 24, 10) // 255 × (1 − 14.5/28)
        assertAlpha(5, bitmap, 37, 10) // 255 × (1 − 27.5/28)
        // The outer clip cuts at f.x + f.w = 38: nothing right of it.
        for (x in 38..63) assertAlpha(0, bitmap, x, 10)
    }

    @Test
    fun bottomFadeErasesDownwardAndKeepsEightPercentBelowTheRect() {
        // f = (0, 10, 40, 20) at d 1; the "run" covers x ∈ [0, 40], y ∈ [0, 60].
        val (bitmap, canvas) = blank(48, 64)
        FadePainter.draw(canvas, Fade(0f, 10f, 40f, 20f, FadeEdge.BOTTOM, null), 1f) {
            canvas.drawRect(0f, 0f, 40f, 60f, standIn)
        }
        assertAlpha(255, bitmap, 20, 5) // above the fade: untouched
        assertAlpha(144, bitmap, 20, 19) // 255 × (1 − 0.92 × 9.5/20)
        assertAlpha(20, bitmap, 20, 35) // 255 × 0.08: the ramp's floor
        assertAlpha(20, bitmap, 20, 55) // CLAMP keeps 0.92 erasing to y1 + 40
    }

    @Test
    fun theEraseStaysInsideTheLayerSoAnEarlierBackdropSurvives() {
        // A DST_OUT straight on the row canvas would punch holes through
        // the red; the saveLayer keeps the erase off the backdrop.
        val (bitmap, canvas) = blank(64, 32)
        bitmap.eraseColor(RED)
        FadePainter.draw(canvas, Fade(10f, 0f, 28f, 20f, FadeEdge.TRAILING, null), 1f) {
            canvas.drawRect(0f, 0f, 60f, 20f, standIn)
        }
        for (y in 0 until 32) {
            for (x in 0 until 64) {
                assertEquals("backdrop at ($x, $y)", 255, alpha(bitmap, x, y))
            }
        }
    }

    // ------------------------------------------- RowModel pass regression

    @Test
    fun rangeDrawsNothingWhileChromePaintsTheBoxAndTheFadedRun() {
        val fonts = StyleFonts().apply { update(RowFixtures.styles) }
        // One opaque box, one run ("T" of the fixture text) masked by the
        // trailing fade: exactly the gating ticket 62 owns and this ticket
        // must not break.
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(RowFixtures.run(0u, 1u, 2f, 20f)),
                boxes = listOf(RowFixtures.box(0f, 0f, 30f, 8f, 0f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND)),
                fades = listOf(RowFixtures.fade(2f, 5f, 40f, 18f, FadeEdge.TRAILING)),
            ),
        )

        // Range is text-only: no boxes, no fades, and the faded run is
        // skipped in the plain loop — the whole bitmap stays transparent.
        val (range, rangeCanvas) = blank(48, 32)
        model.draw(0, rangeCanvas, 1f, TranscriptPalette(false), fonts, RowModel.Pass.Range(0, Int.MAX_VALUE))
        for (y in 0 until 32) {
            for (x in 0 until 48) assertEquals("range at ($x, $y)", 0, range.getPixel(x, y))
        }

        // Chrome paints the box and the fade with its masked run (the only
        // run): the box at its exact role colour, glyph pixels below it.
        val (chrome, chromeCanvas) = blank(48, 32)
        model.draw(0, chromeCanvas, 1f, TranscriptPalette(false), fonts, RowModel.Pass.Chrome)
        assertColor(CODE_BACKGROUND, chrome.getPixel(5, 4), "box (5, 4)")
        val painted = (9 until 22).any { y -> (0 until 40).any { x -> alpha(chrome, x, y) > 0 } }
        assertTrue("the faded run painted below the box", painted)
    }

    private companion object {
        /** Ticket 02's light values (the painter only ever sees the resolved ARGB). */
        val CODE_BACKGROUND = 0xFFFAFAFB.toInt()
        val TEXT_COLOR = 0xFF27272C.toInt()
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val RUN_COLOR = 0xFF2050A0.toInt()
    }
}
