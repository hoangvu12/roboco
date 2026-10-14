package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face


/**
 * The "Icons" page of the design gallery (android-ui-parity #06): the whole
 * [RobocoIcons] registry grid, the weight and size ladders, [NumberCircle],
 * the "Measured vs iOS" reference row and the swipe-action colour blocks.
 *
 * Ticket 22 registers it (`GallerySection("Icons") { IconsGallerySection() }`);
 * this file contains **no vertical scroller of its own** — the gallery
 * frame scrolls it.
 */
@Composable
fun IconsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    Column(Modifier.padding(horizontal = 12.dp)) {
        GalleryLabel("registry — ${RobocoIcons.entries.size} glyphs", palette, typeScale)
        IconsGrid(palette, typeScale)
        Spacer(Modifier.height(20.dp))

        GalleryLabel("weight ladder — 17 pt", palette, typeScale)
        WeightLadder(RobocoIcons.Plus, palette, typeScale)
        Spacer(Modifier.height(8.dp))
        WeightLadder(RobocoIcons.Ellipsis, palette, typeScale)
        Spacer(Modifier.height(20.dp))

        GalleryLabel("size ladder — folder, hairline = layout box", palette, typeScale)
        SizeLadder(palette)
        Spacer(Modifier.height(20.dp))

        GalleryLabel("N.circle 17 pt + question options", palette, typeScale)
        NumberCircleRow(palette)
        Spacer(Modifier.height(20.dp))

        GalleryLabel("measured vs iOS — danger outline = iOS ink box", palette, typeScale)
        MeasuredVsIosRow(palette, typeScale)
        Spacer(Modifier.height(20.dp))

        GalleryLabel("swipe action colours — 74×62 dp, 22 dp glyphs", palette, typeScale)
        SwipeColourBlocks(palette)
        Spacer(Modifier.height(20.dp))
    }
}

/** Section header, the design gallery's monospace label style. */
@Composable
private fun GalleryLabel(text: String, palette: Palette, typeScale: TypeScale) {
    BasicText(text, style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary))
    Spacer(Modifier.height(8.dp))
}

/** Cell caption: the SF name (or constant name) under the glyph. */
@Composable
private fun Caption(text: String, palette: Palette, typeScale: TypeScale) {
    BasicText(text, style = uiStyle(Face.Sans, typeScale.fixed(9f), palette.secondary))
}

/**
 * Every registry entry at `Icon(17 pt, Regular, text)` on an `elevated`
 * card, 4 cells per 72 dp-wide cell row, the SF name captioned below.
 */
@Composable
private fun IconsGrid(palette: Palette, typeScale: TypeScale) {
    for (row in RobocoIcons.entries.chunked(4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in row) {
                IconCell(entry, palette, typeScale)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun IconCell(entry: RegistryEntry, palette: Palette, typeScale: TypeScale) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .background(palette.elevated, SmoothRoundedShape(8.dp))
            .padding(top = 10.dp, start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(entry.icon, palette.text, 17f, SymbolWeight.Regular)
        Spacer(Modifier.height(6.dp))
        Caption(entry.label, palette, typeScale)
    }
}

/** One glyph at 17 pt across the whole [SymbolWeight] ladder. */
@Composable
private fun WeightLadder(icon: RobocoIcon, palette: Palette, typeScale: TypeScale) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (weight in SymbolWeight.entries) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, palette.text, 17f, weight)
                Spacer(Modifier.height(2.dp))
                Caption(weight.name, palette, typeScale)
            }
        }
    }
}

/** Folder at every call-site point size, each layout box hairlined. */
@Composable
private fun SizeLadder(palette: Palette) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (sizePt in listOf(8f, 10f, 11f, 11.5f, 12f, 13f, 14f, 15f, 16f, 17f, 20f, 22f)) {
            Box(
                Modifier
                    .size(iconBoxDp(sizePt))
                    .hairlineBorder(palette.tertiary, RectangleShape),
            ) {
                Icon(RobocoIcons.Folder, palette.text, sizePt)
            }
        }
    }
}

/** `NumberCircle` 1…12 and 50 at 17 pt, plus the question-option glyphs. */
@Composable
private fun NumberCircleRow(palette: Palette) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (n in 1..12) {
            NumberCircle(n, palette.text, 17f)
        }
        NumberCircle(50, palette.text, 17f)
        Icon(RobocoIcons.CheckmarkCircleFill, palette.accent, 17f)
        Icon(RobocoIcons.CheckmarkSquareFill, palette.accent, 17f)
    }
}

/**
 * One worked example from the ticket: the glyph at its iOS call-site size,
 * weight and tint, with a 1 px `danger` outline of the **measured iOS ink
 * box** (±0.9 pt measurement error) centred on the glyph. The ink should
 * fill the outline within ±1 dp — checked visually against the golden and
 * the `1-off.png` / `2-settings.png` reference crops.
 */
private class IosMeasuredSample(
    val label: String,
    val icon: RobocoIcon,
    val sizePt: Float,
    val weight: SymbolWeight,
    val tint: (Palette) -> Color,
    val inkWidth: Float,
    val inkHeight: Float,
)

private fun measured(
    label: String,
    icon: RobocoIcon,
    sizePt: Float,
    weight: SymbolWeight,
    tint: (Palette) -> Color,
    inkWidth: Float,
    inkHeight: Float,
) = IosMeasuredSample(label, icon, sizePt, weight, tint, inkWidth, inkHeight)

/** The worked-examples table, ink boxes in pt (= dp). */
private val measuredSamples = listOf(
    measured("plus 15 sem", RobocoIcons.Plus, 15f, SymbolWeight.Semibold, Palette::accent, 12.4f, 12.4f),
    measured("checkmark 17 sem", RobocoIcons.Checkmark, 17f, SymbolWeight.Semibold, Palette::accent, 14.8f, 14.8f),
    measured("chevron.down 11 sem", RobocoIcons.ChevronDown, 11f, SymbolWeight.Semibold, Palette::tertiary, 9.6f, 5.2f),
    measured("chevron.right 13 sem", RobocoIcons.ChevronRight, 13f, SymbolWeight.Semibold, Palette::tertiary, 7f, 11.3f),
    measured("chevron.backward 17 sem", RobocoIcons.ChevronBackward, 17f, SymbolWeight.Semibold, Palette::text, 10.7f, 18.3f),
    measured("ellipsis 17 med", RobocoIcons.Ellipsis, 17f, SymbolWeight.Medium, Palette::text, 20f, 4.3f),
    measured("circle.lefthalf 20", RobocoIcons.CircleLeftHalfFilled, 20f, SymbolWeight.Regular, Palette::secondary, 21.7f, 21.7f),
    measured("magnifier 22", RobocoIcons.Magnifyingglass, 22f, SymbolWeight.Regular, Palette::text, 23.5f, 23.5f),
)

@Composable
private fun MeasuredVsIosRow(palette: Palette, typeScale: TypeScale) {
    for (row in measuredSamples.chunked(4)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            for (sample in row) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MeasuredSampleCell(sample, palette)
                    Spacer(Modifier.height(2.dp))
                    Caption(sample.label, palette, typeScale)
                }
            }
        }
    }
}

@Composable
private fun MeasuredSampleCell(sample: IosMeasuredSample, palette: Palette) {
    val box = iconBoxDp(sample.sizePt)
    val danger = palette.danger
    Box(
        Modifier
            .size(box)
            .drawBehind {
                val w = sample.inkWidth.dp.toPx()
                val h = sample.inkHeight.dp.toPx()
                drawRect(
                    color = danger,
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = Size(w, h),
                    style = Stroke(1f),
                )
            },
    ) {
        Icon(sample.icon, sample.tint(palette), sample.sizePt, sample.weight)
    }
}

/**
 * The swipe-action glyphs (white, 22 dp boxes per android-app#20) on
 * 74×62 dp plates of the three swipe colours: `accent`, `secondary` and
 * the #5E6AD2 blue (ticket 44's plates).
 */
@Composable
private fun SwipeColourBlocks(palette: Palette) {
    val swipeGlyphs = listOf(
        RobocoIcons.PinFill,
        RobocoIcons.PinSlash,
        RobocoIcons.ArchiveboxFill,
        RobocoIcons.FolderFill,
        RobocoIcons.TrayAndArrowUpFill,
    )
    val swipeBlue = rgb(0x5E6AD2)
    for (plate in listOf(palette.accent, palette.secondary, swipeBlue)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            for (glyph in swipeGlyphs) {
                Box(
                    Modifier
                        .size(74.dp, 62.dp)
                        .background(plate, SmoothRoundedShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconInBox(glyph, Color.White, 22.dp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Icons light")
@Composable
private fun IconsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { IconsGallerySection() }
}

@Preview(name = "Icons dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun IconsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { IconsGallerySection() }
}
