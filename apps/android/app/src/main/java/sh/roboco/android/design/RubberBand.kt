package sh.roboco.android.design

/**
 * UIKit `UIScrollView`'s rubber-band coefficient: the one value the
 * pull-to-refresh gap (48), the transcript overscroll (134) and the image
 * viewer's pan (125) share.
 */
const val UIScrollViewRubberBandC = 0.55f

/**
 * The `UIScrollView` rubber band `f(x) = (1 − 1/(x·c/d + 1))·d`: the
 * displayed offset for a non-negative overshoot [x] over the dimension
 * [d] > 0, saturating below `d`. Evaluated in the algebraically identical
 * stable form `x·c·d / (x·c + d)`: the literal `1 − 1/(u + 1)` cancels
 * catastrophically in `Float` for small `x` (at `x = 0.01, d = 800` its
 * slope reads 0.5531 instead of 0.55, and the per-frame deltas at the
 * start of a pull are exactly that small). Callers own their clamps and
 * signs.
 */
fun rubberBandOffset(x: Float, d: Float, c: Float = UIScrollViewRubberBandC): Float {
    val xc = x * c
    return xc * d / (xc + d)
}

/**
 * The inverse of [rubberBandOffset]: the overshoot that displays
 * [offset] over [d], `D·d / (c·(d − D))` for `0 ≤ D < d`. Callers keep
 * `D` below `d` (no finite travel reaches it).
 */
fun rubberBandTravel(offset: Float, d: Float, c: Float = UIScrollViewRubberBandC): Float =
    offset * d / (c * (d - offset))
