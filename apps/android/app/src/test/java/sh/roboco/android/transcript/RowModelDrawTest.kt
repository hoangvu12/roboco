package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.Scroller

/**
 * [RowModel.draw]'s orchestration over a recording [Canvas] (native
 * graphics so text really draws): the boxes → runs → fades order per
 * layer, the pass semantics, the Range glyph-edge clip, and the guards.
 * Only the `box` and `text:*` events are compared as lists — `clip:*`
 * events are asserted in the `Range(4, 10)` case only, because ticket
 * 64's trailing fade adds its own clips. These expectations hold for the
 * seed painters and must still hold after tickets 63/64 land (the fixture
 * has no decorations and no wide characters).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RowModelDrawTest {

    private val density = 2.625f

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun loadFonts() {
        Fonts.resetForTest()
        Fonts.load(context)
    }

    @After
    fun resetFonts() {
        Fonts.resetForTest()
    }

    private fun newFonts(): StyleFonts = StyleFonts().apply { update(RowFixtures.styles) }

    private fun newCanvas(): RecordingCanvas =
        RecordingCanvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))

    /** Draws the fixture row on a fresh canvas; returns (canvas, fonts) for paint inspection. */
    private fun drawFixture(layer: Int, pass: RowModel.Pass): Pair<RecordingCanvas, StyleFonts> {
        val canvas = newCanvas()
        val fonts = newFonts()
        RowModel(RowFixtures.row()).draw(layer, canvas, density, TranscriptPalette(false), fonts, pass)
        return canvas to fonts
    }

    /** Only the box and text events (clip:* is per-case; ticket 64 adds fade clips). */
    private fun List<String>.paintEvents(): List<String> =
        filter { it == "box" || it.startsWith("text:") }

    // ------------------------------------------------ pass draw order

    @Test
    fun allOnLayerZeroPaintsBoxesThenPlainRunsThenFades() {
        val (canvas, _) = drawFixture(0, RowModel.Pass.All)
        // Boxes first, then the plain run, then the faded run via the fade.
        assertEquals(listOf("box", "box", "text:0", "text:40"), canvas.ops.paintEvents())
    }

    @Test
    fun allOnLayerOnePaintsOnlyTheScrollerRun() {
        val (canvas, _) = drawFixture(1, RowModel.Pass.All)
        assertEquals(listOf("text:19"), canvas.ops.paintEvents())
    }

    @Test
    fun chromeOnLayerZeroPaintsBoxesAndFadesButNoPlainRuns() {
        val (canvas, _) = drawFixture(0, RowModel.Pass.Chrome)
        assertEquals(listOf("box", "box", "text:40"), canvas.ops.paintEvents())
    }

    @Test
    fun rangeOverEverythingPaintsOnlyPlainUnfadedRuns() {
        // No boxes, no fades (so no faded run), no clip: nothing straddles.
        val (canvas, _) = drawFixture(0, RowModel.Pass.Range(0, Int.MAX_VALUE))
        assertEquals(listOf("text:0"), canvas.ops.paintEvents())
        assertTrue(canvas.ops.none { it.startsWith("clip:") })
    }

    @Test
    fun rangeInsideRunZeroClipsAtTheGlyphEdge() {
        val (canvas, fonts) = drawFixture(0, RowModel.Pass.Range(4, 10))
        val ops = canvas.ops
        assertEquals(listOf("text:0"), ops.paintEvents())

        // text:0 is immediately preceded by exactly one clip, whose edges
        // are the run-local advances of UTF-16 indices 4 and 10 — the same
        // computation the painter made (same paint, same text, same run).
        val textIndex = ops.indexOf("text:0")
        assertTrue("text:0 missing from $ops", textIndex > 0)
        val clip = ops[textIndex - 1]
        assertTrue("expected a clip before text:0, got $clip", clip.startsWith("clip:"))
        assertEquals("exactly one clip expected", 1, ops.count { it.startsWith("clip:") })

        val parts = clip.split(':')
        assertEquals(3, parts.size)
        val x = 16f * density
        val paint = fonts[0u]!!.paint
        val expectedLeft = x + paint.getRunAdvance(RowFixtures.T, 0, 19, 0, 19, false, 4)
        val expectedRight = x + paint.getRunAdvance(RowFixtures.T, 0, 19, 0, 19, false, 10)
        assertEquals("clip left", expectedLeft, parts[1].toFloat(), 0.01f)
        assertEquals("clip right", expectedRight, parts[2].toFloat(), 0.01f)
    }

    @Test
    fun rangeNineteenToFortyPaintsNoText() {
        // Run 0 ends at 19 (not > 19), run 2 starts at 40 (not < 40).
        val (canvas, _) = drawFixture(0, RowModel.Pass.Range(19, 40))
        assertEquals(emptyList<String>(), canvas.ops.paintEvents())
    }

    // ---------------------------------------------------------- guards

    @Test
    fun runsOutsideTheTextOrWithUnknownStylesAreSkippedInEveryPass() {
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(
                    RowFixtures.run(200u, 50u, 16f, 28f), // start + len > text.length (90)
                    RowFixtures.run(0u, 5u, 16f, 28f, style = 7u), // unknown style id
                ),
            ),
        )
        val fonts = newFonts()
        ShadowLog.clear()
        for (pass in listOf(
            RowModel.Pass.All,
            RowModel.Pass.Chrome,
            RowModel.Pass.Range(0, Int.MAX_VALUE),
            RowModel.Pass.Range(0, 5),
        )) {
            val canvas = newCanvas()
            model.draw(0, canvas, density, TranscriptPalette(false), fonts, pass)
            assertEquals("no text in pass $pass", emptyList<String>(), canvas.ops.paintEvents())
        }
        // The missing style logs once per id (StyleFonts.get), not per pass.
        assertEquals(
            1,
            ShadowLog.getLogs().count { it.tag == "RobocoTranscript" },
        )
    }

    @Test
    fun malformedScrollerIndicesAreDroppedAndLoggedOnceWithoutCrashing() {
        ShadowLog.clear()
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(
                    RowFixtures.run(0u, 19u, 16f, 28f),
                    RowFixtures.run(19u, 21u, 12f, 22f, scroller = 5u), // >= scrollers.size (1)
                ),
                scrollers = listOf(Scroller(16f, 40f, 288f, 32f, 400f)),
                fades = listOf(RowFixtures.fade(216f, 74f, 88f, 24f, FadeEdge.TRAILING, 5u)),
            ),
        )

        // The malformed run is in no layer, the valid one keeps layer 0;
        // the malformed fade is dropped from every layer.
        assertEquals(2, model.layers)
        assertArrayEquals(intArrayOf(0), model.runsByLayer[0])
        assertArrayEquals(intArrayOf(), model.runsByLayer[1])
        for (layer in 0 until model.layers) assertTrue(model.fadesByLayer[layer].isEmpty())

        val canvas = newCanvas()
        model.draw(0, canvas, density, TranscriptPalette(false), newFonts())
        assertEquals(listOf("text:0"), canvas.ops.paintEvents())
        assertEquals("one warning for the whole model", 1, ShadowLog.getLogs().count { it.tag == "RobocoTranscript" })
    }

    @Test
    fun aLayerOutOfRangeIsANoOp() {
        val canvas = newCanvas()
        RowModel(RowFixtures.row()).draw(2, canvas, density, TranscriptPalette(false), newFonts())
        RowModel(RowFixtures.row()).draw(-1, canvas, density, TranscriptPalette(false), newFonts())
        assertTrue(canvas.ops.isEmpty())
    }

    @Test
    fun useDensityRunsOnEveryDrawSoPaintsFollowTheCanvasDensity() {
        // Two draws at different densities over one fonts instance: the
        // second must rebuild its paints (the #609 count-only bug).
        val model = RowModel(RowFixtures.row())
        val fonts = newFonts()
        model.draw(0, newCanvas(), 2f, TranscriptPalette(false), fonts)
        val first = fonts[0u]!!.paint
        assertEquals(16.5f * 2f, first.textSize, 0f)
        model.draw(0, newCanvas(), 3f, TranscriptPalette(false), fonts)
        val second = fonts[0u]!!.paint
        assertEquals(16.5f * 3f, second.textSize, 0f)
    }
}

/**
 * A [Canvas] that records the ops [RowModel.draw] makes: boxes, text runs
 * (by their start index, −1 for the overload without one) and float
 * [Canvas.clipRect]s (`clip:<left>:<right>`). The draw-order contract is
 * asserted from this list, never from pixels. `internal open` (not
 * `private`) so ticket 118's [VeilTest] can subclass it with layer and
 * restore events.
 */
internal open class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {

    val ops = ArrayList<String>()

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        ops.add("box")
        super.drawRoundRect(rect, rx, ry, paint)
    }

    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        ops.add("text:$start")
        super.drawText(text, start, end, x, y, paint)
    }

    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        ops.add("text:$start")
        super.drawText(text, start, end, x, y, paint)
    }

    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
        ops.add("text:$index")
        super.drawText(text, index, count, x, y, paint)
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        ops.add("text:-1")
        super.drawText(text, x, y, paint)
    }

    override fun drawTextRun(
        text: CharSequence,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint,
    ) {
        ops.add("text:$start")
        super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
    }

    override fun drawTextRun(
        text: CharArray,
        index: Int,
        count: Int,
        contextIndex: Int,
        contextCount: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint,
    ) {
        ops.add("text:$index")
        super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        ops.add("clip:$left:$right")
        return super.clipRect(left, top, right, bottom)
    }
}
