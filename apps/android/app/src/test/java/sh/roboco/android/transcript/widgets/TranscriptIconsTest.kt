package sh.roboco.android.transcript.widgets

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.ColorRole

/**
 * The icon widget's resolver, caches and drawing (ticket 124; Robolectric at
 * `w393dp-h852dp-xxhdpi`, d = 3, NATIVE graphics so both the VectorDrawable
 * and the PNG rasterisations are real). The Roborazzi goldens live in
 * [TranscriptIconScreenshotTest].
 *
 * The three caches are process-wide, so every test resets them first and the
 * log seam is captured into a list. The pixel probes host the widget in
 * White cells at the core's real rect sizes and check, per the ticket:
 *
 *  - `tool-pen` at 18 dp is a **template**: every pixel is a blend of White
 *    and the role colour (light secondary `#62626A`, danger `#DC2626`, dark
 *    danger `#F87171`), and the ink spans 14.5–18 dp of the 18 dp box —
 *    aspect-fit (the SVG's ≈ 21.5 of 24 units × 0.75), not the intrinsic
 *    24 dp and not clipped;
 *  - `fileicon-files-code-blue` keeps its own `#2563EB` / `#60A5FA` strokes
 *    whatever role is passed (never tinted);
 *  - the task boxes measure ticket 06's glyph box: `square`'s stroked
 *    squircle (2–22 of 24 units + the Medium 1.68-unit stroke) spans
 *    ≈ 15.61 dp and `checkmark.square.fill`'s fill spans 14.4 dp, both
 *    centred at (9, 9) — i.e. the 17.28 dp box (`18 × 0.8 × 1.2`) centred
 *    in the widget rect;
 *  - the 16 dp chip glyph measures a 15.36 dp box the same way
 *    (`questionmark.bubble`'s 2.5–21.5 unit grid + stroke → 13.23 dp of
 *    ink, centred at (8, 8)).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class TranscriptIconsTest {

    @get:Rule
    val compose = createComposeRule()

    private val logged = mutableListOf<String>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        compose.mainClock.autoAdvance = false
        TranscriptIcons.resetForTest()
        TranscriptIcons.log = { logged += it }
    }

    @After
    fun restoreSeams() {
        TranscriptIcons.resetForTest()
    }

    // ------------------------------------------------------------ the resolver

    @Test
    fun sourceResolvesEveryToolStemFromTheLiteralTable() {
        val table = mapOf(
            "tool-terminal" to R.drawable.zi_terminal,
            "tool-document" to R.drawable.zi_document,
            "tool-document-add" to R.drawable.zi_document_add,
            "tool-pen" to R.drawable.zi_pen,
            "tool-magnifer" to R.drawable.zi_magnifer,
            "tool-folder-with-files" to R.drawable.zi_folder_with_files,
            "tool-global" to R.drawable.zi_global,
            "tool-checklist" to R.drawable.zi_checklist,
            "tool-bot" to R.drawable.zi_bot,
            "tool-widget" to R.drawable.zi_widget,
            "tool-chat-round-line" to R.drawable.zi_chat_round_line,
            "tool-alt-arrow-down" to R.drawable.zi_alt_arrow_down,
            "tool-alt-arrow-right" to R.drawable.zi_alt_arrow_right,
            "tool-arrow-up-right" to R.drawable.zi_arrow_up_right,
            "tool-git-branch" to R.drawable.zi_git_branch,
        )
        assertEquals("the 15-stem table from the ticket's spec", table, TranscriptIcons.toolDrawables)
        for ((name, res) in table) {
            val source = TranscriptIcons.source(name)
            assertTrue("$name resolves to a Tool source, not $source", source is TranscriptIconSource.Tool)
            assertEquals("$name drawable", res, (source as TranscriptIconSource.Tool).res)
        }
        assertTrue("known stems are never logged", logged.isEmpty())
    }

    @Test
    fun sourceTreatsEveryFileiconNameAsAFileIcon() {
        for (name in listOf("fileicon-files-rust", "fileicon-folders-folder", "fileicon-files-does-not-exist")) {
            assertEquals("$name is a FileIcon whatever art exists", TranscriptIconSource.FileIcon(name), TranscriptIcons.source(name))
        }
        assertTrue("the prefix classifies without opening assets", logged.isEmpty())
    }

    @Test
    fun sourceResolvesEveryCoreSfNameToItsRegistryGlyph() {
        val expected = mapOf(
            "questionmark.bubble" to RobocoIcons.QuestionmarkBubble,
            "exclamationmark.triangle" to RobocoIcons.ExclamationmarkTriangle,
            "arrow.triangle.branch" to RobocoIcons.ArrowTriangleBranch,
            "command" to RobocoIcons.Command,
            "wand.and.stars" to RobocoIcons.WandAndStars,
            "photo" to RobocoIcons.Photo,
            "square" to RobocoIcons.Square,
            "checkmark.square.fill" to RobocoIcons.CheckmarkSquareFill,
        )
        for ((name, icon) in expected) {
            val source = TranscriptIcons.source(name)
            assertTrue("$name resolves to a Symbol source, not $source", source is TranscriptIconSource.Symbol)
            assertEquals("$name glyph", icon, (source as TranscriptIconSource.Symbol).icon)
        }
        assertTrue("known SF names are never logged", logged.isEmpty())
    }

    @Test
    fun sourceReturnsUnknownForNamesThatResolveToNothing() {
        for (name in listOf("tool-nope", "waveform", "")) {
            assertEquals("$name draws nothing", TranscriptIconSource.Unknown, TranscriptIcons.source(name))
        }
        assertEquals(
            "each unknown name is logged once, with the ticket's wording",
            listOf(
                "icon widget: no glyph for 'tool-nope'",
                "icon widget: no glyph for 'waveform'",
                "icon widget: no glyph for ''",
            ),
            logged,
        )
    }

    @Test
    fun unknownNamesAreLoggedOnceHoweverOftenTheyResolve() {
        repeat(10) { TranscriptIcons.source("waveform") }
        repeat(10) { TranscriptIcons.source("tool-nope") }
        assertEquals("one line per name, not per lookup", 2, logged.size)
    }

    @Test
    fun composingAnUnknownIconTenTimesStillLogsOnce() {
        val tick = mutableIntStateOf(0)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.size(16.dp)) {
                    // The role flips every recomposition, so the widget is
                    // genuinely re-resolved — the cache and the log-once
                    // memory hold.
                    TranscriptIcon(
                        name = "waveform",
                        color = if (tick.intValue % 2 == 0) ColorRole.TEXT_TERTIARY else ColorRole.TEXT_SECONDARY,
                        heightDp = 16f,
                        palette = TranscriptPalette(false),
                    )
                }
            }
        }
        compose.waitForIdle()
        repeat(10) {
            compose.runOnUiThread {
                tick.intValue += 1
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
        }
        assertEquals(listOf("icon widget: no glyph for 'waveform'"), logged)
    }

    // -------------------------------------------------------- the bitmap cache

    @Test
    fun fileIconDecodesBothVariantsOfAColourIcon() {
        val light = checkNotNull(TranscriptIcons.fileIcon(context, "fileicon-files-code-blue", dark = false)) {
            "the light PNG must be merged into the debug variant (android-app#13)"
        }
        val dark = checkNotNull(TranscriptIcons.fileIcon(context, "fileicon-files-code-blue", dark = true)) {
            "the dark PNG must be merged into the debug variant (android-app#13)"
        }
        assertNotSame("dark art is a separate decode, not a re-tint", light, dark)
        for (bitmap in listOf(light, dark)) {
            assertEquals("the raster is 96 px wide", 96, bitmap.width)
            assertEquals("the raster is 96 px tall", 96, bitmap.height)
        }
        assertStrokes(light, Color(0xFF2563EB), "light code-blue")
        assertStrokes(dark, Color(0xFF60A5FA), "dark code-blue")
        assertTrue("art that exists is never logged", logged.isEmpty())
    }

    @Test
    fun fileIconFallsBackToTheLightArtWhenThereIsNoDarkVariant() {
        // 93 file icons have a -dark variant; angular-component is one of
        // the ones that do not, so dark falls back to the universal art.
        val light = checkNotNull(TranscriptIcons.fileIcon(context, "fileicon-files-angular-component", dark = false)) {
            "the light angular-component PNG must be merged into the debug variant"
        }
        assertSame(
            "no -dark art: the same cached instance, like UIKit's fallback",
            light,
            TranscriptIcons.fileIcon(context, "fileicon-files-angular-component", dark = true),
        )
        assertTrue("a fallback is not a miss: nothing logged", logged.isEmpty())
    }

    @Test
    fun fileIconCachesItsMissesSoAssetsOpenOncePerStem() {
        val opened = mutableListOf<String>()
        TranscriptIcons.openAsset = { _, path ->
            opened += path
            null
        }
        assertNull(TranscriptIcons.fileIcon(context, "fileicon-files-does-not-exist", dark = false))
        assertNull(TranscriptIcons.fileIcon(context, "fileicon-files-does-not-exist", dark = false))
        assertNull(TranscriptIcons.fileIcon(context, "fileicon-files-does-not-exist", dark = true))
        assertEquals(
            "one open per stem: the light stem, then the -dark stem (whose miss is also cached)",
            listOf(
                "icons/fileicon-files-does-not-exist.png",
                "icons/fileicon-files-does-not-exist-dark.png",
            ),
            opened,
        )
        assertEquals("the miss is logged once", listOf("icon widget: no glyph for 'fileicon-files-does-not-exist'"), logged)
    }

    // ---------------------------------------------------------- the tool glyphs

    @Test
    fun toolGlyphsAreTintedTemplatesAspectFitInLightMode() {
        hostCells(
            Appearance.Light,
            ProbeCell("pen-secondary", "tool-pen", ColorRole.TEXT_SECONDARY, 18f),
            ProbeCell("pen-danger", "tool-pen", ColorRole.DANGER, 18f),
        )
        assertGlyphInk("secondary tool-pen", cellPixels("pen-secondary"), Color.White, Palette.Light.secondary)
        assertGlyphInk("danger tool-pen", cellPixels("pen-danger"), Color.White, Palette.Light.danger)
        // The pen's ink (≈ 21.5 of the 24 viewBox units, stroke included,
        // × 0.75 ≈ 16 dp) spans 14.5–18 dp of the 18 dp rect: aspect-fit,
        // not the intrinsic 24 dp, and not clipped.
        val bounds = checkNotNull(inkBounds(cellPixels("pen-secondary"), Color.White)) {
            "the pen draws ink"
        }
        assertDpBetween(bounds.widthDp, 14.5f, 18f, "pen ink width")
        assertDpBetween(bounds.heightDp, 14.5f, 18f, "pen ink height")
    }

    @Test
    fun toolGlyphTintFollowsTheAppearance() {
        hostCells(
            Appearance.Dark,
            ProbeCell("pen-secondary", "tool-pen", ColorRole.TEXT_SECONDARY, 18f),
            ProbeCell("pen-danger", "tool-pen", ColorRole.DANGER, 18f),
        )
        assertGlyphInk("dark secondary tool-pen", cellPixels("pen-secondary"), Color.White, Palette.Dark.secondary)
        assertGlyphInk("dark danger tool-pen", cellPixels("pen-danger"), Color.White, Palette.Dark.danger)
    }

    // ------------------------------------------------------- the task boxes

    @Test
    fun taskBoxesDrawTheGlyphBoxCentredAt18dp() {
        hostCells(
            Appearance.Light,
            ProbeCell("task-unchecked", "square", ColorRole.TEXT_TERTIARY, 18f),
            ProbeCell("task-checked", "checkmark.square.fill", ColorRole.ACCENT, 18f),
        )
        val unchecked = cellPixels("task-unchecked")
        val checked = cellPixels("task-checked")
        assertGlyphInk("unchecked task box", unchecked, Color.White, Palette.Light.tertiary)
        assertGlyphInk("checked task box", checked, Color.White, Palette.Light.accent)

        // Ticket 06's Icon box is 18 × 0.8 × 1.2 = 17.28 dp, centred: the
        // stroked squircle (2–22 of 24 units, stroke 1.5 × Medium 1.12)
        // spans (21.68 / 24) × 17.28 ≈ 15.61 dp; the filled one spans
        // (20 / 24) × 17.28 = 14.4 dp. Both centre on (9, 9).
        val square = inkBounds(unchecked, Color.White)!!
        assertDpIsWithin(15.61f, square.widthDp, 0.5f, "square ink width")
        assertDpIsWithin(15.61f, square.heightDp, 0.5f, "square ink height")
        assertDpIsWithin(9f, square.centerXDp, 0.5f, "square centre x")
        assertDpIsWithin(9f, square.centerYDp, 0.5f, "square centre y")
        val fill = inkBounds(checked, Color.White)!!
        assertDpIsWithin(14.4f, fill.widthDp, 0.5f, "fill ink width")
        assertDpIsWithin(14.4f, fill.heightDp, 0.5f, "fill ink height")
        assertDpIsWithin(9f, fill.centerXDp, 0.5f, "fill centre x")
        assertDpIsWithin(9f, fill.centerYDp, 0.5f, "fill centre y")
    }

    @Test
    fun taskBoxInkFollowsTheAppearance() {
        hostCells(
            Appearance.Dark,
            ProbeCell("task-unchecked", "square", ColorRole.TEXT_TERTIARY, 18f),
            ProbeCell("task-checked", "checkmark.square.fill", ColorRole.ACCENT, 18f),
        )
        assertGlyphInk("dark unchecked task box", cellPixels("task-unchecked"), Color.White, Palette.Dark.tertiary)
        assertGlyphInk("dark checked task box", cellPixels("task-checked"), Color.White, Palette.Dark.accent)
    }

    // ------------------------------------------------------- the chip glyphs

    @Test
    fun chipRowGlyphsMeasureThe15dp36IconBox() {
        hostCells(Appearance.Light, ProbeCell("chip", "questionmark.bubble", ColorRole.ACCENT, 16f))
        val px = cellPixels("chip")
        assertGlyphInk("chip glyph", px, Color.White, Palette.Light.accent)
        // The Icon box is 16 × 0.8 × 1.2 = 15.36 dp, centred: the glyph's
        // 2.5–21.5 unit grid with the Medium stroke spans (20.68 / 24) ×
        // 15.36 ≈ 13.23 dp, centred on (8, 8).
        val bounds = inkBounds(px, Color.White)!!
        // ±1 dp on the ink extents (decision, round 31): the threshold counts
        // anti-aliased edge pixels and the bounds round to whole px (CI:
        // 14.0 dp). The centres keep ±0.5.
        assertDpIsWithin(13.23f, bounds.widthDp, 1f, "chip ink width")
        assertDpIsWithin(13.23f, bounds.heightDp, 1f, "chip ink height")
        assertDpIsWithin(8f, bounds.centerXDp, 0.5f, "chip centre x")
        assertDpIsWithin(8f, bounds.centerYDp, 0.5f, "chip centre y")
    }

    // ------------------------------------------------------------ the file art

    @Test
    fun fileIconArtIsNeverTinted() {
        // DANGER is passed on purpose: a tint would turn the strokes red.
        hostCells(Appearance.Light, ProbeCell("file", "fileicon-files-code-blue", ColorRole.DANGER, 16f))
        assertGlyphInk("untinted file icon", cellPixels("file"), Color.White, Color(0xFF2563EB))
    }

    @Test
    fun anAppearanceFlipSwapsFileIconArtAndToolTint() {
        val dark = mutableStateOf(false)
        compose.setContent {
            RobocoTheme(if (dark.value) Appearance.Dark else Appearance.Light) {
                val isDark = RobocoTheme.isDark
                Column {
                    Box(Modifier.testTag("tool").size(18.dp).background(Color.White)) {
                        TranscriptIcon("tool-pen", ColorRole.TEXT_SECONDARY, 18f, TranscriptPalette(isDark))
                    }
                    Box(Modifier.testTag("file").size(16.dp).background(Color.White)) {
                        TranscriptIcon("fileicon-files-code-blue", ColorRole.TEXT_SOFT, 16f, TranscriptPalette(isDark))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertGlyphInk("tool light", cellPixels("tool"), Color.White, Palette.Light.secondary)
        assertGlyphInk("file light", cellPixels("file"), Color.White, Color(0xFF2563EB))
        compose.runOnUiThread {
            dark.value = true
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertGlyphInk("tool dark", cellPixels("tool"), Color.White, Palette.Dark.secondary)
        assertGlyphInk("file dark", cellPixels("file"), Color.White, Color(0xFF60A5FA))
    }

    // -------------------------------------------------- decorative, no input

    @Test
    fun theWidgetExposesNoSemanticsNodeAndTapsPassThrough() {
        val taps = mutableListOf<Offset>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(
                    Modifier
                        .testTag("host")
                        .size(18.dp)
                        .background(Color.White)
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> taps += offset }
                        },
                ) {
                    TranscriptIcon("tool-pen", ColorRole.TEXT_SECONDARY, 18f, TranscriptPalette(false))
                }
            }
        }
        compose.waitForIdle()
        // iOS's UIImageView is not an accessibility element: no description
        // anywhere in the tree (the host tag itself carries none).
        assertTrue(
            "the widget must not expose a content description",
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes().isEmpty(),
        )
        // A real touch on the widget's bounds (its centre) reaches the
        // parent's pointerInput: the widget consumes nothing. The tap runs
        // synchronously; no idle wait (the clock is frozen).
        compose.onNodeWithTag("host").performTouchInput { click(center) }
        assertEquals("the tap reached the parent gesture handler", 1, taps.size)
    }

    // ----------------------------------------------------------------- helpers

    /** One probed cell: the tag to capture, and the widget call inside it. */
    private class ProbeCell(
        val tag: String,
        val name: String,
        val color: ColorRole,
        val sizeDp: Float,
    )

    /** Hosts the cells in one composition (one setContent per test). */
    private fun hostCells(appearance: Appearance, vararg cells: ProbeCell) {
        val isDark = appearance == Appearance.Dark
        compose.setContent {
            RobocoTheme(appearance) {
                Column {
                    for (cell in cells) {
                        Box(
                            Modifier
                                .testTag(cell.tag)
                                .size(cell.sizeDp.dp)
                                .background(Color.White),
                        ) {
                            TranscriptIcon(cell.name, cell.color, cell.sizeDp, TranscriptPalette(isDark))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun cellPixels(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /**
     * Every **opaque** pixel of the raster is [ink] (± 2 8-bit steps per
     * channel — the ticket's "opaque stroke pixels") and there are solid
     * strokes to measure; anti-aliased edges are only counted, since a
     * premultiplied readback halves their RGB.
     */
    private fun assertStrokes(bitmap: ImageBitmap, ink: Color, what: String) {
        val px = bitmap.toPixelMap()
        var inked = 0
        var solid = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                if (c.alpha <= 0.02f) continue
                inked++
                if (c.alpha < 0.98f) continue
                solid++
                assertPx("$what pixel at ($x, $y)", c, ink)
            }
        }
        assertTrue("$what: only $inked inked pixels in a 96 px raster", inked >= 200)
        assertTrue("$what: no solid stroke pixels", solid >= 100)
    }

    /**
     * The cell's ink is [ink] and the widget draws nothing else: every
     * pixel is a blend of [bg] and [ink] (anti-aliased edges included),
     * the corners are [bg], and at least one pixel is near-solid [ink].
     */
    private fun assertGlyphInk(what: String, px: PixelMap, bg: Color, ink: Color) {
        assertPx("$what corner (0, 0)", px[0, 0], bg)
        assertPx("$what corner (w-1, 0)", px[px.width - 1, 0], bg)
        assertPx("$what corner (0, h-1)", px[0, px.height - 1], bg)
        assertPx("$what corner (w-1, h-1)", px[px.width - 1, px.height - 1], bg)
        var solid = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                assertBlend("$what pixel at ($x, $y)", c, bg, ink)
                if (near(c, ink, 3)) solid++
            }
        }
        assertTrue("$what: no near-solid $ink ink in the glyph", solid >= 8)
    }

    /** The ink extent in dp: pixels farther than 15 8-bit steps from [bg]. */
    private fun inkBounds(px: PixelMap, bg: Color): InkBounds? {
        var minX = px.width
        var minY = px.height
        var maxX = -1
        var maxY = -1
        val t = 15f / 255f
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                if (abs(c.red - bg.red) > t || abs(c.green - bg.green) > t || abs(c.blue - bg.blue) > t) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return InkBounds(
            minX / PX_PER_DP,
            minY / PX_PER_DP,
            (maxX + 1) / PX_PER_DP,
            (maxY + 1) / PX_PER_DP,
        )
    }

    /** The ink rectangle in dp (the class-level qualifiers pin d = 3). */
    private class InkBounds(
        val leftDp: Float,
        val topDp: Float,
        val rightDp: Float,
        val bottomDp: Float,
    ) {
        val widthDp: Float get() = rightDp - leftDp
        val heightDp: Float get() = bottomDp - topDp
        val centerXDp: Float get() = (leftDp + rightDp) / 2f
        val centerYDp: Float get() = (topDp + bottomDp) / 2f
    }

    private fun assertDpIsWithin(expected: Float, actual: Float, tolerance: Float, what: String) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(expected - actual) <= tolerance,
        )
    }

    /** [low, high] inclusive — the ticket's ink-span window. */
    private fun assertDpBetween(actual: Float, low: Float, high: Float, what: String) {
        assertTrue(
            "$what: $actual dp, expected $low…$high dp",
            actual >= low && actual <= high,
        )
    }

    /** Per-channel colour assertion; [tolerance] is in 8-bit steps. */
    private fun assertPx(what: String, actual: Color, expected: Color, tolerance: Int = 2) {
        val t = tolerance / 255f
        assertTrue(
            "$what: rgb(${(actual.red * 255f).toInt()}, ${(actual.green * 255f).toInt()}, " +
                "${(actual.blue * 255f).toInt()}), expected $expected ± $tolerance",
            abs(actual.red - expected.red) <= t &&
                abs(actual.green - expected.green) <= t &&
                abs(actual.blue - expected.blue) <= t,
        )
    }

    /** Whether [c] is within [tolerance] 8-bit steps of [ink] per channel. */
    private fun near(c: Color, ink: Color, tolerance: Int): Boolean {
        val t = tolerance / 255f
        return abs(c.red - ink.red) <= t &&
            abs(c.green - ink.green) <= t &&
            abs(c.blue - ink.blue) <= t
    }

    /** [c] lies per channel between [a] and [b] (an alpha blend of the two). */
    private fun assertBlend(what: String, c: Color, a: Color, b: Color, tolerance: Int = 3) {
        val t = tolerance / 255f
        fun out(v: Float, p: Float, q: Float): Boolean = v < minOf(p, q) - t || v > maxOf(p, q) + t
        if (out(c.red, a.red, b.red) || out(c.green, a.green, b.green) || out(c.blue, a.blue, b.blue)) {
            assertTrue(
                "$what: rgb(${(c.red * 255f).toInt()}, ${(c.green * 255f).toInt()}, " +
                    "${(c.blue * 255f).toInt()}) is not a blend of $a and $b ± $tolerance",
                false,
            )
        }
    }

    private companion object {
        /** `w393dp-h852dp-xxhdpi` → density 3 (the class-level qualifiers). */
        const val PX_PER_DP = 3f
    }
}
