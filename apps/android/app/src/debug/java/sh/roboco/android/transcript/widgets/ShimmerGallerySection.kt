package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import sh.roboco.android.design.uiStyle
import sh.roboco.android.transcript.StyleFonts
import uniffi.roboco_core.ColorRole

/**
 * The "Shimmer" section of the debug design gallery (ticket 122): the
 * sweeping highlight over a live tool group's title, on the specimen's
 * real layer-0 fixture row (the summary title in `TEXT_SECONDARY` with
 * the `Shimmer` widget over it).
 *
 *  - **Live**: the real [LiveShimmer] with its own [ShimmerClock] — two
 *    sweeps per 3.4 s, honouring the gallery's "Force reduce motion"
 *    toggle (the sweep hides, the base title stays). Under the gallery's
 *    Roborazzi capture (`LocalGalleryCapture`) it is frozen at `t = 1/3`
 *    instead, so `GalleryScreenshotTest` goldens are deterministic.
 *  - below it, the frozen phase table `t = 0 … 0.8333` — each captioned —
 *    covering the quiet phases (t = 0), the k = 1 crossing (0.25 / 1/3 /
 *    0.4167) and the k = 0 crossing (0.8333).
 *
 * Registered in `Gallery.sections` as "Shimmer" (slug `shimmer`).
 */
@Composable
fun ShimmerGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ShimmerCaption("Live")
        ShimmerLiveSpecimen()
        ShimmerCaption("Frozen phases")
        FrozenShimmer(caption = "t = 0", phase = 0f)
        FrozenShimmer(caption = "t = 0.25", phase = 0.25f)
        FrozenShimmer(caption = "t = 1/3", phase = 1f / 3f)
        FrozenShimmer(caption = "t = 0.4167", phase = 0.4167f)
        FrozenShimmer(caption = "t = 0.8333", phase = 0.8333f)
    }
}

/** One frozen specimen under its caption: the phase table row. */
@Composable
private fun FrozenShimmer(caption: String, phase: Float) {
    ShimmerCaption(caption)
    ShimmerSpecimen(dark = RobocoTheme.isDark, phase = phase)
}

/**
 * The live specimen: the fixture's base layer plus the shimmer. A fresh
 * [ShimmerClock] per composition of this section (the gallery never
 * re-keys it), so the sweep runs from t = 0 and the whole 3.4 s rhythm —
 * two crossings and the quiet gap — is visible in ~7 s of watching.
 */
@Composable
private fun ShimmerLiveSpecimen() {
    val dark = RobocoTheme.isDark
    val capture = LocalGalleryCapture.current
    val context = LocalContext.current
    val d = LocalDensity.current.density
    val fonts = remember(context) {
        Fonts.load(context)
        StyleFonts()
    }
    val model = remember(fonts, d) { ShimmerSpecimen.model(fonts, d) }
    val palette = TranscriptPalette(dark)
    val widget = model.display.widgets[0]
    Box(Modifier.size(402.dp, 29.25.dp).background(RobocoTheme.palette.background)) {
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { c -> model.draw(0, c.nativeCanvas, d, palette, fonts) }
        }
        Box(
            Modifier
                .absoluteOffset(ShimmerSpecimen.TITLE_X.dp, ShimmerSpecimen.TITLE_Y.dp)
                .size(widget.w.dp, ShimmerSpecimen.LINE_HEIGHT.dp),
        ) {
            if (capture) {
                // 22's screenshot mode: the live specimen freezes at the
                // k = 1 peak, the same phase as the t = 1/3 golden.
                ShimmerLayer(
                    model = model,
                    x = widget.x,
                    y = widget.y,
                    w = widget.w,
                    h = widget.h,
                    fonts = fonts,
                    color = palette.color(ColorRole.TEXT),
                    phase = { 1f / 3f },
                )
            } else {
                LiveShimmer(
                    clock = remember { ShimmerClock() },
                    model = model,
                    w = widget,
                    fonts = fonts,
                    color = palette.color(ColorRole.TEXT),
                )
            }
        }
    }
}

/** A small section caption: Geist 13.5 dp fixed in `secondary`. */
@Composable
private fun ShimmerCaption(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13.5f), RobocoTheme.palette.secondary),
    )
}
