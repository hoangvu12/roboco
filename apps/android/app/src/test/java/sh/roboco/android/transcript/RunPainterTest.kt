package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.FontChain
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextRun

/**
 * Ticket 63's run painting over a recording [Canvas] (native graphics, the
 * production typeface chain): the glyph origin and colour, the decoration
 * bar geometry (1 pt thick, 2 dp below / 5 dp above the baseline, in the
 * run's colour, after the glyphs, inside the run's own draw), the guards,
 * the mono double-width cell grid, the plan cache, the glyph-edge offset,
 * and the text-only shimmer entry point.
 *
 * The expectations are the ticket's acceptance list verbatim; the widths
 * the assertions need are computed with the very paint the painter used
 * (the same [StyleFonts] entry), so they never drift from the drawing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RunPainterTest {

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

    private val styles: List<StyleDesc> = listOf(
        StyleDesc(0u, FaceRole.SANS, 16.5f, true),
        StyleDesc(1u, FaceRole.MONO, 13.5f, false),
        StyleDesc(2u, FaceRole.SANS, 13.5f, true),
    )

    private fun fonts(d: Float): StyleFonts = StyleFonts().apply {
        update(styles)
        useDensity(d)
    }

    private fun displayOf(text: String, runs: List<TextRun>): RowDisplay = RowDisplay(
        key = 1uL,
        version = 1uL,
        width = 360f,
        height = 200f,
        text = text,
        runs = runs,
        boxes = emptyList(),
        links = emptyList(),
        scrollers = emptyList(),
        widgets = emptyList(),
        fades = emptyList(),
        copyText = text,
    )

    private fun run(
        start: Int,
        len: Int,
        x: Float,
        baseline: Float,
        width: Float,
        style: UShort,
        color: ColorRole,
        decoration: Decoration,
    ): TextRun = TextRun(start.toUInt(), len.toUInt(), x, baseline, width, style, color, decoration, null, null)

    // ------------------------------------------------------- glyph drawing

    @Test
    fun aPlainSansRunIsOneDrawTextAtTheRustOriginInItsRoleColour() {
        for (dark in listOf(false, true)) {
            val d = 3f
            val f = fonts(d)
            val text = "Plain text and a link"
            val model = RowModel(displayOf(text, listOf(run(0, 16, 10f, 20f, 40f, 0u, ColorRole.TEXT, Decoration.NONE))))
            val canvas = RunRecordingCanvas()
            RunPainter().drawRun(canvas, model, 0, d, f, TranscriptPalette(dark))

            assertEquals("one drawText, no bar (dark=$dark)", 1, canvas.ops.size)
            val t = canvas.texts.single()
            assertSame("the row string itself, never a substring", text, t.text)
            assertEquals(0, t.start)
            assertEquals(16, t.end)
            assertEquals(10f * d, t.x, 0f)
            assertEquals(20f * d, t.y, 0f)
            assertEquals(if (dark) 0xFFE8E8EA.toInt() else 0xFF27272C.toInt(), t.color)
        }
    }

    @Test
    fun aSansRunWithCjkIsOneNaturalWidthCall() {
        // Only Mono faces use the 2-cell path: Sans CJK draws through the
        // fallback chain at natural width, exactly as the measurer measured.
        val d = 3f
        val f = fonts(d)
        val text = "see 名称 here"
        val model = RowModel(displayOf(text, listOf(run(0, text.length, 16f, 20f, 100f, 0u, ColorRole.TEXT_FAINT, Decoration.NONE))))
        assertSame(MonoWide.NONE, model.widePlan(0, f[0u]!!))

        val canvas = RunRecordingCanvas()
        RunPainter().drawRun(canvas, model, 0, d, f, TranscriptPalette(false))
        assertEquals(1, canvas.ops.size)
        val t = canvas.texts.single()
        assertEquals(0, t.start)
        assertEquals(text.length, t.end)
    }

    // ------------------------------------------------------ decoration bar

    @Test
    fun anUnderlinedRunDrawsItsOnePointBarTwoDpBelowTheBaselineAfterTheGlyphs() {
        val d = 3f
        val f = fonts(d)
        val text = "the docs"
        val r = run(0, 8, 10f, 20f, 50f, 0u, ColorRole.TEXT_FAINT, Decoration.UNDERLINE)
        val canvas = RunRecordingCanvas()
        TextRunPainter.draw(canvas, text, r, f[0u]!!, 0xFF797981.toInt(), d)

        assertEquals("glyphs then one bar", 2, canvas.ops.size)
        assertTrue("glyphs first", canvas.ops[0] is RunRecordingCanvas.TextOp)
        val bar = canvas.ops[1] as RunRecordingCanvas.RectOp
        assertEquals(30f, bar.l, 0.001f)
        assertEquals(66f, bar.t, 0.001f)
        assertEquals(180f, bar.r, 0.001f)
        assertEquals(69f, bar.b, 0.001f)
        assertEquals("the bar is the run colour", 0xFF797981.toInt(), bar.color)
    }

    @Test
    fun aStruckRunDrawsItsBarFiveDpAboveTheBaseline() {
        val d = 3f
        val f = fonts(d)
        val text = "old ideas"
        val r = run(0, 9, 10f, 20f, 50f, 0u, ColorRole.TEXT, Decoration.STRIKETHROUGH)
        val canvas = RunRecordingCanvas()
        TextRunPainter.draw(canvas, text, r, f[0u]!!, 0xFF27272C.toInt(), d)

        val bar = canvas.rects.single()
        assertEquals(30f, bar.l, 0.001f)
        assertEquals(45f, bar.t, 0.001f)
        assertEquals(180f, bar.r, 0.001f)
        assertEquals(48f, bar.b, 0.001f)
        assertEquals(0xFF27272C.toInt(), bar.color)
    }

    @Test
    fun theBarScalesWithTheDensity() {
        // d = 2.625 (420 dpi): every Rust value × d, the +2/−5/1 constants
        // included — (baseline + 2) · d, not baseline · d + 2.
        val d = 2.625f
        val f = fonts(d)
        val text = "the docs"
        val r = run(0, 8, 10f, 20f, 50f, 0u, ColorRole.TEXT_FAINT, Decoration.UNDERLINE)
        val canvas = RunRecordingCanvas()
        TextRunPainter.draw(canvas, text, r, f[0u]!!, 0xFF797981.toInt(), d)

        val bar = canvas.rects.single()
        assertEquals(26.25f, bar.l, 0.001f)
        assertEquals(57.75f, bar.t, 0.001f)
        assertEquals(157.5f, bar.r, 0.001f)
        assertEquals(60.375f, bar.b, 0.001f)
    }

    @Test
    fun anAlphaCarryingRoleKeepsItsAlphaInBothTextAndBar() {
        val d = 3f
        val f = fonts(d)
        val text = "Soft file name.rs"
        val model = RowModel(displayOf(text, listOf(run(0, text.length, 16f, 20f, 80f, 2u, ColorRole.TEXT_SOFT, Decoration.UNDERLINE))))
        val canvas = RunRecordingCanvas()
        RunPainter().drawRun(canvas, model, 0, d, f, TranscriptPalette(false))

        assertEquals(0xD9303035.toInt(), canvas.texts.single().color)
        assertEquals(0xD9303035.toInt(), canvas.rects.single().color)
    }

    @Test
    fun anEmptyRunDrawsNoGlyphs() {
        val d = 3f
        val f = fonts(d)
        val text = "abc"
        val canvas = RunRecordingCanvas()
        TextRunPainter.drawGlyphs(canvas, text, run(1, 0, 10f, 20f, 12f, 0u, ColorRole.TEXT, Decoration.NONE), f[0u]!!, 0xFF27272C.toInt(), d)
        assertTrue(canvas.ops.isEmpty())
    }

    @Test
    fun anEmptyDecoratedRunStillDrawsItsBar() {
        // Rust never emits len == 0 (markdown.rs:485-487); if one appears,
        // iOS draws an empty CTLine plus the bar — the glyphs skip, the
        // bar does not.
        val d = 3f
        val f = fonts(d)
        val canvas = RunRecordingCanvas()
        TextRunPainter.draw(
            canvas,
            "abc",
            run(1, 0, 10f, 20f, 50f, 0u, ColorRole.TEXT, Decoration.UNDERLINE),
            f[0u]!!,
            0xFF27272C.toInt(),
            d,
        )
        assertEquals("no glyphs, one bar", 1, canvas.ops.size)
        val bar = canvas.rects.single()
        assertEquals(30f, bar.l, 0.001f)
        assertEquals(66f, bar.t, 0.001f)
        assertEquals(180f, bar.r, 0.001f)
        assertEquals(69f, bar.b, 0.001f)
    }

    // ------------------------------------------------- shimmer (text only)

    @Test
    fun drawRunTextPaintsGlyphsOnlyEvenForADecoratedRun() {
        val d = 3f
        val f = fonts(d)
        val text = "Soft file name.rs"
        val r = run(0, text.length, 16f, 20f, 80f, 2u, ColorRole.TEXT_SOFT, Decoration.UNDERLINE)
        val model = RowModel(displayOf(text, listOf(r)))
        val explicit = 0xFF008000.toInt()

        val viaFacade = RunRecordingCanvas()
        RunPainter().drawRunText(viaFacade, model, 0, d, f, explicit)
        assertEquals(1, viaFacade.ops.size)
        assertEquals(explicit, viaFacade.texts.single().color)

        val direct = RunRecordingCanvas()
        TextRunPainter.drawGlyphs(direct, text, r, f[2u]!!, explicit, d)
        assertEquals(1, direct.ops.size)
        assertEquals(explicit, direct.texts.single().color)

        assertTrue("no bar in the shimmer pass, ever", viaFacade.rects.isEmpty() && direct.rects.isEmpty())
    }

    // ------------------------------------------------------------- guards

    @Test
    fun outOfRangeRunsAndUnknownStylesDrawNothingNotEvenTheBar() {
        val text = "short"
        val model = RowModel(
            displayOf(
                text,
                listOf(
                    run(3, 20, 10f, 20f, 40f, 0u, ColorRole.TEXT, Decoration.UNDERLINE), // 3 + 20 > 5
                    run(0, 5, 10f, 40f, 40f, 9u, ColorRole.TEXT, Decoration.UNDERLINE), // unknown style id
                    run(0, 5, 10f, 60f, 40f, 0u, ColorRole.TEXT, Decoration.NONE), // valid
                ),
            ),
        )
        val f = fonts(3f)
        val painter = RunPainter()
        for (i in intArrayOf(0, 1)) {
            val canvas = RunRecordingCanvas()
            painter.drawRun(canvas, model, i, 3f, f, TranscriptPalette(false))
            assertEquals("run $i: nil line on iOS — no text, no bar", 0, canvas.ops.size)
        }

        // And through the model's own draw loop: only the valid run paints.
        val canvas = RunRecordingCanvas()
        model.draw(0, canvas, 3f, TranscriptPalette(false), f, RowModel.Pass.All)
        assertEquals(1, canvas.ops.size)
        assertEquals(60f * 3f, canvas.texts.single().y, 0f)
    }

    // -------------------------------------------------- mono double-width

    @Test
    fun aMonoRunWithDoubleWidthClustersSplitsIntoCellAlignedDrawTextCalls() {
        val d = 3f
        val f = fonts(d)
        val text = "| 名称 |" // |, space, 名, 称, space, | — UTF-16 units 0..5
        val model = RowModel(displayOf(text, listOf(run(0, 6, 10f, 20f, 60f, 1u, ColorRole.CODE_TEXT, Decoration.NONE))))
        val canvas = RunRecordingCanvas()
        RunPainter().drawRun(canvas, model, 0, d, f, TranscriptPalette(false))
        assertEquals(4, canvas.ops.size)

        val paint = f[1u]!!.paint
        val c = FontChain.cellWidth(paint)
        assertEquals("Geist Mono cell = 0.6 em", 0.6f * 13.5f * d, c, 0.01f)
        val cell2 = 2f * c
        val head = FontChain.advance(paint, text, 0, 2) // "| "
        val x0 = 10f * d
        val y = 20f * d
        val (narrow, ming, cheng, tail) = canvas.texts

        for (t in canvas.texts) assertSame("the row string itself, never a substring", text, t.text)
        assertEquals(0, narrow.start)
        assertEquals(2, narrow.end)
        assertEquals(x0, narrow.x, 0.0001f)
        assertEquals(y, narrow.y, 0f)
        assertEquals(2, ming.start)
        assertEquals(3, ming.end)
        assertEquals(x0 + head + (cell2 - FontChain.advance(paint, text, 2, 3)) / 2f, ming.x, 0.0001f)
        assertEquals(y, ming.y, 0f)
        assertEquals(3, cheng.start)
        assertEquals(4, cheng.end)
        assertEquals(x0 + head + cell2 + (cell2 - FontChain.advance(paint, text, 3, 4)) / 2f, cheng.x, 0.0001f)
        assertEquals(4, tail.start)
        assertEquals(6, tail.end)
        assertEquals(x0 + head + 2f * cell2, tail.x, 0.0001f)
        assertEquals(0xFF303035.toInt(), narrow.color)
    }

    @Test
    fun theCodePointsGeistMonoCoversDrawAsOneFastPathCall() {
        // 〃 and 〜 are wide-range code points Geist Mono covers at 1 cell;
        // doubling them would push the rest of the line one cell past its
        // Rust position.
        val d = 3f
        val f = fonts(d)
        val text = "〃〜.." // 4 UTF-16 units (both marks are BMP)
        val model = RowModel(displayOf(text, listOf(run(0, 4, 10f, 20f, 40f, 1u, ColorRole.CODE_TEXT, Decoration.NONE))))
        assertSame(MonoWide.NONE, model.widePlan(0, f[1u]!!))

        val canvas = RunRecordingCanvas()
        RunPainter().drawRun(canvas, model, 0, d, f, TranscriptPalette(false))
        val t = canvas.texts.single()
        assertSame(text, t.text)
        assertEquals(0, t.start)
        assertEquals(4, t.end)
        assertEquals(10f * d, t.x, 0f)
    }

    @Test
    fun thePlanIsComputedOnceAndSharedAcrossDraws() {
        val text = "| 名称 |"
        val model = RowModel(displayOf(text, listOf(run(0, 6, 10f, 20f, 60f, 1u, ColorRole.CODE_TEXT, Decoration.NONE))))
        val f = fonts(3f)
        val font = f[1u]!!

        val plan = model.widePlan(0, font)
        assertArrayEquals(intArrayOf(2, 3, 3, 4), plan)
        assertSame(plan, model.widePlan(0, font))

        val painter = RunPainter()
        painter.drawRun(RunRecordingCanvas(), model, 0, 3f, f, TranscriptPalette(false))
        painter.drawRun(RunRecordingCanvas(), model, 0, 3f, f, TranscriptPalette(false))
        assertSame("drawing twice reuses the identical plan array", plan, model.widePlan(0, font))
    }

    // ------------------------------------------------------- glyph offsets

    @Test
    fun advanceToWalksTheCellGridOfAMonoRun() {
        val d = 3f
        val f = fonts(d)
        val text = "| 名称 |"
        val model = RowModel(displayOf(text, listOf(run(0, 6, 10f, 20f, 60f, 1u, ColorRole.CODE_TEXT, Decoration.NONE))))
        val painter = RunPainter()
        val paint = f[1u]!!.paint
        val head = FontChain.advance(paint, text, 0, 2) // "| "
        val c = FontChain.cellWidth(paint)

        assertEquals("名's leading edge", head, painter.advanceTo(model, 0, 2, f), 0.0001f)
        assertEquals("称's leading edge", head + 2f * c, painter.advanceTo(model, 0, 3, f), 0.0001f)
        assertEquals("end", head + 4f * c + FontChain.advance(paint, text, 4, 6), painter.advanceTo(model, 0, 6, f), 0.0001f)
        assertEquals(0f, painter.advanceTo(model, 0, 0, f), 0f) // clamped below the run
        assertEquals( // clamped above the run
            painter.advanceTo(model, 0, 6, f),
            painter.advanceTo(model, 0, 99, f),
            0.0001f,
        )
    }

    @Test
    fun aSansRunOffsetIsTicketSixtyTwosFormulaExactly() {
        val f = fonts(2.625f)
        val text = "Plain text and a link"
        val model = RowModel(displayOf(text, listOf(run(0, 16, 10f, 20f, 40f, 0u, ColorRole.TEXT, Decoration.NONE))))
        val painter = RunPainter()
        val paint = f[0u]!!.paint
        for (at in intArrayOf(0, 1, 4, 10, 16)) {
            assertEquals(
                "at=$at",
                paint.getRunAdvance(text, 0, 16, 0, 16, false, at),
                painter.advanceTo(model, 0, at, f),
                0f,
            )
        }
    }

    @Test
    fun anOffsetInsideAWideClusterSnapsToItsLeadingEdge() {
        val f = fonts(3f)
        val text = "👩‍💻" // U+1F469 ZWJ U+1F4BB: 5 UTF-16 units, one cluster, wide
        val model = RowModel(displayOf(text, listOf(run(0, 5, 10f, 20f, 40f, 1u, ColorRole.CODE_TEXT, Decoration.NONE))))
        assertArrayEquals(intArrayOf(0, 5), model.widePlan(0, f[1u]!!))

        val painter = RunPainter()
        assertEquals(0f, painter.advanceTo(model, 0, 0, f), 0f)
        for (at in 1..4) assertEquals("a partly fresh cluster goes wholly to the fresh side (at=$at)", 0f, painter.advanceTo(model, 0, at, f), 0f)
        assertEquals(2f * FontChain.cellWidth(f[1u]!!.paint), painter.advanceTo(model, 0, 5, f), 0.0001f)
    }

    // -------------------------------------------------------- Range splits

    @Test
    fun aRangeClipSplitsAStruckMonoRunAtTheCellEdges() {
        val d = 3f
        val f = fonts(d)
        val text = "| 名称 |"
        val model = RowModel(displayOf(text, listOf(run(0, 6, 10f, 20f, 60f, 1u, ColorRole.CODE_TEXT, Decoration.STRIKETHROUGH))))
        val canvas = RunRecordingCanvas()
        model.draw(0, canvas, d, TranscriptPalette(false), f, RowModel.Pass.Range(2, 4))

        // One clip around the whole run: left = 名's leading edge,
        // right = the second cell's trailing edge — the same x the glyphs
        // and the bar are clipped at, never a separate pass.
        val clip = canvas.clips.single()
        val paint = f[1u]!!.paint
        val cell2 = 2f * FontChain.cellWidth(paint)
        val x0 = 10f * d
        assertEquals(x0 + FontChain.advance(paint, text, 0, 2), clip.left, 0.0001f)
        assertEquals(x0 + FontChain.advance(paint, text, 0, 2) + 2f * cell2, clip.right, 0.0001f)

        // The struck bar is inside the same clip, after the glyphs.
        assertEquals(4, canvas.texts.size)
        assertEquals(1, canvas.rects.size)
        assertTrue(canvas.ops.indexOf(canvas.rects.single()) > canvas.ops.indexOf(canvas.texts.first()))
    }
}

/**
 * A [Canvas] that records the ops the run painters make: `drawText` calls
 * (the string identity, the index range, the origin, the paint colour),
 * float `drawRect` bars and float `clipRect`s, in call order. The draw
 * order and geometry contract is asserted from this list, never from
 * pixels; the bitmap only satisfies the constructor.
 */
private class RunRecordingCanvas : Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)) {

    class TextOp(val text: String, val start: Int, val end: Int, val x: Float, val y: Float, val color: Int)

    class RectOp(val l: Float, val t: Float, val r: Float, val b: Float, val color: Int)

    class ClipOp(val left: Float, val right: Float)

    val ops = ArrayList<Any>()
    val texts: List<TextOp>
        get() = ops.filterIsInstance<TextOp>()
    val rects: List<RectOp>
        get() = ops.filterIsInstance<RectOp>()
    val clips: List<ClipOp>
        get() = ops.filterIsInstance<ClipOp>()

    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        ops.add(TextOp(text, start, end, x, y, paint.color))
    }

    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        ops.add(TextOp(text.toString(), start, end, x, y, paint.color))
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        ops.add(RectOp(left, top, right, bottom, paint.color))
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        ops.add(ClipOp(left, right))
        return super.clipRect(left, top, right, bottom)
    }
}
