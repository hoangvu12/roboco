package sh.roboco.android.transcript

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.BoxPrim
import uniffi.roboco_core.BoxStyle
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.Fade
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.Scroller
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextRun
import uniffi.roboco_core.Widget

/**
 * The transcript-chrome specimen (debug gallery, ticket 64): the two
 * non-text primitive kinds of the display list — `BoxPrim` (rounded fills,
 * 1-px inside hairlines) and `Fade` (trailing and bottom overflow ramps) —
 * over a code block with a scroller, a table, a quote bar and rule, a
 * folded bubble, a file pill and tool chrome. Everything draws through the
 * real [RowModel.draw] path, never a bespoke painter, so the gallery, the
 * previews and the goldens exercise the same code as the transcript.
 *
 * The box and fade geometry mirrors the Rust core's
 * `crates/mobile/src/layout` output at text scale 1 (numbers in
 * `.scratch/android-ui-parity/issues/64-paint-boxes-overflow-fades.md`);
 * run baselines and widths are fixture data computed from the real
 * [StyleFonts] paints with the core's baseline formula
 * (`crates/mobile/src/layout/style.rs:245-247`), because only the core
 * measures in production.
 */
@Composable
fun RowChromeFixturePage() {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Read before the draw pass: appearance flips only swap the palette.
    val dark = RobocoTheme.isDark
    val background = RobocoTheme.palette.background
    val fonts = remember(context) {
        // StyleFonts' default constructor resolves typefaces through the
        // shared chain (FontChain), so make sure the chain is built —
        // memoised; this is what previews and the gallery draw with.
        Fonts.load(context)
        StyleFonts()
    }
    // Once per density: run widths and baselines come from the paints, so
    // the six models must be rebuilt when the density changes.
    val rows = remember(fonts, density) { buildChromeRows(fonts, density) }
    val palette = TranscriptPalette(dark)
    Column(
        Modifier
            .fillMaxSize()
            .background(background) // ticket 01: #F3F3F5 / #060606
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (row in rows) {
            Canvas(Modifier.size(390.dp, row.heightDp.dp)) {
                drawIntoCanvas { canvas ->
                    paintFixtureRow(canvas.nativeCanvas, row.model, density, fonts, palette)
                }
            }
        }
    }
}

/**
 * The debug stand-in for the real host (ticket 116 owns it): layer 0 in row
 * coordinates, then each scroller layer clipped to its viewport and
 * translated to its content origin. No scrolling, no edge fades.
 */
private fun paintFixtureRow(
    canvas: android.graphics.Canvas,
    model: RowModel,
    d: Float,
    fonts: StyleFonts,
    palette: IntArray,
) {
    model.draw(0, canvas, d, palette, fonts, RowModel.Pass.All)
    for (i in model.display.scrollers.indices) {
        val s = model.display.scrollers[i]
        val save = canvas.save()
        canvas.clipRect(s.x * d, s.y * d, (s.x + s.w) * d, (s.y + s.h) * d)
        canvas.translate(s.x * d, s.y * d)
        model.draw(i + 1, canvas, d, palette, fonts, RowModel.Pass.All)
        canvas.restoreToCount(save)
    }
}

// ---------------------------------------------------------------- fixture

/** One built fixture row: its [RowModel] and the row canvas height in dp. */
private class ChromeRow(val model: RowModel, val heightDp: Int)

/**
 * The style table shared by fixture rows: one id per distinct (face, size),
 * interned in first-use order — the way the core's style table grows. All
 * fixture styles are `ligatures = false` (only code wants them and the
 * mono runs are plain ASCII). Ticket 116's scroller fixture shares it.
 */
internal class StyleBook {
    private val ids = HashMap<Pair<FaceRole, Float>, UShort>()
    val descs = ArrayList<StyleDesc>()

    fun style(face: FaceRole, size: Float): UShort =
        ids.getOrPut(face to size) {
            val id = descs.size.toUShort()
            descs.add(StyleDesc(id, face, size, ligatures = false))
            id
        }
}

/** Row 1's code body: three syntax-coloured mono lines (fixture text). */
private val CODE_LINES: List<List<Pair<String, ColorRole>>> = listOf(
    listOf(
        "fn" to ColorRole.SYNTAX_KEYWORD,
        " main" to ColorRole.SYNTAX_FUNCTION,
        "() {" to ColorRole.SYNTAX_PUNCTUATION,
    ),
    listOf(
        "    println!" to ColorRole.SYNTAX_FUNCTION,
        "(" to ColorRole.SYNTAX_PUNCTUATION,
        "\"hello, roboco\"" to ColorRole.SYNTAX_STRING,
        ");" to ColorRole.SYNTAX_PUNCTUATION,
    ),
    listOf("}" to ColorRole.SYNTAX_PUNCTUATION),
)

/** Row 4's folded bubble: six lines of ≤ 26 chars, so each fits the 274.44 pt slot. */
private val BUBBLE_LINES = listOf(
    "This bubble is folded",
    "after six lines because",
    "the original message ran",
    "past eight; the sixth",
    "line is erased downward",
    "along a ramp to eight p",
)

/**
 * Builds the six fixture rows. Row width 390, content x 18, content width
 * 354, text scale 1; the numbers mirror the Rust geometry (see the table
 * in the ticket): the code block's `32 + lines·lh + 12` height, the table's
 * 39 pt rows and 1 pt rules, the bubble's `6·25 + (19 + 4) + 2·10` height,
 * the pill's 34 pt name inset and the tool chrome's 1.125 scale.
 */
private fun buildChromeRows(fonts: StyleFonts, d: Float): List<ChromeRow> {
    val book = StyleBook()
    val r1 = FixtureRow(fonts, d, book, height = 104f)
    val r2 = FixtureRow(fonts, d, book, height = 117f)
    val r3 = FixtureRow(fonts, d, book, height = 83f)
    val r4 = FixtureRow(fonts, d, book, height = 193f)
    val r5 = FixtureRow(fonts, d, book, height = 32f)
    val r6 = FixtureRow(fonts, d, book, height = 76f)
    // The whole style table first, in first-use order across the rows
    // (ids 0-4), so the paints exist before any run is measured.
    val label = r1.style(FaceRole.SANS_MEDIUM, 12.5f)
    val mono = r1.style(FaceRole.MONO, 13.5f)
    val semi = r2.style(FaceRole.SANS_SEMIBOLD, 16.5f)
    val body = r2.style(FaceRole.SANS, 16.5f)
    val small = r4.style(FaceRole.SANS_MEDIUM, 13.5f)
    fonts.update(book.descs)
    fonts.useDensity(d)

    // Row 1 — a fenced code block: label in the 32 pt header, mono body in
    // a scroller, rounded 12 pt fill plus a 1-px border
    // (markdown.rs:602-613).
    r1.box(18f, 0f, 354f, 104f, 12f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND)
    r1.box(18f, 0f, 354f, 104f, 12f, BoxStyle.HAIRLINE, ColorRole.CODE_BORDER)
    r1.text("rust", 32f, 6.5f, 19f, label, ColorRole.TEXT_TERTIARY)
    var widest = 0f
    for ((i, parts) in CODE_LINES.withIndex()) {
        widest = maxOf(widest, r1.textRow(parts, 14f, i * 20f, 20f, mono, scroller = 0u))
    }
    // The body scroller: viewport (18, 32, 354, 72), content = widest line
    // + 2·CODE_PAD_X. Appended after its runs — they carry its index, and
    // RowModel groups primitives by index, never by list position.
    r1.scroller(18f, 32f, 354f, 72f, widest + 28f)

    // Row 2 — a table: header band as a rounded fill plus a square lower
    // half, 1-px rounded border, 1 pt row rules before every row but the
    // first (markdown.rs:645-653). Columns 80/120, cell text at +10/+7.
    r2.box(18f, 0f, 240f, 39f, 10f, BoxStyle.FILL, ColorRole.TABLE_HEADER_BACKGROUND)
    r2.box(18f, 19.5f, 240f, 19.5f, 0f, BoxStyle.FILL, ColorRole.TABLE_HEADER_BACKGROUND)
    r2.box(18f, 0f, 240f, 117f, 10f, BoxStyle.HAIRLINE, ColorRole.TABLE_BORDER)
    r2.box(18f, 39f, 240f, 1f, 0f, BoxStyle.FILL, ColorRole.TABLE_BORDER)
    r2.box(18f, 78f, 240f, 1f, 0f, BoxStyle.FILL, ColorRole.TABLE_BORDER)
    r2.text("Role", 28f, 7f, 25f, semi, ColorRole.TEXT)
    r2.text("Light", 128f, 7f, 25f, semi, ColorRole.TEXT)
    r2.text("code", 28f, 46f, 25f, body, ColorRole.TEXT)
    r2.text("#FAFAFB", 128f, 46f, 25f, body, ColorRole.TEXT)
    r2.text("border", 28f, 85f, 25f, body, ColorRole.TEXT)
    r2.text("#E4E4E8", 128f, 85f, 25f, body, ColorRole.TEXT)

    // Row 3 — a blockquote bar (3 pt wide, half-radius) and a horizontal
    // rule (1 pt fill, not a hairline) (markdown.rs:547, :589).
    r3.box(18f, 0f, 3f, 50f, 1.5f, BoxStyle.FILL, ColorRole.QUOTE_BAR)
    r3.box(18f, 72f, 354f, 1f, 0f, BoxStyle.FILL, ColorRole.RULE)
    r3.text("Paint roles, not RGB:", 33f, 0f, 25f, body, ColorRole.TEXT_SECONDARY)
    r3.text("theming is paint-only.", 33f, 25f, 25f, body, ColorRole.TEXT_SECONDARY)

    // Row 4 — a folded user bubble: 6 of > 8 lines shown, the sixth erased
    // by a bottom fade to ≈ 8 % over the intact bubble fill
    // (rows.rs:791-838). bx = 18 + 354 − 304.44, text at bx + 15.
    r4.box(67.56f, 0f, 304.44f, 193f, 20f, BoxStyle.FILL, ColorRole.USER_BUBBLE)
    for ((i, s) in BUBBLE_LINES.withIndex()) {
        r4.text(s, 82.56f, 10f + i * 25f, 25f, body, ColorRole.TEXT)
    }
    r4.text("Show more", 82.56f, 164f, 19f, small, ColorRole.TEXT_SECONDARY)
    r4.fade(82.56f, 135f, 274.44f, 25f, FadeEdge.BOTTOM)

    // Row 5 — a file pill whose name overflows: clipped at 360 pt, faded
    // over the last 28 pt (rows.rs:818-829, :771-775). Name x = 67.56 + 34.
    r5.box(67.56f, 0f, 304.44f, 32f, 12f, BoxStyle.FILL, ColorRole.CHIP_BACKGROUND)
    r5.text("a-very-long-attachment-file-name-that-overflows.md", 101.56f, 6.5f, 19f, small, ColorRole.TEXT)
    r5.fade(332f, 6.5f, 28f, 19f, FadeEdge.TRAILING)

    // Row 6 — tool chrome at scale 1.125: a file badge with its icon well,
    // a subagent card (fill + hairline) and its tile
    // (tools.rs:940-942, :1088-1093).
    r6.box(18f, 4f, 120f, 24.75f, 5.625f, BoxStyle.FILL, ColorRole.TOOL_BADGE)
    r6.box(19.125f, 5.125f, 22.5f, 22.5f, 4.5f, BoxStyle.FILL, ColorRole.TOOL_WELL)
    r6.box(18f, 36f, 354f, 33.75f, 10.125f, BoxStyle.FILL, ColorRole.AGENT_CARD)
    r6.box(18f, 36f, 354f, 33.75f, 10.125f, BoxStyle.HAIRLINE, ColorRole.AGENT_CARD_BORDER)
    r6.box(27f, 42.75f, 20.25f, 20.25f, 5.625f, BoxStyle.FILL, ColorRole.AGENT_TILE)
    r6.text("main.rs", 48.375f, 6.875f, 19f, small, ColorRole.TEXT_SOFT)
    r6.text("Agent", 56.25f, 43.375f, 19f, small, ColorRole.TEXT)

    return listOf(r1, r2, r3, r4, r5, r6).mapIndexed { i, row ->
        ChromeRow(RowModel(row.build(i + 1)), row.height.toInt())
    }
}

/**
 * One fixture row under construction: accumulates the text, runs, boxes,
 * links, scrollers, fades, widgets and styles and produces the
 * [RowDisplay]. Heights are the fixture's own (the core decides them in
 * production); the run data is derived from [fonts] through the core's
 * baseline formula. Ticket 116's `ScrollerFixturePage` reuses this
 * builder, so it carries `links` and `widgets` lists (empty by default)
 * and boxes may live inside a scroller.
 */
internal class FixtureRow(
    private val fonts: StyleFonts,
    private val d: Float,
    private val styles: StyleBook,
    val width: Float = 390f,
    val height: Float,
) {
    private val sb = StringBuilder()
    private val runs = ArrayList<TextRun>()
    private val boxes = ArrayList<BoxPrim>()
    private val scrollers = ArrayList<Scroller>()
    private val fades = ArrayList<Fade>()
    private val links = ArrayList<LinkHit>()
    private val widgets = ArrayList<Widget>()
    private var at = 0

    /** Interns a style in the shared table and returns its id. */
    fun style(face: FaceRole, size: Float): UShort = styles.style(face, size)

    /**
     * Appends one run of `s` at (`x`, `top`) in a line box of `lh`, with
     * the core's half-leading baseline (`layout/style.rs:245-247`) and the
     * platform-measured width; returns the width in pt.
     */
    fun text(s: String, x: Float, top: Float, lh: Float, style: UShort, color: ColorRole, scroller: UInt? = null): Float {
        val paint = fonts[style]!!.paint
        val fm = paint.fontMetrics
        val ascent = -fm.ascent / d
        val descent = fm.descent / d
        val baseline = top + (lh - (ascent + descent)) / 2f + ascent
        val width = paint.measureText(s) / d
        runs.add(
            TextRun(
                start = at.toUInt(),
                len = s.length.toUInt(),
                x = x,
                baseline = baseline,
                width = width,
                style = style,
                color = color,
                decoration = Decoration.NONE,
                scroller = scroller,
                revealMs = null,
            ),
        )
        sb.append(s)
        at += s.length
        return width
    }

    /** Lays `parts` (text, role) left to right from `x` on one line; returns the line's total width in pt. */
    fun textRow(
        parts: List<Pair<String, ColorRole>>,
        x: Float,
        top: Float,
        lh: Float,
        style: UShort,
        scroller: UInt? = null,
    ): Float {
        var cx = x
        for ((s, color) in parts) cx += text(s, cx, top, lh, style, color, scroller)
        return cx - x
    }

    /** Appends one chrome box, optionally inside a scroller (wide-table fills and rules). */
    fun box(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        style: BoxStyle,
        color: ColorRole,
        scroller: UInt? = null,
    ) {
        boxes.add(BoxPrim(x, y, w, h, radius, style, color, scroller))
    }

    /** Appends one link hit, optionally inside a scroller (markdown table cells). */
    fun link(x: Float, y: Float, w: Float, h: Float, url: String, scroller: UInt? = null) {
        links.add(LinkHit(x, y, w, h, url, scroller))
    }

    /** Appends a scroller viewport; returns its index. */
    fun scroller(x: Float, y: Float, w: Float, h: Float, contentWidth: Float): UInt {
        scrollers.add(Scroller(x, y, w, h, contentWidth))
        return (scrollers.size - 1).toUInt()
    }

    /** Appends one overflow fade on the row canvas layer. */
    fun fade(x: Float, y: Float, w: Float, h: Float, edge: FadeEdge) {
        fades.add(Fade(x, y, w, h, edge, null))
    }

    /** The finished display list; [row] is the fixture row number (the display key). */
    fun build(row: Int): RowDisplay {
        val text = sb.toString()
        return RowDisplay(
            key = row.toULong(),
            version = 1uL,
            width = width,
            height = height,
            text = text,
            runs = runs.toList(),
            boxes = boxes.toList(),
            links = links.toList(),
            scrollers = scrollers.toList(),
            widgets = widgets.toList(),
            fades = fades.toList(),
            copyText = text,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "Row chrome light", widthDp = 390, heightDp = 720)
@Composable
private fun RowChromeLightPreview() {
    RobocoTheme(Appearance.Light) { RowChromeFixturePage() }
}

@Preview(name = "Row chrome dark", widthDp = 390, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RowChromeDarkPreview() {
    RobocoTheme(Appearance.Dark) { RowChromeFixturePage() }
}
