package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview

/**
 * The "Edge fades" entry of the design gallery (android-ui-parity #10): one
 * fixture per behaviour of [FadingText], [FadingHorizontalScroll] and
 * [EdgeFadeOverlay]. Ticket 22 owns the registration line in
 * `design/Gallery.kt`:
 *
 * `GallerySection("Edge fades", screenshotHeightDp = 1100) { EdgeFadeGallerySection() }`
 *
 * The section contains no unbounded vertical scroller (default
 * `GalleryLayout.Scroll`); every item sits on `Palette.background`. The
 * fixtures use `FontFamily.Default` 16.sp on purpose, so the goldens do not
 * depend on ticket 03.
 */
@Composable
fun EdgeFadeGallerySection() {
    val palette = RobocoTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background),
    ) {
        val text = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, color = palette.text)

        // 1-2 · FadingText that fits: aligned per fitsAlignment, no mask.
        Caption("1 · FadingText fits, start", palette)
        FadingText("Short title", text, Modifier.width(240.dp))
        Caption("2 · FadingText fits, centred", palette)
        FadingText("Short title", text, Modifier.width(240.dp), fitsAlignment = Alignment.CenterHorizontally)

        // 3-5 · Overflowing: the head stays at the start edge, the tail fades.
        Caption("3 · FadingText overflow, LTR", palette)
        FadingText("Refactor the session list so long titles fade instead of ellipsizing", text, Modifier.width(240.dp))
        Caption("4 · FadingText overflow, RTL", palette)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            FadingText("Refactor the session list so long titles fade instead of ellipsizing", text, Modifier.width(240.dp))
        }
        Caption("5 · FadingText overflow, 40dp slot (fade capped at half)", palette)
        FadingText("Refactor the session list so long titles fade instead of ellipsizing", text, Modifier.width(40.dp))

        // 6 · Inline content (the session row meta line's project tile).
        Caption("6 · FadingText with inline content", palette)
        val inlineContent = mapOf(
            "tile" to InlineTextContent(Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.Center)) {
                Box(Modifier.size(14.dp).background(palette.accent))
            },
        )
        val metaLine = buildAnnotatedString {
            appendInlineContent("tile", "[]")
            append("  roboco   main branch with a long name")
        }
        FadingText(metaLine, text, Modifier.width(120.dp), inlineContent = inlineContent)

        // 7 · Only the text before the first hard line break.
        Caption("7 · FadingText shows the first line only", palette)
        FadingText("First line\nsecond line", text, Modifier.width(240.dp))

        // 8 · The scroll ramp: rest, 12dp, 100dp, end.
        Caption("8 · FadingHorizontalScroll: rest · 12dp · 100dp · end", palette)
        ScrollChipRow("rest", 0, palette)
        ScrollChipRow("12dp", 12, palette)
        ScrollChipRow("100dp", 100, palette)
        ScrollChipRow("end", Int.MAX_VALUE, palette)

        // 9 · Content that fits: no mask at all.
        Caption("9 · FadingHorizontalScroll that fits: no fade", palette)
        FadingHorizontalScroll(Modifier.width(240.dp)) {
            repeat(3) { i -> GalleryChip(i, palette) }
        }

        // 10 · The painted overlay, clear → background.
        Caption("10 · EdgeFadeOverlay over stripes", palette)
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            StripedBlock(Modifier.matchParentSize(), palette)
            EdgeFadeOverlay(Modifier.matchParentSize())
        }
    }
}

/** Caption style: `BasicText`, default family, 11.sp, `Palette.tertiary`. */
private fun captionStyle(palette: Palette): TextStyle =
    TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, color = palette.tertiary)

/** A small caption in `Palette.tertiary` (`BasicText` 11.sp, default family). */
@Composable
private fun Caption(text: String, palette: Palette) {
    BasicText(
        text,
        style = captionStyle(palette),
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * One static scroller state: 10 chips in a 240dp viewport. `initial` is in
 * px; `Int.MAX_VALUE` is clamped to `maxValue` once measured, which is the
 * scrolled-to-the-end state.
 */
@Composable
private fun ScrollChipRow(label: String, initialDp: Int, palette: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val density = LocalDensity.current
        val state = rememberScrollState(with(density) { initialDp.dp.roundToPx() })
        FadingHorizontalScroll(
            modifier = Modifier.width(240.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(10) { i -> GalleryChip(i, palette) }
        }
        BasicText(
            label,
            style = captionStyle(palette),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** A 28dp-tall capsule with the chip fill and the chip label. */
@Composable
private fun GalleryChip(index: Int, palette: Palette) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(palette.chip)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Chip $index",
            style = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, color = palette.text),
        )
    }
}

/** Alternating `Palette.text` / `Palette.accent` 10dp bars, the overlay's backdrop. */
@Composable
private fun StripedBlock(modifier: Modifier = Modifier, palette: Palette) {
    Column(modifier) {
        repeat(12) { i ->
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(if (i % 2 == 0) palette.text else palette.accent),
            )
        }
    }
}

@Preview(name = "Edge fades - Light")
@Composable
private fun EdgeFadeGallerySectionLightPreview() {
    RobocoTheme(Appearance.Light) { EdgeFadeGallerySection() }
}

@Preview(name = "Edge fades - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EdgeFadeGallerySectionDarkPreview() {
    RobocoTheme(Appearance.Dark) { EdgeFadeGallerySection() }
}
