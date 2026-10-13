package sh.roboco.android.transcript

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * The transcript's rubber band (ticket 134): an [OverscrollEffect] that
 * reproduces UIKit's `alwaysBounceVertical` + `bounces` for ticket 68's
 * viewport — a drag past either end moves `viewport.offset` out of its
 * range by `(1 − 1/(x·0.55/d + 1))·d` (the band), and a release springs
 * it back with a critically damped `ω = 20` frame loop. Moving the
 * offset itself (as UIKit moves `contentOffset`) instead of translating
 * the drawn content means 68's `distanceFromBottom`, `scrollY`, the
 * momentum re-latch (R7) and the jump button (131) all see the same
 * "past the end" state as on iOS; ticket 130's A7 clamp was widened to
 * let anchoring compose with it.
 *
 * Units (the ticket's convention table): Compose hands the effect
 * pointer-space px (+y = the finger moves down, older content comes
 * into view) — `reverseDirection` re-reverses only the inner scroll —
 * while [TranscriptViewport] is dp in offset space (+ = toward the
 * tail). Every conversion is a single sign flip and a density division,
 * done at the edges of [applyToScroll] and [applyToFling].
 *
 * The band is applied **as a delta**: a change of `pull` from p₁ to p₂
 * writes `offset + band(p₂) − band(p₁)`, so anchor shifts (130),
 * runway changes (69) and content growth compose with the band instead
 * of fighting a stored base. Fling deltas (`SideEffect`), the mouse
 * wheel, keyboard and accessibility scrolls pass straight through
 * `performScroll` — only a finger drag while `viewport.tracking` bands,
 * so a wheel at an end never leaves a band with nothing to release it.
 * A resting finger holds the bounce ("a touch on a moving scroll view
 * stops it"); the effect's [node] draws nothing, so the modifier chain
 * needs no `Modifier.overscroll`.
 */
class TranscriptRubberBand(
    private val viewport: TranscriptViewport,
    private val density: () -> Float,
) : OverscrollEffect {

    /** The signed raw finger travel beyond the edge, in offset space dp (+ past the tail). */
    private var pull = 0f

    /** A bounce-back frame loop is running. */
    private var bouncing = false

    /** Bumped to cancel the running bounce; each loop keeps its own generation. */
    private var generation = 0

    override val isInProgress: Boolean get() = bouncing || pull != 0f

    /** Nothing to draw: the band moves the offset, the rows move with it. */
    override val node: DelegatableNode = object : Modifier.Node() {}

    /**
     * B0–B6. A drag delta in pointer-space px. The whole gesture delta
     * is consumed (B6): the band always takes what `scrollByUser`
     * refuses, and `scrollByUser` clamps itself (68's R14).
     */
    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // B0: only a finger drag (68's `tracking`) bands. Everything else —
        // fling deltas, wheel, keyboard, accessibility — scrolls 1:1.
        val d = viewport.viewportHeight
        if (source != NestedScrollSource.UserInput || !viewport.tracking || d <= 0f) {
            return performScroll(delta)
        }
        // B1: any user drag first cancels a running bounce.
        cancelBounce()
        // B2: re-grab — continue a band left out of range (a cancelled
        // bounce, or a release that never ran) on the same curve. An
        // excess ≥ 0.5·d can only come from content shrinking, so it
        // stays unbanded and 68's R14 scrolls back 1:1.
        val e = excess()
        if (pull == 0f && e != 0f && abs(e) < 0.5f * d) {
            pull = sign(e) * rubberBandInverse(abs(e), d)
        }
        // B3: relax — a delta that points against the band first walks
        // the raw travel back toward 0 (the band closes along the same
        // curve), and only the remainder scrolls 1:1.
        var r = -delta.y / density() // the delta in offset space dp
        if (pull != 0f && pull * r < 0f) {
            val take = min(abs(r), abs(pull))
            movePull(pull - sign(pull) * take)
            r -= sign(r) * take
        }
        // B4: always perform the scroll, even at 0 — the first delta while
        // tracking is what sets 68's `dragging` and releases follow (R5).
        val result = performScroll(Offset(0f, -r * density()))
        val consumed = -result.y / density()
        // B5: stretch — what the scroll refused is exactly the travel past
        // the end; grow the band by it.
        val left = r - consumed
        if (abs(left) > 0.01f) {
            movePull(pull + left)
        }
        // B6: the gesture's whole delta is consumed.
        return delta
    }

    /**
     * R-a / R-b on release. Out of bounds: bounce back with v₀ = 0 and
     * do **not** fling — on UIKit a release out of bounds never flings
     * the content. In range: run the fling (68's `FollowAwareFling`
     * around [IosFlingBehavior]), and if it stopped at the end it was
     * heading for, carry its leftover velocity into an edge bounce.
     */
    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val wasOut = excess() != 0f
        pull = 0f
        if (wasOut) {
            bounce(0f)
            return
        }
        val left = performFling(velocity)
        val v = -left.y / density() // offset space dp/s
        if (v != 0f && atEndToward(v)) bounce(v)
    }

    // ------------------------------------------------------------- helpers

    /** `offset − offset.coerceIn(min, max)`: + past the tail, − above the top. */
    private fun excess(): Float =
        viewport.offset - viewport.offset.coerceIn(viewport.minOffset, viewport.maxOffset)

    /** Whether the offset sits at the end the velocity [v] (offset space) points to. */
    private fun atEndToward(v: Float) =
        if (v > 0f) viewport.offset >= viewport.maxOffset - 0.5f
        else viewport.offset <= viewport.minOffset + 0.5f

    /** The band's signed contribution for a raw travel [p] over viewport height [d]. */
    private fun band(p: Float, d: Float): Float = sign(p) * rubberBand(abs(p), d)

    /** Moves [pull] to [to], applying the band's change as a plain offset delta. */
    private fun movePull(to: Float) {
        val d = viewport.viewportHeight
        val shift = band(to, d) - band(pull, d)
        pull = to
        viewport.setOffset(viewport.offset + shift)
    }

    /**
     * B1: cancels the running bounce at once (a drag owns the offset
     * again); the old loop exits on its generation mismatch and its
     * `finally` leaves the flinging flag alone.
     */
    private fun cancelBounce() {
        if (!bouncing) return
        generation++
        bouncing = false
        viewport.onFlingEnd()
    }

    /**
     * The bounce-back (the ticket's Spec loop): a hand-written
     * `withFrameNanos` loop stepping the exact critically damped ODE
     * toward `target = offset.coerceIn(min, max)`, re-read every frame
     * so anchoring (130) and content growth move the target under it.
     * `flinging` brackets the whole bounce — UIKit reports
     * `isDecelerating` during it, so 68's R7 re-latches on the first
     * step past the tail exactly as on iOS. Not an `Animatable`: it
     * would snap under `MotionDurationScale` 0 and cannot chase a
     * moving target; this loop is unaffected by both.
     */
    private suspend fun bounce(v0: Float) {
        val myGen = ++generation
        bouncing = true
        viewport.onFlingStart()
        try {
            var v = v0
            var last = 0L
            var first = true
            while (true) {
                val now = withFrameNanos { it }
                // Another writer owns the offset: a newer bounce, or 68's
                // tail spring (R3 started it while following).
                if (generation != myGen || viewport.springRunning) return
                // The first frame is pinned to one 60 Hz step, so the bounce
                // never wastes it (the dead-first-frame trap of #609); after
                // that the real elapsed time, clamped to 1/30 s.
                val dt = if (first) 1f / 60f
                else (((now - last) / 1e9).coerceIn(0.0, 1.0 / 30.0)).toFloat()
                last = now
                val wasFirst = first
                first = false
                // A resting finger holds the bounce: iOS stops the scroll
                // at the touch; the release resumes it.
                if (viewport.tracking) continue
                val target = viewport.offset.coerceIn(viewport.minOffset, viewport.maxOffset)
                val x = viewport.offset - target
                if (wasFirst) {
                    // Defensive: nothing to spring from and the velocity
                    // points at no end (the caller checked) — a no-op.
                    if (x == 0f && !atEndToward(v)) return
                } else if (abs(x) < 0.5f && v * x <= 0f) {
                    // Settled (moving toward 0, not out): snap and exit.
                    if (x != 0f) viewport.setOffset(target)
                    return
                }
                val step = bounceStep(x, v, dt)
                viewport.setOffset(target + step.first)
                v = step.second
            }
        } finally {
            if (generation == myGen) {
                bouncing = false
                viewport.onFlingEnd()
            }
        }
    }
}

/**
 * The transcript's overscroll effect (ticket 134). The density is read
 * through [rememberUpdatedState] so a density change reaches the
 * non-composable callbacks without rebuilding the effect or the
 * modifier chain; the viewport is the remember key, matching 68's other
 * per-viewport state.
 */
@Composable
fun rememberTranscriptRubberBand(viewport: TranscriptViewport): OverscrollEffect {
    val density by rememberUpdatedState(LocalDensity.current.density)
    return remember(viewport) { TranscriptRubberBand(viewport) { density } }
}
