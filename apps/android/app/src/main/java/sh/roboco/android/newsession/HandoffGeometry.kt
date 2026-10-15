package sh.roboco.android.newsession

import androidx.compose.ui.geometry.Rect
import sh.roboco.android.design.Motion
import kotlin.math.min

/**
 * The draft handoff's pure geometry (android-ui-parity #95) — the frame
 * math of `ios:Session/DraftHandoff.swift`: the flying bubble's padding
 * and inner rect (matching the Rust transcript bubble's
 * `BUBBLE_PAD_X = 15` / `BUBBLE_PAD_Y = 10` / `BUBBLE_RADIUS = 20`,
 * `mob:layout/rows.rs:132-134`), the composer glide's live target and
 * interpolation, the eased glide progress, the poll-give-up fallback and
 * the flight lerp.
 *
 * Every rect is in **root px** (the app is edge-to-edge, ticket 35, so
 * root = window), every `d` is the current density, and every function
 * is pure: the animator and the tests share exactly this math.
 */

/**
 * The flying bubble's start frame: [text] outset by the bubble padding —
 * top 10 dp, left 15 dp, bottom 10 dp, right 15 dp
 * (`DraftHandoff.swift:202-204`, `:63`).
 */
internal fun bubbleStartRect(text: Rect, d: Float): Rect = Rect(
    left = text.left - 15f * d,
    top = text.top - 10f * d,
    right = text.right + 15f * d,
    bottom = text.bottom + 10f * d,
)

/**
 * One glide frame's target: the draft composer keeps **its own size**
 * (never squashed) and settles bottom-aligned on the live rect
 * (`DraftHandoff.swift:92-93`: `x = live.minX`, `y = live.maxY − h0`).
 * The target is re-read every frame — the chat composer rides the IME
 * down while the glide runs.
 */
internal fun glideTarget(live: Rect, from: Rect): Rect = Rect(
    left = live.left,
    top = live.bottom - from.height,
    right = live.left + from.width,
    bottom = live.bottom,
)

/**
 * One glide frame: component-wise interpolation of left, top, width and
 * height by the eased progress `e` (the width and height stay constant
 * because [glideTarget] keeps `from`'s size; the lerp stays general).
 */
internal fun glideRect(from: Rect, to: Rect, e: Float): Rect {
    fun lerp(a: Float, b: Float): Float = a + (b - a) * e
    val left = lerp(from.left, to.left)
    val top = lerp(from.top, to.top)
    val width = lerp(from.width, to.width)
    val height = lerp(from.height, to.height)
    return Rect(left, top, left + width, top + height)
}

/**
 * The glide's eased progress from frame-clock time:
 * `t = min(1, elapsed / (durationMs·1e6))`, `e = Motion.easeOutQuart(t)`
 * — the exact quartic `1 − (1 − t)⁴` of `DraftHandoff.swift:164-165`,
 * not a bezier approximation.
 */
internal fun glideProgress(elapsedNanos: Long, durationMs: Int): Float {
    val t = min(1.0, elapsedNanos / (durationMs * 1_000_000.0))
    return Motion.easeOutQuart(t).toFloat()
}

/**
 * The flight's fallback target when the poll gives up (21 reads, no
 * first user bubble): `w = min(startWidth, 0.8·rootWidth)` at
 * `x = rootWidth − 16 dp − w`, `y = safeTop + 60 dp`
 * (`DraftHandoff.swift:105`) — [start]'s height. The host translates the
 * result from host coordinates into root.
 */
internal fun fallbackBubbleTarget(start: Rect, rootWidth: Float, safeTop: Float, d: Float): Rect {
    val w = min(start.width, 0.8f * rootWidth)
    val left = rootWidth - 16f * d - w
    val top = safeTop + 60f * d
    return Rect(left, top, left + w, top + start.height)
}

/**
 * One flight frame: component-wise lerp of left, top, width and height
 * by the spring progress `p`. `p` may overshoot 1 slightly (ζ 0.86) —
 * the rect is **never clamped**; the fill alpha is what clamps
 * (`bubbleFill = p.coerceIn(0f, 1f)`).
 */
internal fun flightRect(start: Rect, dest: Rect, p: Float): Rect {
    fun lerp(a: Float, b: Float): Float = a + (b - a) * p
    val left = lerp(start.left, dest.left)
    val top = lerp(start.top, dest.top)
    val width = lerp(start.width, dest.width)
    val height = lerp(start.height, dest.height)
    return Rect(left, top, left + width, top + height)
}

/**
 * The flying bubble's inner (text) rect: [r] inset by the padding —
 * left 15 dp, top 10 dp, right 15 dp, bottom 10 dp (`:182-211`). The
 * Rust bubble's text sits at `(bx + 15, by + 10)`, so the flight lands
 * on the real bubble's text.
 */
internal fun bubbleInner(r: Rect, d: Float): Rect = Rect(
    left = r.left + 15f * d,
    top = r.top + 10f * d,
    right = r.right - 15f * d,
    bottom = r.bottom - 10f * d,
)
