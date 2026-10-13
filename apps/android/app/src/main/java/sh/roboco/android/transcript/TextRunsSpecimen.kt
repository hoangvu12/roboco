package sh.roboco.android.transcript

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import sh.roboco.android.core.FontChain
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextRun

/**
 * The text-run specimen (debug gallery, ticket 63): every run-painting
 * case in one 360 × 200 dp fixture — body text and a link (markdown links
 * are coloured, never underlined), a struck span, faint thought prose
 * with an underlined link and a strike, a pending-bubble line, an
 * alpha-carrying role, and three mono lines whose `|` borders share pixel
 * columns only because wide clusters occupy exactly two cells (and `〃〜`,
 * which Geist Mono covers at one cell, are excluded). Drawn through the
 * real [RowModel.draw] path, never a bespoke painter, so the gallery, the
 * previews and the goldens exercise the same per-run code as the
 * transcript.
 *
 * The `x`/`width` values are fixture-made (Rust would send them): runs
 * are laid left to right from 16 dp per line with the same advance math
 * the painter uses — `FontChain.advance` for narrow stretches, two cells for
 * double-width clusters — so bars sit on their glyphs and the mono
 * columns land where the painter draws them.
 */
@Composable
fun TextRunsSpecimen(dark: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // StyleFonts' default constructor resolves typefaces through the
    // shared chain (FontChain.typeface), so make sure the chain is built:
    // memoised once startup's prewarm has run, and this is what lets
    // previews and the gallery draw the production faces.
    val fonts = remember(context) {
        Fonts.load(context)
        StyleFonts()
    }
    // Once per density: the fixture's own layout needs the synced paints
    // (61's data half, 62's paint side — there is no sync method).
    val model = remember(fonts, density) {
        fonts.update(SpecimenStyles)
        fonts.useDensity(density)
        fixtureModel(fonts, density)
    }
    Box(
        Modifier
            .size(360.dp, 200.dp)
            .background(RobocoTheme.palette.background), // ticket 01: #F3F3F5 / #060606
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { canvas ->
                fonts.update(SpecimenStyles)
                fonts.useDensity(density)
                model.draw(0, canvas.nativeCanvas, density, TranscriptPalette(dark), fonts)
            }
        }
    }
}

// ------------------------------------------------------------------ fixture

/** The specimen's own style table: body 16.5, small 13.5, code 13.5 mono (pt). */
private val SpecimenStyles: List<StyleDesc> = listOf(
    StyleDesc(0u, FaceRole.SANS, 16.5f, true),
    StyleDesc(1u, FaceRole.SANS, 13.5f, true),
    StyleDesc(2u, FaceRole.MONO, 13.5f, false),
)

private class SpecSpan(val text: String, val style: UShort, val color: ColorRole, val decoration: Decoration)

private class SpecLine(val baseline: Float, val spans: List<SpecSpan>)

private val SpecimenLines: List<SpecLine> = listOf(
    SpecLine(20f, listOf(
        SpecSpan("Plain text and ", 0u, ColorRole.TEXT, Decoration.NONE),
        SpecSpan("a link", 0u, ColorRole.LINK, Decoration.NONE),
    )),
    SpecLine(45f, listOf(
        SpecSpan("old ideas", 0u, ColorRole.TEXT, Decoration.STRIKETHROUGH),
        SpecSpan(" are struck.", 0u, ColorRole.TEXT, Decoration.NONE),
    )),
    SpecLine(66f, listOf(
        SpecSpan("see ", 1u, ColorRole.TEXT_FAINT, Decoration.NONE),
        SpecSpan("the docs", 1u, ColorRole.TEXT_FAINT, Decoration.UNDERLINE),
        SpecSpan(" and ", 1u, ColorRole.TEXT_FAINT, Decoration.NONE),
        SpecSpan("gone", 1u, ColorRole.TEXT_FAINT, Decoration.STRIKETHROUGH),
    )),
    SpecLine(90f, listOf(SpecSpan("| name | stat |", 2u, ColorRole.CODE_TEXT, Decoration.NONE))),
    SpecLine(110f, listOf(SpecSpan("| 名称 | 状态 |", 2u, ColorRole.CODE_TEXT, Decoration.NONE))),
    SpecLine(130f, listOf(SpecSpan("| 〃〜.. | 😀ok |", 2u, ColorRole.CODE_TEXT, Decoration.NONE))),
    SpecLine(156f, listOf(SpecSpan("Sending this now…", 0u, ColorRole.TEXT_SECONDARY, Decoration.NONE))),
    SpecLine(180f, listOf(SpecSpan("Soft file name.rs", 1u, ColorRole.TEXT_SOFT, Decoration.UNDERLINE))),
)

/** All line strings concatenated — the runs' `start`/`len` index into it. */
private val SpecimenText: String =
    SpecimenLines.flatMap { it.spans }.joinToString("") { it.text }

/**
 * Builds the fixture [RowModel]: one layer-0 row (no boxes, scrollers,
 * links, widgets or fades), runs laid left to right from 16 dp per line.
 */
private fun fixtureModel(fonts: StyleFonts, d: Float): RowModel {
    val runs = ArrayList<TextRun>()
    var at = 0
    for (line in SpecimenLines) {
        var x = 16f
        for (span in line.spans) {
            val start = at
            val len = span.text.length
            at += len
            val width = spanWidth(fonts, span.style, start, at, d)
            runs.add(
                TextRun(
                    start.toUInt(),
                    len.toUInt(),
                    x,
                    line.baseline,
                    width,
                    span.style,
                    span.color,
                    span.decoration,
                    null,
                    null,
                ),
            )
            x += width
        }
    }
    return RowModel(
        RowDisplay(
            key = 1uL,
            version = 1uL,
            width = 360f,
            height = 200f,
            text = SpecimenText,
            runs = runs,
            boxes = emptyList(),
            links = emptyList(),
            scrollers = emptyList(),
            widgets = emptyList(),
            fades = emptyList(),
            copyText = "",
        ),
    )
}

/**
 * The painter's advance math, returned in pt: `FontChain.advance` for narrow
 * stretches, two cells per double-width cluster. This is Rust's job in
 * the real transcript; the fixture only needs `x` and `width` to agree
 * with what the painter draws.
 */
private fun spanWidth(fonts: StyleFonts, style: UShort, start: Int, end: Int, d: Float): Float {
    val entry = fonts[style]!!
    val paint = entry.paint
    val plan = if (FontChain.isMono(entry.face)) MonoWide.plan(SpecimenText, start, end) else MonoWide.NONE
    if (plan.isEmpty()) return FontChain.advance(paint, SpecimenText, start, end) / d
    val cell2 = 2f * FontChain.cellWidth(paint)
    var px = 0f
    var seg = start
    var k = 0
    while (k < plan.size) {
        val cs = plan[k]
        val ce = plan[k + 1]
        if (cs > seg) px += FontChain.advance(paint, SpecimenText, seg, cs)
        px += cell2
        seg = ce
        k += 2
    }
    if (seg < end) px += FontChain.advance(paint, SpecimenText, seg, end)
    return px / d
}

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "Text runs · light")
@Composable
private fun TextRunsSpecimenLightPreview() {
    RobocoTheme(Appearance.Light) { TextRunsSpecimen(dark = false) }
}

@Preview(name = "Text runs · dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TextRunsSpecimenDarkPreview() {
    RobocoTheme(Appearance.Dark) { TextRunsSpecimen(dark = true) }
}
