package sh.roboco.android.transcript

import kotlin.math.exp
import sh.roboco.android.design.Motion
import sh.roboco.android.design.UIScrollViewRubberBandC
import sh.roboco.android.design.rubberBandOffset
import sh.roboco.android.design.rubberBandTravel

/**
 * The transcript's scroll physics constants and pure maths (ticket 134):
 * the UIKit decay curve, the rubber band, the bounce spring and the
 * vertical scroll indicator's geometry (decision TRANSCRIPT-D3). Pure
 * functions over floats — unit-testable on the plain JVM, pinned by
 * [ScrollPhysicsTest] against the ticket's reference tables.
 *
 * UIScrollView holds no formula for its rubber band or its bounce
 * spring in the app source (`ios:Transcript/TranscriptListView.swift`
 * sets only `alwaysBounceVertical`), so the band and spring values are
 * the widely used UIKit reproduction from the parent epic
 * (android-app#16 lines 211-215): band coefficient 0.55 over the
 * viewport height, and a critically damped spring with stiffness 400
 * (ζ = 1, ω = 20 rad/s). Like 68's tail-spring formula, the bounce is
 * an exact per-frame ODE step, not a Compose `spring()`: it must
 * re-read a moving target every frame and survive `MotionDurationScale`
 * 0, which a spring spec cannot (FOUNDATION-D1 covers row tweening, not
 * this scroll math). No value here is pasted elsewhere; the indicator
 * geometry is consumed by [scrollIndicatorThumb].
 */

/** UIScrollView.DecelerationRate.normal: velocity retained per millisecond. */
const val IOS_DECELERATION_RATE = 0.998f

/** k = −1000·ln(0.998) s⁻¹; v(t) = v₀·e^(−k·t) (the lab's own model, TLV-lab:279-280). */
const val IOS_DECAY_K = 2.0020027f

/** Compose exponentialDecay friction is −4.2 × multiplier per second; k / 4.2. */
const val IOS_FRICTION_MULTIPLIER = 0.4766673f

/** Stop when the remaining travel v/k is below half a pixel (px/s). */
const val IOS_FLING_STOP_PX_PER_S = 0.5f * IOS_DECAY_K

/** UIKit rubber-band coefficient (android-app#16 line 211): the shared [UIScrollViewRubberBandC]. */
const val RUBBER_BAND_C = UIScrollViewRubberBandC

/** Bounce spring: dampingRatio 1, stiffness 400 → ω = 20 (android-app#16 line 214): [Motion.TranscriptBounce]. */
val BOUNCE_OMEGA: Float = Motion.TranscriptBounce.naturalFrequency.toFloat()

/**
 * The band's visible displacement for a raw finger travel [x] past an
 * edge over a viewport height [d]: `f(x) = (1 − 1/(x·c/d + 1))·d`. The
 * slope at 0 is `c` (the content first moves at 55 % of finger speed)
 * and the displacement never reaches `d`. `x ≥ 0`; `d > 0`.
 *
 * Evaluated in the algebraically identical form `x·c·d / (x·c + d)`:
 * the ticket's literal `1 − 1/(u + 1)` cancels catastrophically in
 * `Float` for small `x` (at `x = 0.01, d = 800` it gives a slope of
 * 0.5531 instead of 0.55 — ticket 134's acceptance bound is 1e-3), and
 * the per-frame deltas at the start of a pull are exactly that small.
 * The f(x) table values are unchanged to 1e-4.
 */
fun rubberBand(x: Float, d: Float): Float = rubberBandOffset(x, d)

/**
 * The inverse of [rubberBand]: the raw finger travel that produces
 * [displacement] over viewport height [d] —
 * `f⁻¹(D) = D·d / (c·(d − D))` for `0 ≤ D < d`. The denominator is
 * clamped away from zero for `D ≥ d` (where no finite travel exists);
 * callers only invert within `0.5·d` (ticket B2), far from the clamp.
 */
fun rubberBandInverse(displacement: Float, d: Float): Float =
    rubberBandTravel(displacement.coerceAtMost(d * 0.999f), d)

/**
 * One exact step of the critically damped bounce toward 0 (ζ = 1,
 * ω = [BOUNCE_OMEGA]), the flow map of `x'' + 2ωx' + ω²x = 0`:
 * `E = e^(−ω·dt)`, `b = v + ω·x`, returns `(x', v')` =
 * `((x + b·dt)·E, (v − ω·b·dt)·E)`. The step is exact, so the discrete
 * sequence equals the analytic solution at the frame times.
 */
fun bounceStep(x: Float, v: Float, dt: Float): Pair<Float, Float> {
    val e = exp(-BOUNCE_OMEGA * dt)
    val b = v + BOUNCE_OMEGA * x
    return ((x + b * dt) * e) to ((v - BOUNCE_OMEGA * b * dt) * e)
}

// ---------------------------------------------------- scroll indicator (D3)
// UIKit's indicator width, end padding, minimum length, colour, hold and
// fade are private internals, so these are start values to tune side by
// side with the iOS app (the ticket's Spec ▸ Vertical scroll indicator).
// They are ticket-scoped, like PullToRefreshDefaults' non-spring values
// (48): Motion tokens carry iOS call sites, and these have none — the
// ticket's Comments own the tuned values.

/** The thumb's width (dp). Start value; UIKit's is private. */
const val INDICATOR_WIDTH = 3f

/** The thumb's end padding (dp): from the track's end and the viewport edge. */
const val INDICATOR_END_PAD = 3f

/** The thumb's minimum length (dp); UIKit's is private. */
const val INDICATOR_MIN_LEN = 36f

/** The thumb's colour alpha: black in light, white in dark (UIKit `.default`). */
const val INDICATOR_ALPHA = 0.35f

/** How long the thumb stays fully visible after scrolling stops (ms). Start value. */
const val INDICATOR_HOLD_MS = 500L

/** The fade-out duration (ms), linear. Start value. */
const val INDICATOR_FADE_MS = 250

/** Below this scroll range (dp) the content fits and there is no thumb. */
const val INDICATOR_MIN_SPAN = 0.5f

/**
 * The indicator's thumb geometry (68's formula, decision TRANSCRIPT-D3),
 * a pure function of the viewport's scroll state. Returns
 * `(thumbTop, length)` in dp, measured from the transcript box's top
 * edge, or `null` when the content fits (`span ≤ [INDICATOR_MIN_SPAN]`):
 * a one-message chat shows no indicator, as UIKit's.
 *
 * The track runs from `topInset + [INDICATOR_END_PAD]` to
 * `viewportHeight − bottomInset − [INDICATOR_END_PAD]` (between the
 * header and the composer stack, SVC:376); the thumb's length is the
 * visible fraction of the content, floored at [INDICATOR_MIN_LEN] and
 * capped at the track; its top is the offset's fraction of the range.
 * While a band pulls the offset past either end, the thumb shrinks by
 * the excess and pins to that end of the track, as UIKit's does.
 */
fun scrollIndicatorThumb(
    offset: Float,
    minOffset: Float,
    maxOffset: Float,
    contentHeight: Float,
    viewportHeight: Float,
    topInset: Float,
    bottomInset: Float,
): Pair<Float, Float>? {
    val span = maxOffset - minOffset
    if (span <= INDICATOR_MIN_SPAN) return null
    val trackTop = topInset + INDICATOR_END_PAD
    val trackBottom = viewportHeight - bottomInset - INDICATOR_END_PAD
    val trackLen = trackBottom - trackTop
    val len = (trackLen * (viewportHeight - topInset - bottomInset) / contentHeight)
        .coerceAtLeast(INDICATOR_MIN_LEN)
        .coerceAtMost(trackLen)
    val excess = offset - offset.coerceIn(minOffset, maxOffset)
    if (excess < 0f) {
        // Pulled above the top: shrink, pinned to the track's top end.
        val shrunk = (len + excess).coerceAtLeast(INDICATOR_WIDTH)
        return trackTop to shrunk
    }
    if (excess > 0f) {
        // Past the tail: shrink, pinned to the track's bottom end.
        val shrunk = (len - excess).coerceAtLeast(INDICATOR_WIDTH)
        return (trackBottom - shrunk) to shrunk
    }
    val fraction = ((offset - minOffset) / span).coerceIn(0f, 1f)
    val thumbTop = trackTop + fraction * (trackLen - len)
    return thumbTop to len
}
