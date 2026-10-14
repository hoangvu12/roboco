package sh.roboco.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The scroll bench's pure core (android-ui-parity #136) — iOS
 * `ScrollBench.tick`'s fling script, pinned at the ticket's worked
 * example: a fake target at `offset 1000, min -100, max 1000`,
 * `passes = 1` (2 flings), a 60 Hz budget (16 666 667 ns) and ticks every
 * 16 666 667 ns from t = 1e9. Plain JVM: the stepper touches no Compose
 * state.
 */
class ScrollBenchStepperTest {

    private val budget = 16_666_667L

    /**
     * The fake target: a plain offset field behind a `val` (a `var offset`
     * would clash with the interface's `setOffset` at the JVM level).
     */
    private class FakeTarget(start: Float, override val minOffset: Float, override val maxOffset: Float) :
        BenchScrollTarget {
        private var current = start
        override val offset: Float get() = current
        override fun setOffset(value: Float) {
            current = value
        }
        override fun releaseFollow() {}
    }

    @Test
    fun runsTheTwoFlingScript() {
        val target = FakeTarget(1000f, -100f, 1000f)
        val stepper = ScrollBenchStepper(target, passes = 1)

        // Tick 1 (last == 0): only records the clock, nothing moves.
        var t = 1_000_000_000L
        assertFalse(stepper.tick(t, budget))
        assertEquals(1000f, target.offset, 1e-3f)
        assertEquals(0, stepper.result.frames)

        // Tick 2: the first fling points down (+1) from the tail, so the
        // clamped y hits maxOffset at once — that fling ends here.
        t += budget
        assertFalse(stepper.tick(t, budget))
        assertEquals(1000f, target.offset, 1e-3f)
        assertEquals(1, stepper.result.frames)

        // Tick 3: the second fling starts up from the tail; the first
        // measured displacement is 5029.356 dp/s * 1/60 s.
        t += budget
        assertFalse(stepper.tick(t, budget))
        assertEquals(916.177f, target.offset, 0.01f)

        // The run ends on measured frame 18, clamped at minOffset.
        var done = false
        while (!done) {
            t += budget
            done = stepper.tick(t, budget)
        }
        assertEquals(-100f, target.offset, 1e-3f)
        assertEquals(18, stepper.result.frames)
        assertEquals(0, stepper.result.hitches)
    }

    @Test
    fun oneDoubledIntervalIsExactlyOneHitch() {
        val target = FakeTarget(1000f, -100f, 1000f)
        val stepper = ScrollBenchStepper(target, passes = 1)

        var t = 1_000_000_000L
        assertFalse(stepper.tick(t, budget))
        // The first measured frame arrives one full budget late.
        t += 2 * budget
        assertFalse(stepper.tick(t, budget))

        var done = false
        while (!done) {
            t += budget
            done = stepper.tick(t, budget)
        }
        assertEquals(1, stepper.result.hitches)
        assertEquals(16.667, stepper.result.hitchMs, 0.001)
        assertEquals(33.333, stepper.result.worstMs, 0.001)
    }
}
