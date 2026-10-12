package sh.roboco.android.design.gallery

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rgba
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassCrashGuard
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalGlassTier
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.rememberGlassProgress

/**
 * The "Glass surface" page of the debug design gallery (ticket 07): the
 * material in every configuration, over a scrollable haze source.
 *
 * Registered as "Glass surface" by ticket 22's registry
 * (`design/Gallery.kt`); until 22 lands this section stays unregistered.
 * The Light/Dark and Force-reduce-motion toggles come from 22's scaffold.
 * Monospace labels keep the file independent of ticket 03's type scale.
 *
 * The layout follows the per-screen Haze convention (`GlassHaze.kt`): the
 * scrolling stripes are the *only* `hazeSource`; every surface is a sibling
 * drawn after it, never inside it.
 */
@Composable
fun GlassGallerySection() {
    val palette = RobocoTheme.palette
    val context = LocalContext.current
    val guard = remember { GlassCrashGuard.get(context) }
    ProvideHazeState {
        Box(Modifier.fillMaxWidth()) {
            // 1. The source: 24 rows × 44 dp (1056 dp) scrolled in a 360 dp window.
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
                    .hazeSource(LocalHazeState.current!!),
            ) {
                for (i in 0 until 24) {
                    GalleryRow(i, palette)
                }
            }
            // 2. Chrome: siblings of the source, drawn after (over) it.
            Column(Modifier.fillMaxWidth()) {
                Label("Tier: ${LocalGlassTier.current} · API ${Build.VERSION.SDK_INT} · guard ${guard.tripped}", palette.text)
                ToggleLabel(
                    "Force fallback glass: ${if (Glass.forceFallback) "on" else "off"}",
                    palette.accent,
                ) { Glass.forceFallback = !Glass.forceFallback }
                Spacer(Modifier.height(12.dp))

                GlassSurface(
                    Modifier.size(200.dp, 44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label("Archived", palette.text)
                }
                Spacer(Modifier.height(12.dp))

                // Cards 160 × 64 at radii 18, 20, 25, 26 — Glass.shape(r.dp).
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (r in listOf(18, 20)) {
                        RadiusCard(r, palette)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (r in listOf(25, 26)) {
                        RadiusCard(r, palette)
                    }
                }
                Spacer(Modifier.height(12.dp))

                GlassSurface(
                    Modifier.size(200.dp, 44.dp),
                    interactive = true,
                    contentAlignment = Alignment.Center,
                ) {
                    Label("Press me", palette.text)
                }
                Spacer(Modifier.height(12.dp))

                GlassSurface(
                    Modifier.size(200.dp, 44.dp),
                    tint = palette.accent,
                    contentAlignment = Alignment.Center,
                ) {
                    Label("Tinted", palette.text)
                }
                Spacer(Modifier.height(12.dp))

                MaterializeDemo(palette)
            }
        }
    }
}

/** One 160 × 64 card of radius `r` dp, labelled with the radius. */
@Composable
private fun RadiusCard(r: Int, palette: Palette) {
    GlassSurface(
        Modifier.size(160.dp, 64.dp),
        shape = Glass.shape(r.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label("$r", palette.text)
    }
}

/**
 * The materialize demo: a hidden-start panel driven by a plain-text toggle,
 * with the live progress read out (`p = %.2f`). The caller-side
 * `rememberGlassProgress` mirrors the surface's own animation, the pattern
 * the ticket prescribes for callers that show something outside the surface.
 */
@Composable
private fun MaterializeDemo(palette: Palette) {
    var materialized by remember { mutableStateOf(false) }
    val p = rememberGlassProgress(materialized)
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassSurface(
            Modifier.size(160.dp, 64.dp),
            shape = Glass.shape(25.dp),
            visible = materialized,
            contentAlignment = Alignment.Center,
        ) {
            Label("Panel", palette.text)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            ToggleLabel("Materialize", palette.accent) { materialized = !materialized }
            Label("p = %.2f".format(p.value), palette.secondary)
        }
    }
}

/** One source row: alternating accent @0.35, text @0.12 and project tone stripes. */
@Composable
private fun GalleryRow(i: Int, palette: Palette) {
    Box(Modifier.fillMaxWidth().height(44.dp).background(galleryStripe(palette, i))) {
        Label(
            "Row $i",
            palette.text,
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp),
        )
    }
}

/** Stripes of `palette.accent` @0.35, `palette.text` @0.12 and `projectTone(i)`. */
private fun galleryStripe(palette: Palette, i: Int): Color = when (i % 3) {
    0 -> if (palette.isDark) rgba(0x8B7CF6, 0.35) else rgba(0x5B43E8, 0.35)
    1 -> if (palette.isDark) rgba(0xE8E8EA, 0.12) else rgba(0x27272C, 0.12)
    else -> palette.projectTone(i)
}

/** Monospace label style; swapped for Geist by ticket 03. */
private fun labelStyle(color: Color): TextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)

@Composable
private fun Label(text: String, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, style = labelStyle(color), modifier = modifier)
}

/** A plain-text toggle (no Material controls in the gallery). */
@Composable
private fun ToggleLabel(text: String, color: Color, onClick: () -> Unit) {
    BasicText(
        text,
        style = labelStyle(color),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}
