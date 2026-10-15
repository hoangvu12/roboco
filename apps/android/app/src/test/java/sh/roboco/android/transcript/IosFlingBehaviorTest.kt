package sh.roboco.android.transcript

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The iOS fling deceleration of [IosFlingBehavior] (ticket 134), plain
 * JUnit: the decay runs on a stepped [MonotonicFrameClock] so the
 * per-frame `scrollBy` deltas can be pinned against the ticket's
 * reference curve — `v₀ = 3000 px/s` travels 271.88 px at 0.1 s, 590.07
 * at 0.25 s, 947.78 at 0.5 s, 1296.11 at 1 s and 1471.16 at 2 s
 * (UIKit's `DecelerationRate.normal`, `v(t) = v₀·e^(−2.002·t)`), and
 * the fling ends on clock frame 241 ± 2 (≈ 4 s). The scope is a plain
 * [ScrollScope]; no Compose UI, no Robolectric.
 *
 * Frame numbering: [StepFrameClock.frame] is 1-based and the decay's
 * first frame has play time 0 (`scrollBy(0f)`), so clock frame `n` is
 * `t = (n − 1)/60` s.
 */
class IosFlingBehaviorTest {

    /** The 60 Hz step the ticket's reference table is written against. */
    private val step60 = 16_666_667L

    /** Scrolls everything it is asked to, logging `(clock.frame, total)` per call. */
    private class RecordingScope(private val clock: StepFrameClock) : ScrollScope {
        val log = mutableListOf<Pair<Int, Double>>()
        var total = 0.0
            private set

        override fun scrollBy(pixels: Float): Float {
            total += pixels
            log += clock.frame to total
            return pixels
        }

        fun totalAt(frame: Int): Double = log.last { it.first == frame }.second
    }

    /** A wall the fling cannot pass: consumes only up to [wall] px. */
    private class WallScope(private val wall: Float) : ScrollScope {
        var total = 0f
            private set

        override fun scrollBy(pixels: Float): Float {
            val before = total
            total = (total + pixels).coerceAtMost(wall)
            return total - before
        }
    }

    private fun iosFling() = IosFlingBehavior(
        exponentialDecay(IOS_FRICTION_MULTIPLIER, IOS_FLING_STOP_PX_PER_S),
    )

    /** `MotionDurationScale` 0, as "Remove animations" provides in composition. */
    private object ZeroScale : MotionDurationScale {
        override val scaleFactor = 0f
    }

    // ------------------------------------------------------------ the curve

    @Test
    fun theDecayCurveMatchesUIKitAt60Hz() {
        val clock = StepFrameClock(step60)
        runBlocking(clock) {
            val scope = RecordingScope(clock)
            val left = with(iosFling()) { scope.performFling(3000f) }
            // The first frame is play time 0: nothing scrolled yet.
            assertEquals(0.0, scope.totalAt(1), 1e-9)
            // t = 0.1, 0.25, 0.5, 1.0 and 2.0 s (frames 7, 16, 31, 61, 121).
            assertEquals(271.88, scope.totalAt(7), 0.5)
            assertEquals(590.07, scope.totalAt(16), 0.5)
            assertEquals(947.78, scope.totalAt(31), 0.5)
            assertEquals(1296.11, scope.totalAt(61), 0.5)
            assertEquals(1471.16, scope.totalAt(121), 0.5)
            // Duration ln(3000/1.001)/2.002 = 3.9987 s → the block last
            // runs on clock frame 241 ± 2, and the decay has settled.
            val lastFrame = scope.log.last().first
            assertTrue("the fling must end on frame 241 ± 2, was $lastFrame", lastFrame in 239..243)
            assertTrue("the leftover velocity must be within ±2 px/s, was $left", abs(left) <= 2f)
        }
    }

    @Test
    fun theCurveMirrorsForNegativeVelocities() {
        val clock = StepFrameClock(step60)
        runBlocking(clock) {
            val scope = RecordingScope(clock)
            val left = with(iosFling()) { scope.performFling(-3000f) }
            assertEquals(-271.88, scope.totalAt(7), 0.5)
            assertEquals(-590.07, scope.totalAt(16), 0.5)
            assertEquals(-947.78, scope.totalAt(31), 0.5)
            assertEquals(-1296.11, scope.totalAt(61), 0.5)
            assertEquals(-1471.16, scope.totalAt(121), 0.5)
            assertTrue(abs(left) <= 2f)
        }
    }

    @Test
    fun removeAnimationsDoesNotChangeTheCurve() {
        // The behavior wraps itself in a MotionDurationScale of 1, so an
        // outer scale of 0 ("Remove animations") must not teleport the
        // fling to its end: the same frames, the same distances.
        val clock = StepFrameClock(step60)
        runBlocking(clock + ZeroScale) {
            val scope = RecordingScope(clock)
            with(iosFling()) { scope.performFling(3000f) }
            assertEquals(271.88, scope.totalAt(7), 0.5)
            assertEquals(590.07, scope.totalAt(16), 0.5)
            assertEquals(947.78, scope.totalAt(31), 0.5)
            assertEquals(1296.11, scope.totalAt(61), 0.5)
            assertEquals(1471.16, scope.totalAt(121), 0.5)
        }
    }

    // ------------------------------------------------------------ the edges

    @Test
    fun aWallCancelsTheFlingAndHandsBackItsVelocity() {
        val clock = StepFrameClock(step60)
        runBlocking(clock) {
            val scope = WallScope(500f)
            val left = with(iosFling()) { scope.performFling(3000f) }
            // 3000 px/s reaches 500 px after ~0.14 s at ≈ 1999 px/s: the
            // decay is cancelled at the wall and returns that velocity
            // (±10 %) for the rubber band's edge bounce.
            assertEquals(1999f, abs(left), 200f)
            assertEquals(500f, scope.total, 1e-3f)
        }
    }

    @Test
    fun aTinyVelocityIsNotAnimated() {
        val clock = StepFrameClock(step60)
        runBlocking(clock) {
            val scope = RecordingScope(clock)
            val left = with(iosFling()) { scope.performFling(0.5f) }
            // Below 1 px/s there is nothing worth decaying.
            assertEquals(0.5f, left, 0f)
            assertTrue("no frame must be consumed, was ${scope.log.size}", scope.log.isEmpty())
        }
    }
}
