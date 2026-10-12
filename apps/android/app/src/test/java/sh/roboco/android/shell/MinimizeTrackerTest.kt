package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MinimizeTracker] — the pure 24 px accumulator of android-ui-parity #28's
 * acceptance criteria (threshold 24 px for readability; the values are
 * exact in binary float, so `>` vs `≥` is tested without rounding noise).
 *
 * Sign convention (pinned here): "down d" — the finger moving up, the list
 * scrolling toward its end — is `consumedY = −d`.
 */
class MinimizeTrackerTest {

    private val tracker = MinimizeTracker(thresholdPx = 24f)

    // --------------------------------------------------------- minimize

    @Test
    fun down23Then5Then25MinimizesOnlyPastTheStrictThreshold() {
        // 23.5: not enough.
        assertNull(tracker.onScroll(consumedY = -23.5f, leftoverY = 0f, minimized = false))
        // Travel exactly 24: `>` is strict, still no change.
        assertNull(tracker.onScroll(consumedY = -0.5f, leftoverY = 0f, minimized = false))
        // 24.25: minimize.
        assertEquals(true, tracker.onScroll(consumedY = -0.25f, leftoverY = 0f, minimized = false))
    }

    @Test
    fun up23Then5ExpandsAtExactlyMinus24() {
        assertEquals(true, tracker.onScroll(consumedY = -24.25f, leftoverY = 0f, minimized = false))
        // From minimized: up 23.5 is not enough.
        assertNull(tracker.onScroll(consumedY = 23.5f, leftoverY = 0f, minimized = true))
        // Travel exactly −24: `<=` expands.
        assertEquals(false, tracker.onScroll(consumedY = 0.5f, leftoverY = 0f, minimized = true))
    }

    @Test
    fun aDirectionReversalResetsTheAccumulator() {
        // 20 down, 5 up, 20 down: the reversals reset, so this never
        // accumulates to 24.
        assertNull(tracker.onScroll(consumedY = -20f, leftoverY = 0f, minimized = false))
        assertNull(tracker.onScroll(consumedY = 5f, leftoverY = 0f, minimized = false))
        assertNull(tracker.onScroll(consumedY = -20f, leftoverY = 0f, minimized = false))
        // One more dp of down travel would now trigger — the accumulator
        // holds 20, not 45.
        assertEquals(true, tracker.onScroll(consumedY = -4.25f, leftoverY = 0f, minimized = false))
    }

    // ------------------------------------------------------- at the top

    @Test
    fun leftoverUpwardScrollExpandsOnlyWhileMinimized() {
        // The pull the list at its top could not consume.
        assertEquals(false, tracker.onScroll(consumedY = 0f, leftoverY = 3f, minimized = true))
        assertNull(tracker.onScroll(consumedY = 0f, leftoverY = 3f, minimized = false))
    }

    @Test
    fun zeroDeltaIsANoOp() {
        assertNull(tracker.onScroll(consumedY = 0f, leftoverY = 0f, minimized = false))
        assertNull(tracker.onScroll(consumedY = 0f, leftoverY = 0f, minimized = true))
    }

    // -------------------------------------------------------- the reset

    @Test
    fun resetZeroesTheAccumulator() {
        assertNull(tracker.onScroll(consumedY = -20f, leftoverY = 0f, minimized = false))
        tracker.reset()
        // 4 dp of down travel after the reset: not enough.
        assertNull(tracker.onScroll(consumedY = -4f, leftoverY = 0f, minimized = false))
    }

    @Test
    fun aSmallLeftoverBelowTheHalfPixelIsNotAtTheTop() {
        // Sub-half-pixel leftovers come from float noise, not a real pull.
        assertNull(tracker.onScroll(consumedY = -23.5f, leftoverY = 0.4f, minimized = false))
        assertNull(tracker.onScroll(consumedY = -0.5f, leftoverY = 0f, minimized = false))
        // Downward leftover (scrolling past the bottom end) is not the top.
        assertNull(tracker.onScroll(consumedY = -20f, leftoverY = -3f, minimized = false))
    }
}
