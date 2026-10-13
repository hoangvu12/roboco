package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "Wallpaper hero" entry of the design gallery (android-ui-parity #21):
 * the three mask fixtures plus the live store item. The registration line
 * in `design/Gallery.kt` (ticket 22's registry) is this ticket's own:
 *
 * `GallerySection("Wallpaper hero", screenshotHeightDp = 1400) { WallpaperHeroGallerySection() }`
 *
 * The section is about 1300 dp tall, so it needs 22's `screenshotHeightDp =
 * 1400` (the default 891 would cut it off in the gallery golden). Under a
 * gallery capture the live item draws nothing: Robolectric has no stored
 * wallpaper, so the store's `render` returns null and the hero emits no node.
 * The section contains no scroller and no unbounded child, so the default
 * `GalleryLayout.Scroll` applies. Every frame sits on `Palette.background`.
 */
@Composable
fun WallpaperHeroGallerySection(showLive: Boolean = true) {
    val palette = RobocoTheme.palette
    val density = LocalDensity.current
    val fixture = remember { wallpaperFixture() }
    val caption = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, color = palette.secondary)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Plain", style = caption)
        WallpaperArtwork(
            render = WallpaperRender(fixture, 1f),
            appear = { 1f },
            modifier = Modifier.size(360.dp, 300.dp),
        )

        BasicText("Opacity 0.6 · scrollFade 0.5", style = caption)
        WallpaperArtwork(
            render = WallpaperRender(fixture, 0.6f),
            appear = { 1f },
            scrollFade = { 0.5f },
            modifier = Modifier.size(360.dp, 300.dp),
        )

        BasicText("Cutout", style = caption)
        // Hero-local px, like the composer rect the new-session page passes.
        val cutout = with(density) { Rect(16.dp.toPx(), 180.dp.toPx(), 344.dp.toPx(), 232.dp.toPx()) }
        Box(Modifier.size(360.dp, 300.dp)) {
            WallpaperArtwork(
                render = WallpaperRender(fixture, 1f),
                appear = { 1f },
                cutout = { cutout },
                modifier = Modifier.matchParentSize(),
            )
            // A 1-px hairline outline of the same rect, drawn on top and
            // outside the hero, so the composer's position is visible.
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            color = palette.hairline,
                            topLeft = Offset(cutout.left, cutout.top),
                            size = Size(cutout.width, cutout.height),
                            style = Stroke(width = 1f),
                        )
                    },
            )
        }

        if (showLive) {
            BasicText("Live store (set one in Settings)", style = caption)
            WallpaperHero(modifier = Modifier.size(360.dp, 300.dp))
        }
    }
}

/**
 * A deterministic 600 × 400 wallpaper: four vertical colour bands
 * (`#E5484D`, `#F5A524`, `#30A46C`, `#3E63DD`) with 2 px white diagonal
 * stripes every 24 px, so the aspect-fill crop and the mask are easy to
 * see. Painted with the Compose canvas — no asset file is needed.
 */
internal fun wallpaperFixture(): ImageBitmap {
    val bitmap = ImageBitmap(width = 600, height = 400)
    val canvas = Canvas(bitmap)
    val fill = Paint()
    val bands = listOf(0xFFE5484D, 0xFFF5A524, 0xFF30A46C, 0xFF3E63DD)
    bands.forEachIndexed { index, band ->
        fill.color = Color(band)
        canvas.drawRect(index * 150f, 0f, (index + 1) * 150f, 400f, fill)
    }
    val stripe = Paint().apply {
        color = Color.White
        style = PaintingStyle.Stroke
        strokeWidth = 2f
    }
    var x = -400f
    while (x <= 600f) {
        canvas.drawLine(Offset(x, 0f), Offset(x + 400f, 400f), stripe)
        x += 24f
    }
    return bitmap
}

@Preview(name = "Wallpaper hero - Light")
@Composable
private fun WallpaperHeroGallerySectionLightPreview() {
    RobocoTheme(Appearance.Light) { WallpaperHeroGallerySection(showLive = false) }
}

@Preview(name = "Wallpaper hero - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WallpaperHeroGallerySectionDarkPreview() {
    RobocoTheme(Appearance.Dark) { WallpaperHeroGallerySection(showLive = false) }
}
