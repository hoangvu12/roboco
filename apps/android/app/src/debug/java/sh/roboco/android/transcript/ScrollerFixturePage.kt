package sh.roboco.android.transcript

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.BoxStyle
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.Scroller

/**
 * The transcript-scroller specimen (debug gallery, ticket 116): the real
 * [ScrollerViewport] over three [RowModel] fixtures — a code-block body
 * that overflows its card, a wide table that scrolls with its header,
 * border and rules inside the scroller, and a code block that fits (no
 * mask, no drag). The long body appears at rest, 12 dp in and at the end,
 * the table at rest and at the end, so the fades show every state: none on
 * the left at rest, 24 dp on the right, both mid-way, none on the right at
 * the end. Every item is a live scroller — drag them in the gallery. Layer
 * 0 (the code card and label) draws fixed behind; only layer 1 moves.
 *
 * The numbers mirror the Rust geometry (see the ticket's fixture table);
 * run widths come from the real [StyleFonts] paints, and `contentWidth`
 * is the widest line plus 2·CODE_PAD_X (28 pt), as `markdown.rs:611`.
 */
@Composable
fun ScrollerFixturePage() {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Read before the draw pass: an appearance flip only swaps the palette.
    val dark = RobocoTheme.isDark
    val background = RobocoTheme.palette.background
    val fonts = remember(context) {
        // StyleFonts' default constructor resolves typefaces through the
        // shared chain, so make sure it is built first (memoised).
        Fonts.load(context)
        StyleFonts()
    }
    // Once per density: run widths come from the paints, so the items must
    // be rebuilt when the density changes.
    val items = remember(fonts, density) { buildScrollerRows(fonts, density) }
    val palette = TranscriptPalette(dark)
    Column(
        Modifier
            .fillMaxSize()
            .background(background) // ticket 01: #F3F3F5 / #060606
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (item in items) key(item) {
            // Each item owns its state: at rest, 12 dp in, or at the end.
            val state = remember(item) { ScrollState(item.initialPx) }
            if (item.toEnd) LaunchedEffect(state) { state.scrollTo(state.maxValue) }
            Box(
                Modifier
                    .size(390.dp, item.heightDp.dp)
                    .drawBehind {
                        drawIntoCanvas { c ->
                            item.model.draw(0, c.nativeCanvas, density, palette, fonts)
                        }
                    },
            ) {
                ScrollerViewport(
                    s = item.s,
                    state = state,
                    drawLayer = {
                        drawIntoCanvas { c ->
                            item.model.draw(1, c.nativeCanvas, density, palette, fonts)
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- fixture

/** One built fixture item: its [RowModel], the row height in dp, the [Scroller] and the item's offset state. */
private class ScrollerItem(
    val model: RowModel,
    val heightDp: Int,
    val s: Scroller,
    val initialPx: Int = 0,
    val toEnd: Boolean = false,
)

/** Row 1's overflowing code body: three syntax-coloured mono lines (fixture text). */
private val CODE_LONG_LINES: List<List<Pair<String, ColorRole>>> = listOf(
    listOf(
        "fun" to ColorRole.SYNTAX_KEYWORD,
        " main" to ColorRole.SYNTAX_FUNCTION,
        "() {" to ColorRole.SYNTAX_PUNCTUATION,
    ),
    listOf(
        "    println" to ColorRole.SYNTAX_FUNCTION,
        "(" to ColorRole.SYNTAX_PUNCTUATION,
        "\"A deliberately long line that runs well past the right edge of the code card\"" to ColorRole.SYNTAX_STRING,
        ")" to ColorRole.SYNTAX_PUNCTUATION,
    ),
    listOf("}" to ColorRole.SYNTAX_PUNCTUATION),
)

/**
 * Builds the six items over the three rows. Row width 390, card x = 18,
 * card width 354, text scale 1: the code card's 32 pt header and 12 pt
 * bottom padding, the table's 39 pt header band, 25 pt rows and 1 pt
 * rules (`markdown.rs:413-424`, `:602-613`). `contentWidth` = the widest
 * layer-1 line + 28 (side padding 14 + 14); the two `check`s pin the
 * overflow facts so a font change cannot silently turn the long block
 * into a fitting one (or the short one into an overflowing one).
 */
private fun buildScrollerRows(fonts: StyleFonts, d: Float): List<ScrollerItem> {
    val book = StyleBook()
    val code = FixtureRow(fonts, d, book, height = 104f)
    val short = FixtureRow(fonts, d, book, height = 64f)
    val table = FixtureRow(fonts, d, book, height = 117f)
    val label = code.style(FaceRole.SANS_MEDIUM, 12.5f)
    val mono = code.style(FaceRole.MONO, 13.5f)
    val semi = table.style(FaceRole.SANS_SEMIBOLD, 16.5f)
    val body = table.style(FaceRole.SANS, 16.5f)
    fonts.update(book.descs)
    fonts.useDensity(d)

    // CODE_LONG — the card (fill, hairline, language label) is layer 0 and
    // stays fixed; the three mono lines are the scroller's content.
    code.box(18f, 0f, 354f, 104f, 12f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND)
    code.box(18f, 0f, 354f, 104f, 12f, BoxStyle.HAIRLINE, ColorRole.CODE_BORDER)
    code.text("kotlin", 32f, 6.5f, 19f, label, ColorRole.TEXT_TERTIARY)
    var widest = 0f
    for ((i, parts) in CODE_LONG_LINES.withIndex()) {
        widest = maxOf(widest, code.textRow(parts, 14f, i * 20f, 20f, mono, scroller = 0u))
    }
    val longContent = widest + 28f
    code.scroller(18f, 32f, 354f, 72f, longContent)
    check(longContent > 354.5f) { "CODE_LONG must overflow its card: contentWidth $longContent" }

    // CODE_SHORT — the same card at h 64; the single line fits, so the
    // viewport neither scrolls nor fades.
    short.box(18f, 0f, 354f, 64f, 12f, BoxStyle.FILL, ColorRole.CODE_BACKGROUND)
    short.box(18f, 0f, 354f, 64f, 12f, BoxStyle.HAIRLINE, ColorRole.CODE_BORDER)
    short.text("kotlin", 32f, 6.5f, 19f, label, ColorRole.TEXT_TERTIARY)
    val shortW = short.textRow(
        listOf("val" to ColorRole.SYNTAX_KEYWORD, " x = 1" to ColorRole.CODE_TEXT),
        14f,
        0f,
        20f,
        mono,
        scroller = 0u,
    )
    val shortContent = shortW + 28f
    short.scroller(18f, 32f, 354f, 32f, shortContent)
    check(shortContent < 353.5f) { "CODE_SHORT must fit its card: contentWidth $shortContent" }

    // TABLE_WIDE — everything but the row height lives inside the scroller:
    // the header fills, the rounded hairline border and the 1 pt row rules
    // scroll and fade with the cells (markdown.rs:635-653).
    table.box(0f, 0f, 560f, 39f, 10f, BoxStyle.FILL, ColorRole.TABLE_HEADER_BACKGROUND, scroller = 0u)
    table.box(0f, 19.5f, 560f, 19.5f, 0f, BoxStyle.FILL, ColorRole.TABLE_HEADER_BACKGROUND, scroller = 0u)
    table.box(0f, 0f, 560f, 117f, 10f, BoxStyle.HAIRLINE, ColorRole.TABLE_BORDER, scroller = 0u)
    table.box(0f, 39f, 560f, 1f, 0f, BoxStyle.FILL, ColorRole.TABLE_BORDER, scroller = 0u)
    table.box(0f, 78f, 560f, 1f, 0f, BoxStyle.FILL, ColorRole.TABLE_BORDER, scroller = 0u)
    for ((c, h) in listOf("Role", "Light", "Dark", "Notes").withIndex()) {
        table.text(h, 10f + 140f * c, 7f, 25f, semi, ColorRole.TEXT, scroller = 0u)
    }
    for ((c, cell) in listOf("code", "#FAFAFB", "#0B0B0D", "Card fill").withIndex()) {
        table.text(cell, 10f + 140f * c, 46f, 25f, body, ColorRole.TEXT, scroller = 0u)
    }
    for ((c, cell) in listOf("link", "#5B43E8", "#8B7CF6").withIndex()) {
        table.text(cell, 10f + 140f * c, 85f, 25f, body, ColorRole.TEXT, scroller = 0u)
    }
    // The one scroller-hosted link (a markdown table cell), hit-tested at
    // its run width; 126 converts through ScrollerGeometry.
    val seeDocs = table.text("see docs", 430f, 85f, 25f, body, ColorRole.LINK, scroller = 0u)
    table.link(430f, 85f, seeDocs, 25f, "https://example.com/docs", 0u)
    table.scroller(18f, 0f, 354f, 117f, 560f)

    val codeDisplay = code.build(1)
    val shortDisplay = short.build(2)
    val tableDisplay = table.build(3)
    val codeModel = RowModel(codeDisplay)
    val shortModel = RowModel(shortDisplay)
    val tableModel = RowModel(tableDisplay)
    return listOf(
        ScrollerItem(codeModel, 104, codeDisplay.scrollers[0]),
        ScrollerItem(codeModel, 104, codeDisplay.scrollers[0], initialPx = (12f * d).roundToInt()),
        ScrollerItem(codeModel, 104, codeDisplay.scrollers[0], toEnd = true),
        ScrollerItem(tableModel, 117, tableDisplay.scrollers[0]),
        ScrollerItem(tableModel, 117, tableDisplay.scrollers[0], toEnd = true),
        ScrollerItem(shortModel, 64, shortDisplay.scrollers[0]),
    )
}

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "Scrollers light", widthDp = 390, heightDp = 760)
@Composable
private fun ScrollerLightPreview() {
    RobocoTheme(Appearance.Light) { ScrollerFixturePage() }
}

@Preview(name = "Scrollers dark", widthDp = 390, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScrollerDarkPreview() {
    RobocoTheme(Appearance.Dark) { ScrollerFixturePage() }
}
