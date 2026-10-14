package sh.roboco.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hitch meter's pure core (android-ui-parity #136) — iOS
 * `HitchMeter.tick`'s rule and text, pinned at a 60 Hz budget
 * (16 666 667 ns). Plain JVM: no Android types.
 */
class HitchCounterTest {

    private val budget = 16_666_667L

    @Test
    fun regularIntervalsProduceNoHitch() {
        val counter = HitchCounter()
        var t = 1_000_000_000L
        var texts = 0
        repeat(120) {
            // The only non-null returns are the 30-frame text refreshes;
            // a hitch never appears.
            if (counter.onFrame(t, budget, 0uL) != null) texts++
            t += budget
        }
        assertEquals(0, counter.hitches)
        assertEquals(120, counter.frames)
        // Frames 30, 60, 90 and 120 each refreshed the text.
        assertEquals(4, texts)
    }

    @Test
    fun exactlyOneAndAHalfBudgetsIsNotAHitch() {
        val counter = HitchCounter()
        assertNull(counter.onFrame(1_000_000_000L, budget, 0uL))
        // 1.5 x 16 666 667 = 25 000 000.5: the rule is strictly greater,
        // so the exact 25 000 000 gap stays clean.
        assertNull(counter.onFrame(1_000_000_000L + 25_000_000L, budget, 0uL))
        assertEquals(0, counter.hitches)
        assertEquals(2, counter.frames)
    }

    @Test
    fun aGapOfTwoBudgetsIsOneHitch() {
        val counter = HitchCounter()
        assertNull(counter.onFrame(1_000_000_000L, budget, 0uL))
        assertNull(counter.onFrame(1_000_000_000L + 33_333_334L, budget, 0uL))
        assertEquals(1, counter.hitches)
    }

    @Test
    fun textArrivesOnEveryThirtiethFrame() {
        val counter = HitchCounter()
        // Frame 2's doubled interval is the run's single hitch; every
        // other interval is exactly the budget.
        assertNull(counter.onFrame(1_000_000_000L, budget, 1449uL))
        assertNull(counter.onFrame(1_000_000_000L + 33_333_334L, budget, 1449uL))
        var t = 1_000_000_000L + 33_333_334L
        for (frame in 3..29) {
            t += budget
            assertNull("frame $frame produced text", counter.onFrame(t, budget, 1449uL))
        }
        t += budget
        assertEquals(" 1 hitches · layout 1.4ms ", counter.onFrame(t, budget, 1449uL))
    }

    @Test
    fun countersNeverReset() {
        val counter = HitchCounter()
        var t = 1_000_000_000L
        var texts = 0
        for (frame in 1..120) {
            // One doubled interval (frame 2) — one cumulative hitch.
            t += if (frame == 2) 33_333_334L else budget
            if (counter.onFrame(t, budget, 0uL) != null) texts++
        }
        assertEquals(120, counter.frames)
        assertEquals(1, counter.hitches)
        // Frames 30, 60, 90 and 120 each rewrote the text.
        assertEquals(4, texts)
    }

    @Test
    fun meterTextFormatsTheLayoutMicros() {
        assertEquals(" 1 hitches · layout 1.4ms ", meterText(1, 1449uL))
        assertEquals(" 0 hitches · layout 0.0ms ", meterText(0, 0uL))
        assertEquals(" 12 hitches · layout 104.6ms ", meterText(12, 104_623uL))
    }
}
