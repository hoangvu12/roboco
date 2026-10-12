package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.ceil
import kotlin.math.min

/**
 * The design gallery's deterministic busy backdrop (android-ui-parity #22):
 * a striped fake list on the left half and a procedural "photo" on the
 * right, with diagonal stripes over everything. High-frequency edges,
 * saturated colour and both light and dark areas under any glass placed on
 * it — and identical on every run, so goldens stay byte-stable.
 *
 * Lives in main so component `@Preview`s (tickets 08, 14, …) can use it; it
 * is inert drawing code and R8 drops it from release when unused.
 */
@Composable
fun GalleryBackdrop(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    // Built once and reset per draw; the two ridges are size-dependent paths.
    val ridgeFar = remember { Path() }
    val ridgeNear = remember { Path() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val half = w / 2f

        // 1. Base.
        drawRect(palette.background)

        // 2. Striped fake list (left half): rows at a 36 dp pitch.
        val pitch = 36.dp.toPx()
        val rows = ceil(h / pitch).toInt()
        for (i in 0 until rows) {
            val top = i * pitch
            drawRect(
                if (i % 2 == 0) palette.elevated else palette.chip,
                topLeft = Offset(0f, top),
                size = Size(half, pitch),
            )
            drawCircle(
                palette.projectTone(i),
                radius = 10.dp.toPx(),
                center = Offset(22.dp.toPx(), top + 18.dp.toPx()),
            )
            val bar1Width = min(96.dp.toPx(), half - 52.dp.toPx())
            if (bar1Width > 0f) {
                drawRoundRect(
                    palette.text,
                    topLeft = Offset(40.dp.toPx(), top + 9.dp.toPx()),
                    size = Size(bar1Width, 8.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            val bar2Width = min(64.dp.toPx(), half - 52.dp.toPx())
            if (bar2Width > 0f) {
                drawRoundRect(
                    palette.secondary,
                    topLeft = Offset(40.dp.toPx(), top + 21.dp.toPx()),
                    size = Size(bar2Width, 6.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }

        // 3. Procedural "photo" (right half): fixed colours, like a real photo.
        drawRect(
            Brush.verticalGradient(
                0f to rgb(0xF59E0B),
                0.5f to rgb(0xDB2777),
                1f to rgb(0x4C1D95),
                startY = 0f,
                endY = h,
            ),
            topLeft = Offset(half, 0f),
            size = Size(half, h),
        )
        drawCircle(
            rgb(0xFDE68A),
            radius = 0.12f * min(half, h),
            center = Offset(half + 0.62f * half, 0.30f * h),
        )
        ridgeFar.reset()
        ridgeFar.moveTo(half, 0.78f * h)
        ridgeFar.lineTo(half + 0.35f * half, 0.52f * h)
        ridgeFar.lineTo(half + 0.70f * half, 0.80f * h)
        ridgeFar.lineTo(w, 0.62f * h)
        ridgeFar.lineTo(w, h)
        ridgeFar.lineTo(half, h)
        ridgeFar.close()
        drawPath(ridgeFar, rgb(0x312E81))
        ridgeNear.reset()
        ridgeNear.moveTo(half, 0.90f * h)
        ridgeNear.lineTo(half + 0.25f * half, 0.70f * h)
        ridgeNear.lineTo(half + 0.55f * half, 0.92f * h)
        ridgeNear.lineTo(half + 0.80f * half, 0.76f * h)
        ridgeNear.lineTo(w, 0.88f * h)
        ridgeNear.lineTo(w, h)
        ridgeNear.lineTo(half, h)
        ridgeNear.close()
        drawPath(ridgeNear, rgb(0x1E1B4B))

        // 4. Diagonal 45° stripes over everything (bottom-left to top-right).
        val stripe = rgba(if (palette.isDark) 0xE8E8EA else 0x27272C, 0x29 / 255.0)
        val strokeWidth = 4.dp.toPx()
        val step = 24.dp.toPx()
        clipRect {
            var k = -h
            while (k < w) {
                drawLine(stripe, Offset(k, h), Offset(k + h, 0f), strokeWidth = strokeWidth)
                k += step
            }
        }
    }
}

/**
 * A full-width stage for glass demos: the [GalleryBackdrop] fills the box
 * and [content] sits on top of it as a **sibling** of the backdrop's
 * drawing node, never inside it.
 *
 * Glass sections pass `backdropModifier = Modifier.hazeSource(state)`
 * (ticket 07): the source modifier lands on the backdrop itself, so the
 * glass sibling never sits inside the node that carries `hazeSource`.
 */
@Composable
fun GlassStage(
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    backdropModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        GalleryBackdrop(Modifier.matchParentSize().then(backdropModifier))
        content()
    }
}

// ---------------------------------------------------------------------------
// Previews: the backdrop alone in light and dark, so the stripes, the photo
// (sun + two ridges) and the diagonal overlay can be checked at a glance.
// ---------------------------------------------------------------------------

@Preview(name = "Backdrop light")
@Composable
private fun BackdropLightPreview() {
    RobocoTheme(Appearance.System) {
        GlassStage(height = 320.dp) {}
    }
}

@Preview(name = "Backdrop dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackdropDarkPreview() {
    RobocoTheme(Appearance.System) {
        GlassStage(height = 320.dp) {}
    }
}
