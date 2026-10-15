package sh.roboco.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import kotlin.math.min

/** The default squircle smoothing, matching iOS continuous corners' feel. */
const val DEFAULT_SMOOTHING = 0.6f

/**
 * iOS continuous ("squircle") corners: `layer.cornerCurve = .continuous` and
 * `UIBezierPath(roundedRect:cornerRadius:)`, built on graphics-shapes
 * `RoundedPolygon`.
 *
 * When the radius reaches half of the shorter side, iOS draws a plain
 * capsule — and the polygon would keep smoothing the *long* sides, producing
 * a "smoothed stadium" rather than a capsule (on 160×44 at r=22 the corner
 * runs 35.2 px along the long edge instead of 22). So the clamp hit returns
 * the exact capsule [Outline.Rounded] instead of the polygon.
 *
 * Marked immutable for skipping: the memoised outline below is a pure
 * function of the constructor parameters and the queried size, never of
 * external state.
 */
@Immutable
class SmoothRoundedShape(val radius: Dp, val smoothing: Float = DEFAULT_SMOOTHING) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(size.toRect())
        val r = smoothCornerRadiusPx(with(density) { radius.toPx() }, size.width, size.height)
        if (r <= 0f) return Outline.Rectangle(size.toRect())
        // (width, height, radius) fully determines the branch below, so the
        // cache is valid for any entry it returns.
        cache?.let { c ->
            if (c.width == size.width && c.height == size.height && c.radius == r) return c.outline
        }
        val outline = if (r >= min(size.width, size.height) / 2f) {
            Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        } else {
            Outline.Generic(polygonPath(size.width, size.height, r, smoothing))
        }
        cache = CacheEntry(size.width, size.height, r, outline)
        return outline
    }

    override fun equals(other: Any?): Boolean =
        other is SmoothRoundedShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode(): Int = 31 * radius.hashCode() + smoothing.hashCode()

    @Volatile
    private var cache: CacheEntry? = null

    private class CacheEntry(val width: Float, val height: Float, val radius: Float, val outline: Outline)
}

/** Exact circular ends, like iOS `.capsule()`. */
val CapsuleShape: Shape = RoundedCornerShape(percent = 50)

/** The clamped corner radius: at most half of the shorter side. */
internal fun smoothCornerRadiusPx(radiusPx: Float, width: Float, height: Float): Float =
    min(radiusPx, min(width, height) / 2f)

/**
 * The same continuous-corner path as [SmoothRoundedShape], as a plain
 * [Path] for Canvas painters (the wallpaper cutout, context menus). Applies
 * the same clamp and the same capsule short-circuit.
 */
fun smoothRoundedPath(width: Float, height: Float, radiusPx: Float, smoothing: Float = DEFAULT_SMOOTHING): Path {
    if (width <= 0f || height <= 0f) {
        return Path().apply { addRect(Rect(0f, 0f, width, height)) }
    }
    val r = smoothCornerRadiusPx(radiusPx, width, height)
    if (r <= 0f) return Path().apply { addRect(Rect(0f, 0f, width, height)) }
    if (r >= min(width, height) / 2f) {
        return Path().apply { addRoundRect(RoundRect(0f, 0f, width, height, CornerRadius(r))) }
    }
    return polygonPath(width, height, r, smoothing)
}

/** One shape instance per radius, sharing its outline cache. */
@Composable
fun rememberSmoothRoundedShape(radius: Dp): Shape = remember(radius) { SmoothRoundedShape(radius) }

/**
 * Builds the polygon rectangle and walks its cubics into a Compose [Path]
 * (the overload ticket 17's `SmoothTopRoundedShape` shares). See
 * [polygonPath].
 */
internal fun polygonPath(width: Float, height: Float, radiusPx: Float, smoothing: Float): Path =
    polygonPath(
        RoundedPolygon.rectangle(
            width = width,
            height = height,
            rounding = CornerRounding(radiusPx, smoothing),
            centerX = width / 2f,
            centerY = height / 2f,
        ),
    )

/**
 * Walks a [RoundedPolygon]'s cubics into a Compose [Path].
 * Platform-neutral by construction: graphics-shapes' own `toPath()` would
 * hand back an `android.graphics.Path`.
 */
internal fun polygonPath(polygon: RoundedPolygon): Path {
    val cubics = polygon.cubics
    val path = Path()
    if (cubics.isEmpty()) return path
    path.moveTo(cubics[0].anchor0X, cubics[0].anchor0Y)
    for (c in cubics) {
        path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
    }
    path.close()
    return path
}
