package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.BoxStyle
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.RowDisplay
import kotlin.math.abs

/**
 * [Veil] chunk bookkeeping, the easeOut alphas and the layer-0 draw
 * composition (ticket 118). Bookkeeping and alpha follow the spec tables;
 * the draw-sequence half runs on Robolectric native graphics so the runs'
 * widths and font metrics are real (62's [RecordingCanvas] events are the
 * contract, never pixels).
 *
 * Draw fixture (the ticket's "draw sequence" spec): 34 + 50 UTF-16 units,
 * one fill box, a plain run `[0, 34)` and a run `[34, 84)` claimed by a
 * trailing fade — so `Chrome` paints the box and the faded run, `Range`
 * paints the plain run clipped at the glyph edge, and the fresh chunk
 * rides in one bounded `layer:` event.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VeilTest {

    private val density = 2f

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

    private fun newFonts(): StyleFonts = StyleFonts().apply {
        update(RowFixtures.styles)
        // Qualified: inside apply, a bare `density` is StyleFonts.density (0).
        useDensity(this@VeilTest.density)
    }

    /** A model over the given text (runs empty — grow only reads text and key). */
    private fun modelOf(text: String, key: ULong = 1uL, version: ULong = 1uL): RowModel =
        RowModel(
            RowFixtures.rowOf(emptyList()).copy(
                key = key,
                version = version,
                text = text,
                copyText = text,
            ),
        )

    /** One live chunk `[5, 11)` started at `100` (from "Hello" → "Hello world"). */
    private fun grownChunk(): Veil {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        veil.grow(modelOf("Hello world", version = 2uL), animate = true)
        assertTrue(veil.tick(100L))
        return veil
    }

    // ------------------------------------------------------ chunk bookkeeping

    @Test
    fun theFirstModelOnlyBecomesTheBaseline() {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        assertTrue(veil.chunks.isEmpty())
        assertFalse(veil.hasChunks)
    }

    @Test
    fun growingTheSameInstanceTwiceRecordsOneChunk() {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        val grown = modelOf("Hello world", version = 2uL)
        veil.grow(grown, animate = true)
        veil.grow(grown, animate = true) // recomposition with the same instance
        assertEquals(1, veil.chunks.size)
    }

    @Test
    fun aGrowRecordsTheChunkUnstartedAndTickStampsIt() {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        veil.grow(modelOf("Hello world", version = 2uL), animate = true)
        val c = veil.chunks.single()
        assertEquals(5, c.from)
        assertEquals(11, c.to)
        assertEquals(Veil.UNSTARTED, c.started)
        assertTrue(veil.tick(1_000_000L))
        assertEquals(1_000_000L, c.started)
    }

    @Test
    fun anInsertionAtTheChunkEdgeAddsAChunkAfterIt() {
        val veil = grownChunk()
        veil.grow(modelOf("Hello worldabcd", version = 3uL), animate = true) // p = 11, delta = 4
        val chunks = veil.chunks
        assertEquals(2, chunks.size)
        assertEquals(5, chunks[0].from)
        assertEquals(11, chunks[0].to)
        assertEquals(100L, chunks[0].started)
        assertEquals(11, chunks[1].from)
        assertEquals(15, chunks[1].to)
        assertEquals(Veil.UNSTARTED, chunks[1].started)
    }

    @Test
    fun anInsertionBeforeTheChunkShiftsItRight() {
        val veil = grownChunk()
        veil.grow(modelOf("HeXYZllo world", version = 3uL), animate = true) // p = 2, delta = 3
        val chunks = veil.chunks
        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].from)
        assertEquals(5, chunks[0].to)
        assertEquals(Veil.UNSTARTED, chunks[0].started)
        assertEquals(8, chunks[1].from)
        assertEquals(14, chunks[1].to)
        assertEquals(100L, chunks[1].started)
    }

    @Test
    fun anInsertionInsideTheChunkSplitsItOnTheOldClock() {
        val veil = grownChunk()
        veil.grow(modelOf("Hello woABrld", version = 3uL), animate = true) // p = 8, delta = 2
        val chunks = veil.chunks
        assertEquals(3, chunks.size)
        assertEquals(5, chunks[0].from)
        assertEquals(8, chunks[0].to)
        assertEquals(100L, chunks[0].started)
        assertEquals(8, chunks[1].from)
        assertEquals(10, chunks[1].to)
        assertEquals(Veil.UNSTARTED, chunks[1].started)
        assertEquals(10, chunks[2].from)
        assertEquals(13, chunks[2].to)
        assertEquals(100L, chunks[2].started)
    }

    @Test
    fun aNonInsertionClearsAllChunks() {
        val veil = grownChunk()
        veil.grow(modelOf("Xello worldZ", version = 3uL), animate = true)
        assertTrue(veil.chunks.isEmpty())
    }

    @Test
    fun aShrinkClearsAllChunks() {
        val veil = grownChunk()
        veil.grow(modelOf("Hello worl", version = 3uL), animate = true)
        assertTrue(veil.chunks.isEmpty())
    }

    @Test
    fun anEqualLengthNewVersionClearsAllChunks() {
        // The width-only rebuild: same text, a new instance.
        val veil = grownChunk()
        veil.grow(modelOf("Hello world", version = 3uL), animate = true)
        assertTrue(veil.chunks.isEmpty())
    }

    @Test
    fun aKeyChangeClearsAllChunks() {
        val veil = grownChunk()
        veil.grow(modelOf("Hello world again", key = 2uL, version = 3uL), animate = true)
        assertTrue(veil.chunks.isEmpty())
    }

    @Test
    fun growWithoutAnimationRecordsNothingClearsAndMovesTheBaseline() {
        val veil = grownChunk()
        veil.grow(modelOf("Hello world again", version = 3uL), animate = false)
        assertTrue(veil.chunks.isEmpty())
        // The baseline still moved: the next animated grow records from it.
        veil.grow(modelOf("Hello world again!", version = 4uL), animate = true)
        val c = veil.chunks.single()
        assertEquals(17, c.from)
        assertEquals(18, c.to)
    }

    @Test
    fun tickKeepsTheChunkJustBeforeExpiryAndDropsItAtIt() {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        veil.grow(modelOf("Hello world", version = 2uL), animate = true)
        val t = 1_000_000_000L
        assertTrue(veil.tick(t))
        assertTrue(veil.tick(t + 219_999_999L))
        assertEquals(1, veil.chunks.size)
        assertFalse(veil.tick(t + 220_000_000L))
        assertTrue(veil.chunks.isEmpty())
    }

    // ------------------------------------------------------------------ alpha

    @Test
    fun anUnstartedChunkHasAlphaZero() {
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        veil.grow(modelOf("Hello world", version = 2uL), animate = true)
        assertEquals(0, veil.alphaInt(veil.chunks.single(), 110_000_000L))
    }

    @Test
    fun alphaFollowsTheEaseOutTable() {
        // 220 ms UIKit curveEaseOut, cubic-bezier(0, 0, 0.58, 1), rounded
        // half up — the ticket's table: 41 / 96 / 175 / 231 / 251 (±1).
        val veil = Veil()
        veil.grow(modelOf("Hello"), animate = true)
        veil.grow(modelOf("Hello world", version = 2uL), animate = true)
        veil.tick(0L)
        val c = veil.chunks.single()
        assertEquals(0, veil.alphaInt(c, 0L))
        assertAlpha(41, veil.alphaInt(c, 22_000_000L))
        assertAlpha(96, veil.alphaInt(c, 55_000_000L))
        assertAlpha(175, veil.alphaInt(c, 110_000_000L))
        assertAlpha(231, veil.alphaInt(c, 165_000_000L))
        assertAlpha(251, veil.alphaInt(c, 198_000_000L))
    }

    private fun assertAlpha(expected: Int, actual: Int) {
        assertTrue("alpha $actual is not within ±1 of $expected", abs(actual - expected) <= 1)
    }

    // --------------------------------------------------------- draw sequence

    /** 34 + 50 UTF-16 units: the plain run and the faded run's text. */
    private val text: String =
        "Streaming text fades in per chunk." + "A trailing line wider than its slot fades out here"

    /** The draw fixture: `(old baseline, veiled model)` with measured run widths. */
    private fun drawModels(fonts: StyleFonts): Pair<RowModel, RowModel> {
        val paint = fonts[0u]!!.paint
        val plainWidth = paint.measureText(text, 0, 34) / density
        val fadedWidth = paint.measureText(text, 34, 84) / density
        val new: RowDisplay = RowFixtures.rowOf(
            runs = listOf(
                RowFixtures.run(0u, 34u, 16f, 28f).copy(width = plainWidth),
                RowFixtures.run(34u, 50u, 16f, 92f).copy(width = fadedWidth),
            ),
            boxes = listOf(
                RowFixtures.box(16f, 40f, 288f, 32f, 10f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND),
            ),
            fades = listOf(RowFixtures.fade(216f, 74f, 88f, 24f, FadeEdge.TRAILING, null)),
        ).copy(text = text, version = 2uL, height = 112f, copyText = text)
        val old = RowFixtures.rowOf(emptyList())
            .copy(text = text.substring(0, 14), version = 1uL, copyText = text.substring(0, 14))
        return RowModel(old) to RowModel(new)
    }

    private fun veilCanvas(): VeilCanvas = VeilCanvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))

    /** The paint event list: boxes, text runs, veil layers and restores. */
    private fun List<String>.events(): List<String> =
        filter { it == "box" || it.startsWith("text:") || it.startsWith("layer:") || it == "restore" }

    @Test
    fun noLiveChunkDrawsExactlyThePassAllEventList() {
        val fonts = newFonts()
        val (_, new) = drawModels(fonts)
        val veil = Veil()
        veil.grow(new, animate = true) // first model: baseline only, no chunks
        val palette = TranscriptPalette(false)

        val veiled = veilCanvas()
        veil.draw(veiled, new, density, palette, fonts, 0L)
        val plain = veilCanvas()
        new.draw(0, plain, density, palette, fonts, RowModel.Pass.All)

        assertEquals(plain.ops.events(), veiled.ops.events())
        assertTrue(veiled.ops.none { it.startsWith("layer:") })
    }

    @Test
    fun oneMidFadeChunkDrawsChromeThenSettledThenTheLayeredFreshText() {
        val fonts = newFonts()
        val (old, new) = drawModels(fonts)
        val veil = Veil()
        veil.grow(old, animate = true)
        veil.grow(new, animate = true) // chunk [14, 34)
        veil.tick(0L) // started now
        val canvas = veilCanvas()
        veil.draw(canvas, new, density, TranscriptPalette(false), fonts, now = 110_000_000L)

        // Boxes first, the faded run's text once (through the fade), the
        // settled [0, 14) clipped, then the chunk in one layer at alpha
        // 175 with the fresh [14, 34) clipped inside, closed by restores.
        assertEquals(
            listOf("box", "text:34", "restore", "text:0", "restore", "layer:175", "text:0", "restore", "restore"),
            canvas.ops.events(),
        )
        assertEquals("the faded run painted more than once", 1, canvas.ops.count { it == "text:34" })
    }

    @Test
    fun anUnstartedChunkDrawsNoLayerAndNoFreshText() {
        val fonts = newFonts()
        val (old, new) = drawModels(fonts)
        val veil = Veil()
        veil.grow(old, animate = true)
        veil.grow(new, animate = true) // chunk [14, 34), unstarted
        val canvas = veilCanvas()
        veil.draw(canvas, new, density, TranscriptPalette(false), fonts, now = 0L)

        assertTrue(canvas.ops.none { it.startsWith("layer:") })
        // Only the settled half: one text:0, the faded run once.
        assertEquals(1, canvas.ops.count { it == "text:0" })
        assertEquals(1, canvas.ops.count { it == "text:34" })
    }

    @Test
    fun aChunkOverOnlyTheFadedRunDrawsNoLayer() {
        val fonts = newFonts()
        val (_, new) = drawModels(fonts)
        val veil = Veil()
        // The previous text ends at 34: the chunk covers the faded run only,
        // which is never veiled, so there are no bounds and no layer.
        veil.grow(
            RowModel(
                RowFixtures.rowOf(emptyList())
                    .copy(text = text.substring(0, 34), version = 1uL, copyText = text.substring(0, 34)),
            ),
            animate = true,
        )
        veil.grow(new, animate = true) // chunk [34, 84)
        veil.tick(0L)
        val canvas = veilCanvas()
        veil.draw(canvas, new, density, TranscriptPalette(false), fonts, now = 110_000_000L)

        assertTrue(canvas.ops.none { it.startsWith("layer:") })
        // The whole plain run settled, unclipped; the faded run once.
        assertEquals(listOf("box", "text:34", "restore", "text:0"), canvas.ops.events())
    }

    @Test
    fun theChunkLayerBoundsAreTightAroundTheIntersectingRun() {
        val fonts = newFonts()
        val (old, new) = drawModels(fonts)
        val veil = Veil()
        veil.grow(old, animate = true)
        veil.grow(new, animate = true)
        veil.tick(0L)
        val canvas = veilCanvas()
        veil.draw(canvas, new, density, TranscriptPalette(false), fonts, now = 110_000_000L)

        val layer = canvas.layers.single()
        val paint = fonts[0u]!!.paint
        val fm = Paint.FontMetrics()
        paint.getFontMetrics(fm)
        val plainWidth = paint.measureText(text, 0, 34) / density
        // 4 dp horizontal pad, font metrics ± 2 dp vertical — around the
        // run's box, never the whole-clip ±1e5 of saveLayerAlpha(null, …).
        assertEquals(16f * density - 4f * density, layer[0], 0.01f)
        assertEquals(28f * density + fm.top - 2f * density, layer[1], 0.01f)
        assertEquals((16f + plainWidth) * density + 4f * density, layer[2], 0.01f)
        assertEquals(28f * density + fm.bottom + 2f * density, layer[3], 0.01f)
        assertEquals(175f, layer[4], 1f)
        assertTrue("left ${layer[0]} is a whole-clip layer", layer[0] > 0f)
        assertTrue("right ${layer[2]} is a whole-clip layer", layer[2] < 1e5f)
    }
}

/**
 * Ticket 62's [RecordingCanvas] plus the veil's own events:
 * [Canvas.saveLayerAlpha] as `layer:<alpha>` (with the bounds kept in
 * [layers] for the tightness assertions) and [Canvas.restoreToCount] as
 * `restore` (the Range split and the fade's layer both restore).
 */
private class VeilCanvas(bitmap: Bitmap) : RecordingCanvas(bitmap) {

    /** `[left, top, right, bottom, alpha]` per saveLayerAlpha. */
    val layers = ArrayList<FloatArray>()

    override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int {
        ops.add("layer:$alpha")
        layers.add(floatArrayOf(left, top, right, bottom, alpha.toFloat()))
        return super.saveLayerAlpha(left, top, right, bottom, alpha)
    }

    override fun restoreToCount(saveCount: Int) {
        ops.add("restore")
        super.restoreToCount(saveCount)
    }
}
