package sh.roboco.android.transcript

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.withContext

/**
 * UIScrollView's fling deceleration as a Compose [FlingBehavior]
 * (ticket 134): `v(t) = v₀·e^(−k·t)` with
 * `k = −1000·ln(0.998) = [IOS_DECAY_K]` — velocity keeps 0.998 of
 * itself every millisecond, `UIScrollView.DecelerationRate.normal`.
 * Built from [exponentialDecay] with [IOS_FRICTION_MULTIPLIER], whose
 * friction is `−4.2 × multiplier` per second. A hard 3000 px/s flick
 * therefore coasts `v/k ≈ 1498.5` px over ≈ 4 s — Compose's spline
 * decay feels different, which is why the transcript never uses it.
 *
 * The decay spec is density-independent (px in, px out), so [remember]
 * needs no key. This is a plain [FlingBehavior], not a
 * `TargetedFlingBehavior`: nothing in the transcript needs the
 * remaining-distance callback. Ticket 116 (decision TRANSCRIPT-D2)
 * passes [rememberIosFlingBehavior] to its horizontal scrollers too.
 *
 * Stepping is delta-based — each frame scrolls `value − lastValue`,
 * never to an absolute target, so ticket 130's anchoring can shift the
 * offset under a running fling without disturbing it. When the scroll
 * refuses a delta (the list hit an end), the decay is cancelled and
 * the current velocity is returned, which becomes the rubber band's
 * edge-bounce velocity. All structure mirrors Compose's own
 * `DefaultFlingBehavior`, including the small-velocity early-out and
 * the `CancellationException` recovery.
 *
 * The fling runs under a [MotionDurationScale] of 1: like Compose's own
 * fling it animates through the scale, but with the system animator
 * scale at 0 ("Remove animations") it must not teleport to `v/k` in one
 * frame — Reduce Motion does not change scroll physics on iOS.
 */
class IosFlingBehavior(private val decay: DecayAnimationSpec<Float>) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
        withContext(UnitMotionScale) {
            // Nothing worth animating below 1 px/s, as in Compose's default.
            if (abs(initialVelocity) <= 1f) return@withContext initialVelocity
            var velocityLeft = initialVelocity
            var lastValue = 0f
            val state = AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
            try {
                state.animateDecay(decay) {
                    val delta = value - lastValue
                    val consumed = scrollBy(delta)
                    lastValue = value
                    velocityLeft = velocity
                    // The scroll refused part of the delta: an end is in
                    // the way, so stop decaying and hand the current
                    // velocity back for the edge bounce.
                    if (abs(delta - consumed) > 0.5f) cancelAnimation()
                }
            } catch (e: CancellationException) {
                // The fling itself was cancelled (a new gesture took the
                // scroll mutex): report where the decay had got to.
                velocityLeft = state.velocity
            }
            velocityLeft
        }
}

/**
 * The transcript's fling behavior: [IosFlingBehavior] over the UIKit
 * decay constants. `remember` with no keys — the decay is
 * density-independent, so it survives density changes unchanged.
 */
@Composable
fun rememberIosFlingBehavior(): FlingBehavior =
    remember { IosFlingBehavior(exponentialDecay(IOS_FRICTION_MULTIPLIER, IOS_FLING_STOP_PX_PER_S)) }

/**
 * A [MotionDurationScale] of 1 for the physics that must not follow the
 * system animator scale: the fling and the indicator fade. Shared by
 * [IosFlingBehavior] and the scroll indicator (ticket 134).
 */
internal object UnitMotionScale : MotionDurationScale {
    override val scaleFactor = 1f
}
