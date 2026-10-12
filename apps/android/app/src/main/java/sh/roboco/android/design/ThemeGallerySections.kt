package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import sh.roboco.android.core.Face

// ---------------------------------------------------------------------------
// Debug design-gallery sections (registered by ticket 22; this file only
// draws them). Neither section scrolls vertically: the gallery frame does.
// ---------------------------------------------------------------------------

/** The label style the gallery chrome uses: Geist Mono at a fixed 12 dp. */
@Composable
private fun Label(text: String, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(12f), color), modifier = modifier)
}

/** A 4×4 checkerboard, so translucent swatches show their alpha. */
private fun DrawScope.checkerboard(size: Size) {
    val cell = size.minDimension / 4f
    for (y in 0..3) {
        for (x in 0..3) {
            drawRect(
                color = if ((x + y) % 2 == 0) rgb(0xEDEDED) else rgb(0xB4B4B4),
                topLeft = Offset(x * cell, y * cell),
                size = Size(cell, cell),
            )
        }
    }
}

private fun hex(color: Color): String =
    "#" + Integer.toString(color.toArgb(), 16).uppercase().padStart(8, '0')

/** One 32 dp palette row: swatch over checkerboard, name, light · dark hexes. */
@Composable
private fun PaletteRow(name: String, light: Color, dark: Color, palette: Palette) {
    val swatch = SmoothRoundedShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(swatch)
                .drawBehind { checkerboard(size) }
                .background(if (palette.isDark) dark else light)
                .hairlineBorder(palette.hairline, swatch),
        )
        Spacer(Modifier.width(12.dp))
        Label(name, palette.text, Modifier.weight(1f))
        Label("${hex(light)} · ${hex(dark)}", palette.secondary)
    }
}

@Composable
private fun GalleryHeader(text: String, palette: Palette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(top = 6.dp),
    ) {
        Label(text, palette.secondary)
    }
}

private val chromeRoleNames = listOf(
    "background", "rowActive", "elevated", "text", "secondary", "tertiary",
    "hairline", "accent", "controlFill", "accentSoft", "danger", "success",
    "warning", "userBubble", "codeBackground", "codeBorder", "chip", "subline",
)

private val statusToneNames = listOf("working", "input", "failed", "done", "idle", "time")

/**
 * The palette page of the design gallery: every chrome role, project tone
 * and status tone with its light and dark hex. ≈ 32 × 32 + 3 × 24 ≈ 1100 dp
 * tall, sized for the 1400 dp "Palette" shot.
 */
@Composable
fun PaletteGallerySection() {
    val palette = RobocoTheme.palette
    val light = Palette.Light
    val dark = Palette.Dark
    Column {
        GalleryHeader("chrome roles", palette)
        for (name in chromeRoleNames) {
            PaletteRow(name, light.roleByName(name), dark.roleByName(name), palette)
        }
        GalleryHeader("project tones", palette)
        for (i in 0 until Palette.PROJECT_TONE_COUNT) {
            PaletteRow(
                Palette.PROJECT_TONE_NAMES[i],
                light.projectTones[i],
                dark.projectTones[i],
                palette,
            )
        }
        GalleryHeader("status tones", palette)
        for (name in statusToneNames) {
            PaletteRow(
                name,
                light.statusToneByName(name),
                dark.statusToneByName(name),
                palette,
            )
        }
    }
}

private fun Palette.roleByName(name: String): Color = when (name) {
    "background" -> background
    "rowActive" -> rowActive
    "elevated" -> elevated
    "text" -> text
    "secondary" -> secondary
    "tertiary" -> tertiary
    "hairline" -> hairline
    "accent" -> accent
    "controlFill" -> controlFill
    "accentSoft" -> accentSoft
    "danger" -> danger
    "success" -> success
    "warning" -> warning
    "userBubble" -> userBubble
    "codeBackground" -> codeBackground
    "codeBorder" -> codeBorder
    "chip" -> chip
    "subline" -> subline
    else -> error("unknown role: $name")
}

private fun Palette.statusToneByName(name: String): Color = when (name) {
    "working" -> status.working
    "input" -> status.input
    "failed" -> status.failed
    "done" -> status.done
    "idle" -> status.idle
    "time" -> status.time
    else -> error("unknown status tone: $name")
}

/**
 * The shapes page of the design gallery: continuous vs circular corners,
 * the capsule short-circuit and hairlines.
 */
@Composable
fun ShapesGallerySection() {
    val palette = RobocoTheme.palette
    // 0.6 alpha through rgba, per the palette rule (never Color.copy).
    val dangerSoft = if (palette.isDark) rgba(0xF87171, 0.6) else rgba(0xDC2626, 0.6)
    Column {
        for (r in listOf(12, 16, 20, 26)) {
            Label("smooth $r dp + circular outline", palette.secondary)
            Box(
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .size(160.dp, 64.dp)
                    .background(palette.chip, SmoothRoundedShape(r.dp))
                    .hairlineBorder(dangerSoft, RoundedCornerShape(r.dp)),
            )
        }
        Label("capsule vs smooth 100 dp (identical)", palette.secondary)
        Row(Modifier.padding(bottom = 12.dp)) {
            Box(Modifier.size(160.dp, 44.dp).background(palette.chip, CapsuleShape))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(160.dp, 44.dp).background(palette.chip, SmoothRoundedShape(100.dp)))
        }
        Label("hairline horizontal / vertical", palette.secondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(160.dp).height(hairlineWidth()).background(palette.hairline))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(hairlineWidth()).height(64.dp).background(palette.hairline))
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (both sections in one RobocoTheme; dark follows the uiMode).
// ---------------------------------------------------------------------------

@Preview(name = "Theme - Light")
@Composable
private fun ThemeSectionsLightPreview() {
    RobocoTheme(Appearance.System) {
        Column {
            PaletteGallerySection()
            ShapesGallerySection()
        }
    }
}

@Preview(name = "Theme - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemeSectionsDarkPreview() {
    RobocoTheme(Appearance.System) {
        Column {
            PaletteGallerySection()
            ShapesGallerySection()
        }
    }
}
