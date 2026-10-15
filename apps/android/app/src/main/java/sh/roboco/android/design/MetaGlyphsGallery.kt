package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face

/**
 * The fixture names, colorIndex 0…7 in order (the ticket's gallery list).
 * `"?"` is the literal empty-project tile.
 */
private val TILE_NAMES = listOf(
    "zeron", "edge", "MacBook Pro", "blog", "Home", "Zeron iOS", "roboco", "?",
)

/**
 * The "Meta glyphs" page of the design gallery (android-ui-parity #12):
 * the row meta line's static glyphs — [PrBadge] at its two sizes, [PrIcon]
 * with a 4× zoom, [ProjectTile] (composable and bitmap-painter forms) and
 * [BranchIcon].
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Meta glyphs") { MetaGlyphsGallerySection() }` — no
 * vertical scroller of its own: the gallery frame scrolls it.
 */
@Composable
fun MetaGlyphsGallerySection() {
    val palette = RobocoTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetaLabel("PrBadge — size 11")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrBadge(77u, PrState.Closed)
            PrBadge(102u, PrState.Open)
            PrBadge(412u, PrState.Merged)
            PrBadge(9u, PrState.Draft)
        }

        MetaLabel("PrBadge — size 14")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrBadge(77u, PrState.Closed, size = 14f)
            PrBadge(102u, PrState.Open, size = 14f)
            PrBadge(412u, PrState.Merged, size = 14f)
            PrBadge(9u, PrState.Draft, size = 14f)
        }

        MetaLabel("PrIcon — 12 dp, 48 dp (4×)")
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PrIcon(12.dp, palette.text)
            PrIcon(48.dp, palette.text)
        }

        MetaLabel("ProjectTile — sides 14 / 18 / 20 dp, colorIndex 0…7")
        for (side in listOf(14.dp, 18.dp, 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in TILE_NAMES.indices) {
                    ProjectTile(TILE_NAMES[i], i, side = side)
                }
            }
        }

        MetaLabel("ProjectTile — 56 dp (4× of 14)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in TILE_NAMES.indices) {
                ProjectTile(TILE_NAMES[i], i, side = 56.dp)
            }
        }

        MetaLabel("ProjectTile floorMod — 9 renders as 1, −1 as 7")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectTile("roboco", 1, side = 14.dp)
            ProjectTile("roboco", 9, side = 14.dp)
            ProjectTile("roboco", 7, side = 14.dp)
            ProjectTile("roboco", -1, side = 14.dp)
        }

        MetaLabel("ProjectTile bitmap painter — 14 dp (must match the 14 dp row)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in TILE_NAMES.indices) {
                Image(
                    painter = rememberProjectTilePainter(TILE_NAMES[i], i),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        MetaLabel("BranchIcon — 12 dp subline, 13 dp text (default)")
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchIcon(palette.subline, side = 12.dp)
            BranchIcon(palette.text)
        }
    }
}

/** Section caption: mono 11 pt in `secondary` (`#62626A` / `#A9A9AE`). */
@Composable
private fun MetaLabel(text: String) {
    BasicText(text, style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary))
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Meta glyphs light")
@Composable
private fun MetaGlyphsLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) { MetaGlyphsGallerySection() }
    }
}

@Preview(name = "Meta glyphs dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MetaGlyphsDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) { MetaGlyphsGallerySection() }
    }
}
