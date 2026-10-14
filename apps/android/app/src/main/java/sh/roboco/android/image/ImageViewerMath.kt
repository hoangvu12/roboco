package sh.roboco.android.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import sh.roboco.android.design.UIScrollViewRubberBandC
import sh.roboco.android.design.rubberBandOffset

/**
 * The image viewer's pure geometry (android-ui-parity #125): the port of
 * iOS `ImageViewer.swift`'s `UIScrollView` maths — the aspect-fit/fill
 * rects, the whole-view pan bounds (letterbox included), the 2.5×
 * double-tap target, the log-space half-resistance zoom overshoot, the
 * UIScrollView rubber band and the drag-to-dismiss numbers. Pure
 * functions over px floats, no Compose runtime, pinned by
 * [sh.roboco.android.image.ImageViewerMathTest] against the ticket's
 * worked values.
 *
 * Coordinate system: `screen = content * z + T` where the content is the
 * **whole** `W x H` view (`imageView.frame = scroll.bounds`,
 * `contentSize = bounds.size`), not just the picture — at zoom `z` the
 * user can pan into the letterbox bands, exactly like the iOS source.
 */

/** `scroll.minimumZoomScale`, the UIKit default. */
const val ViewerMinZoom = 1f

/** `scroll.maximumZoomScale` (ImageViewer.swift:26). */
const val ViewerMaxZoom = 5f

/** The double-tap rect is W/2.5 x H/2.5, so the scale becomes 2.5. */
const val ViewerDoubleTapZoom = 2.5f

/** UIScrollView's rubber-band coefficient (c = 0.55): the shared [UIScrollViewRubberBandC]. */
const val ViewerRubberBandC = UIScrollViewRubberBandC

/** Drag-to-dismiss distance threshold: 100 dp of vertical drag. */
const val ViewerDismissDistanceDp = 100f

/** Drag-to-dismiss velocity threshold: 800 dp/s at release. */
const val ViewerDismissVelocityDpPerS = 800f

/** The picture's shrink at full dismiss progress (`k = 1 − 0.15·p`). */
const val ViewerDismissShrink = 0.15f

/**
 * The aspect-**fit** rect of an `img` in a `view` (`.scaleAspectFit`):
 * scale `min(W/iw, H/ih)`, centred — insets ignored
 * (`contentInsetAdjustmentBehavior = .never`).
 */
fun fitRect(img: Size, view: Size): Rect {
    if (view.width <= 0f || view.height <= 0f) return Rect.Zero
    val iw = img.width.coerceAtLeast(1f)
    val ih = img.height.coerceAtLeast(1f)
    val s = min(view.width / iw, view.height / ih)
    val dw = iw * s
    val dh = ih * s
    return Rect(
        left = (view.width - dw) / 2f,
        top = (view.height - dh) / 2f,
        right = (view.width + dw) / 2f,
        bottom = (view.height + dh) / 2f,
    )
}

/**
 * The aspect-**fill** rect of an `img` over a `clip` rect: scale
 * `max(clip.w/iw, clip.h/ih)`, centred on `clip` — the zoom transition's
 * crop inside the flying clip rect (the tile shows a Crop tile, and the
 * transition must not letterbox inside it).
 */
fun fillRect(img: Size, clip: Rect): Rect {
    if (clip.width <= 0f || clip.height <= 0f) return clip
    val iw = img.width.coerceAtLeast(1f)
    val ih = img.height.coerceAtLeast(1f)
    val s = max(clip.width / iw, clip.height / ih)
    val dw = iw * s
    val dh = ih * s
    val cx = clip.center.x
    val cy = clip.center.y
    return Rect(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
}

/** Component-wise rect lerp (`C = lerp(S, E, t)` of the zoom transition). */
fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left = a.left + (b.left - a.left) * t,
    top = a.top + (b.top - a.top) * t,
    right = a.right + (b.right - a.right) * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)

/**
 * The pan bounds at zoom `z`: `Tx in [W − W·z, 0]`, `Ty in [H − H·z, 0]`
 * — the whole `W x H` content, letterbox bands included.
 */
fun clampT(t: Offset, z: Float, view: Size): Offset = Offset(
    x = t.x.coerceIn(view.width - view.width * z, 0f),
    y = t.y.coerceIn(view.height - view.height * z, 0f),
)

/** `screen = content·z + T` applied to the picture's fit rect `F`. */
fun zoomedRect(fit: Rect, z: Float, t: Offset): Rect = Rect(
    left = fit.left * z + t.x,
    top = fit.top * z + t.y,
    right = fit.right * z + t.x,
    bottom = fit.bottom * z + t.y,
)

/**
 * The double-tap target, exactly `ImageViewer.swift:59-67`:
 * `z > 1` → back to `(1, (0, 0))`; otherwise zoom to 2.5× on a
 * `(W/2.5) x (H/2.5)` rect centred on the tap point (in content
 * coordinates), with the offset clamped to the content.
 *
 * Worked values (`W = 400, H = 800`): tap `(100, 200)` → `(2.5, (−50,
 * −100))`; tap `(10, 10)` → `(2.5, (0, 0))`; tap `(390, 790)` →
 * `(2.5, (−600, −1200))`; any tap at `z = 3` → `(1, (0, 0))`.
 */
fun doubleTapTarget(z: Float, t: Offset, tap: Offset, view: Size): Pair<Float, Offset> {
    if (z > 1f) return 1f to Offset.Zero
    val p = if (z > 0f) (tap - t) / z else tap - t
    val rw = view.width / ViewerDoubleTapZoom
    val rh = view.height / ViewerDoubleTapZoom
    val zNew = min(view.width / rw, view.height / rh).coerceIn(ViewerMinZoom, ViewerMaxZoom)
    val target = clampT(
        Offset(view.width / 2f - zNew * p.x, view.height / 2f - zNew * p.y),
        zNew,
        view,
    )
    return zNew to target
}

/**
 * The rubber-banded **displayed** zoom for a raw pinch product `zRaw`:
 * log-space half resistance past either limit — `z = min·(zRaw/min)^0.5`
 * below, `z = max·(zRaw/max)^0.5` above, identity in between. So a pinch
 * to 0.5× shows 0.707×, 8× shows 6.32×; release settles to the limit.
 */
fun rubberZoom(zRaw: Float, min: Float = ViewerMinZoom, max: Float = ViewerMaxZoom): Float = when {
    zRaw < min -> min * sqrt(zRaw / min)
    zRaw > max -> max * sqrt(zRaw / max)
    else -> zRaw
}

/**
 * The UIScrollView rubber band `rb(x, dim) = (1 − 1/(x·0.55/dim + 1))·dim`,
 * sign-preserving: `x` is the overshoot in px (either sign), `dim` the
 * pan dimension (`W` or `H`). The magnitude is the shared stable
 * [rubberBandOffset] (`x·c·d / (x·c + d)`; the literal `1 − 1/(u + 1)`
 * cancels catastrophically in `Float` for small `x`).
 */
fun rubberBand(overshootPx: Float, dimPx: Float): Float {
    if (overshootPx == 0f || dimPx <= 0f) return 0f
    return rubberBandOffset(abs(overshootPx), abs(dimPx)) * sign(overshootPx)
}

/** Dismiss progress `p = min(1, |dy| / (0.5·H))`. */
fun dismissProgress(dyPx: Float, viewH: Float): Float {
    if (viewH <= 0f) return 0f
    return min(1f, abs(dyPx) / (0.5f * viewH))
}

/**
 * Release decision: `|dy| > 100 dp` **or** `|vy| > 800 dp/s`
 * (`vy` from a `VelocityTracker`; `density` = px per dp).
 */
fun shouldDismiss(dyPx: Float, vyPxPerS: Float, density: Float): Boolean =
    abs(dyPx) > ViewerDismissDistanceDp * density ||
        abs(vyPxPerS) > ViewerDismissVelocityDpPerS * density

/**
 * The drag-to-dismiss / predictive-back picture transform: scale
 * `1 − 0.15·p` about the window `center` (down to 0.85), then translate
 * by `offset` (the drag offset; the back gesture passes zero).
 */
fun dismissTransform(rect: Rect, center: Offset, p: Float, offset: Offset): Rect {
    val k = 1f - ViewerDismissShrink * p
    return Rect(
        left = center.x + (rect.left - center.x) * k + offset.x,
        top = center.y + (rect.top - center.y) * k + offset.y,
        right = center.x + (rect.right - center.x) * k + offset.x,
        bottom = center.y + (rect.bottom - center.y) * k + offset.y,
    )
}
