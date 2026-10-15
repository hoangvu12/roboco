package sh.roboco.android.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Overflow fades (android-ui-parity #10), ported from
 * `ios:Design/EdgeFade.swift`. Content that does not fit fades out at the
 * edge it runs past instead of being cut or ellipsized — an alpha mask, so
 * it works over glass and any background.
 *
 * `left`/`right` are always **physical** sides, like the Swift `leading`/
 * `trailing` parameters: the mask gradient runs physical left → right
 * (`EdgeFade.swift:8-9`) and never flips for RTL; callers pick the side.
 */
object EdgeFade {
    /** `EdgeFade.swift:7` — `static let width: CGFloat = 24`. */
    val Width: Dp = 24.dp

    /** FadingText: overflow only when the text is wider than the slot + this (iOS `+ 0.5`, `:43`). */
    val OverflowTolerance: Dp = 0.5.dp

    /** FadingHorizontalScroll: no mask unless max scroll exceeds this (iOS `maxX > 0.5`, `:72`). */
    val ScrollThreshold: Dp = 0.5.dp

    /**
     * The two opaque-stop locations of the horizontal alpha mask
     * (`EdgeFade.mask`, `:6-20`): `a = min(left/w, 0.5)`,
     * `b = max(1 − right/w, 0.5)`, `w = max(width, 1)`. Each fade is capped
     * at half the width, so the two ramps never cross; a 0 width is a hard
     * edge. Fractions of [width] in → fractions out.
     */
    fun maskStops(width: Float, left: Float, right: Float): Pair<Float, Float> {
        val w = max(width, 1f)
        val a = min(left / w, 0.5f)
        val b = max(1f - right / w, 0.5f)
        return a to b
    }

    /**
     * The mask itself: clear at 0, opaque at [maskStops]'s `a`, opaque at
     * `b`, clear at 1 — black/transparent because only alpha matters
     * (`EdgeFade.swift:10-11`). Horizontal over `0..width`.
     */
    fun horizontalMask(width: Float, left: Float, right: Float): Brush {
        val (a, b) = maskStops(width, left, right)
        return Brush.horizontalGradient(
            0f to Color.Transparent,
            a to Color.Black,
            b to Color.Black,
            1f to Color.Transparent,
            startX = 0f,
            endX = width,
        )
    }

    /**
     * The scroll ramp (`FadingScrollView.updateFade`, `:76-79`): each edge's
     * fade grows linearly with travel in that direction and is clamped to
     * `[0, fadeWidth]`, so it is 0 at rest on the start edge, 0 once scrolled
     * to the end, and never pops. Returns (startFade, endFade).
     */
    fun scrollFades(x: Float, maxX: Float, fadeWidth: Float): Pair<Float, Float> {
        if (fadeWidth <= 0f) return 0f to 0f
        val start = fadeWidth * (x / fadeWidth).coerceIn(0f, 1f)
        val end = fadeWidth * ((maxX - x) / fadeWidth).coerceIn(0f, 1f)
        return start to end
    }

    /**
     * Whether a scroller gets a mask at all: `maxValue` is `Int.MAX_VALUE`
     * before the first measure (treat as not measured), and content that
     * overflows by ≤ [EdgeFade.ScrollThreshold] gets none (`:72`).
     */
    fun scrollMasked(maxValue: Int, thresholdPx: Float): Boolean =
        maxValue != Int.MAX_VALUE && maxValue > thresholdPx

    /**
     * EdgeFadeOverlay stops, verbatim from `EdgeFade.swift:106`: (location,
     * alpha) of `Palette.background`, a smoothstep-ish ramp over the first
     * ~45 % then solid.
     */
    val OverlayStops: List<Pair<Float, Float>> = listOf(
        0f to 0f,
        0.12f to 0.18f,
        0.24f to 0.5f,
        0.36f to 0.82f,
        0.46f to 0.96f,
        0.55f to 1f,
        1f to 1f,
    )
}

/** Physical fade widths in px; `null` = no mask. */
@Immutable
data class HorizontalFades(val left: Float, val right: Float)

/**
 * Alpha-masks this node horizontally with the [EdgeFade] gradient, the port
 * of `layer.mask = CAGradientLayer` (`EdgeFade.swift:19`). The mask rect is
 * this node's bounds, so it stays fixed to the visible slot while content
 * moves under it.
 *
 * [masked] must return `true` exactly when [fades] would return non-null —
 * it is a separate lambda because the layer scope has no layout direction.
 * Both lambdas are called from the layer/draw phases only ([DrawScope] and
 * [GraphicsLayerScope][androidx.compose.ui.graphics.GraphicsLayerScope] are
 * both [Density]), so a state they read — scroll offset, overflow flag —
 * invalidates the layer or draw, never composition.
 */
fun Modifier.horizontalFadeMask(
    masked: Density.() -> Boolean,
    fades: Density.(LayoutDirection) -> HorizontalFades?,
): Modifier = this
    .graphicsLayer {
        // BlendMode.DstIn needs an offscreen layer; without one it punches
        // through to the window and erases whatever lies behind (glass,
        // wallpaper). iOS sets layer.mask = nil when idle, so Auto costs
        // nothing while unmasked (EdgeFade.swift:50-52).
        compositingStrategy = if (masked()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val f = fades(layoutDirection) ?: return@drawWithContent
        drawRect(EdgeFade.horizontalMask(size.width, f.left, f.right), blendMode = BlendMode.DstIn)
    }

/**
 * iOS `FadingScrollView`'s mask (`EdgeFade.swift:59-86`): the physical edges
 * fade while there is more content that way, ramping with travel. Compose
 * `horizontalScroll` mirrors in RTL (value 0 = right-hand start), so
 * start/end travel is mapped to the physical sides by layout direction —
 * no fade at rest on the leading edge in both directions.
 *
 * Scroll state (`value`, `maxValue`) is read only in the layer/draw phases:
 * scrolling never recomposes. **Put this BEFORE `horizontalScroll(state)`**
 * so the mask is fixed to the viewport, not the scrolling content.
 */
fun Modifier.horizontalScrollEdgeFade(
    state: ScrollState,
    fadeWidth: Dp = EdgeFade.Width,
): Modifier = horizontalFadeMask(
    masked = { EdgeFade.scrollMasked(state.maxValue, EdgeFade.ScrollThreshold.toPx()) },
    fades = { dir ->
        val max = state.maxValue
        if (!EdgeFade.scrollMasked(max, EdgeFade.ScrollThreshold.toPx())) {
            null
        } else {
            val fw = fadeWidth.toPx()
            val (start, end) = EdgeFade.scrollFades(state.value.toFloat(), max.toFloat(), fw)
            if (dir == LayoutDirection.Ltr) HorizontalFades(start, end) else HorizontalFades(end, start)
        }
    },
)

/** [horizontalScrollEdgeFade] plus the [horizontalScroll] itself, in that order. */
fun Modifier.fadingHorizontalScroll(
    state: ScrollState,
    fadeWidth: Dp = EdgeFade.Width,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
): Modifier = horizontalScrollEdgeFade(state, fadeWidth)
    .horizontalScroll(state, enabled, flingBehavior)
