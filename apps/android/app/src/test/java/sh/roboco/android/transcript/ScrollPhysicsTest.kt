package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure scroll-physics maths of ticket 134 ([ScrollPhysics.kt]),
 * plain JUnit — no Robolectric, no core: every value is pinned against
 * the ticket's reference tables (UIKit's rubber band, the exact
 * critically damped bounce step, and the indicator's worked example),
 * tolerance 1e-3 unless the table says otherwise.
 */
class ScrollPhysicsTest {

    // -------------------------------------------------------- the constants

    @Test
    fun theDecayConstantsMatchUIKit() {
        // k = −1000·ln(0.998); frictionMultiplier = k / 4.2.
        // The rate is the decimal 0.998: `0.998f.toDouble()` is
        // 0.99800002574…, whose log gives 2.0019769 — the float literal's
        // representation error times 1000/r, not a wrong constant. Pin the
        // constant against the exact decimal instead (2.00200267…).
        assertEquals(0.998, IOS_DECELERATION_RATE.toDouble(), 1e-7)
        assertEquals(-1000.0 * kotlin.math.ln(0.998), IOS_DECAY_K.toDouble(), 1e-6)
        assertEquals(IOS_DECAY_K, IOS_FRICTION_MULTIPLIER * 4.2f, 1e-5f)
        // The stop threshold is half a pixel of remaining travel.
        assertEquals(0.5f * IOS_DECAY_K, IOS_FLING_STOP_PX_PER_S, 1e-6f)
        // Bounce spring: dampingRatio 1, stiffness 400 → ω = 20.
        assertEquals(20f, BOUNCE_OMEGA, 0f)
    }

    // --------------------------------------------------------- the rubber band

    @Test
    fun theBandMatchesUIKitSTable() {
        // d = 800: the ticket's f(x) table.
        assertEquals(5.4624f, rubberBand(10f, 800f), 1e-3f)
        assertEquals(21.4112f, rubberBand(40f, 800f), 1e-3f)
        assertEquals(51.4620f, rubberBand(100f, 800f), 1e-3f)
        assertEquals(96.7033f, rubberBand(200f, 800f), 1e-3f)
        assertEquals(136.7876f, rubberBand(300f, 800f), 1e-3f)
        assertEquals(172.5490f, rubberBand(400f, 800f), 1e-3f)
        assertEquals(283.8710f, rubberBand(800f, 800f), 1e-3f)
        assertEquals(419.0476f, rubberBand(1600f, 800f), 1e-3f)
        // The ticket's second viewport height.
        assertEquals(48.3516f, rubberBand(100f, 400f), 1e-3f)
    }

    @Test
    fun theBandInvertsItself() {
        for (x in listOf(10f, 100f, 800f, 1600f)) {
            assertEquals("x = $x", x, rubberBandInverse(rubberBand(x, 800f), 800f), 1e-2f)
        }
    }

    @Test
    fun theBandNeverReachesTheViewportHeight() {
        assertTrue(rubberBand(1e6f, 800f) < 800f)
        // f(1e6) = 800·687.5/688.5 = 798.8381 exactly (u = x·c/d = 687.5),
        // so the old `> 799.9` at x = 1e6 contradicted the formula itself;
        // the saturation within 0.1 of d needs u ≥ 7999, i.e. x ≈ 1.2e7.
        assertEquals(798.8381f, rubberBand(1e6f, 800f), 1e-3f)
        assertTrue(rubberBand(1e8f, 800f) < 800f)
        assertTrue(rubberBand(1e8f, 800f) > 799.9f) // but it saturates there (799.988)
    }

    @Test
    fun theBandsSlopeAtTheEdgeIsC() {
        // f(0.01)/0.01: the content first moves at 55 % of finger speed.
        assertEquals(0.55f, rubberBand(0.01f, 800f) / 0.01f, 1e-3f)
    }

    // -------------------------------------------------------- the bounce step

    @Test
    fun theBounceStepFromRestMatchesUIKit() {
        // Release, x₀ = 100, v₀ = 0, dt = 1/60: 95.5375, 85.5695, 73.5759.
        var x = 100f
        var v = 0f
        val step = 1f / 60f
        repeat(3) {
            val next = bounceStep(x, v, step)
            x = next.first
            v = next.second
            when (it) {
                0 -> assertEquals(95.5375f, x, 1e-3f)
                1 -> assertEquals(85.5695f, x, 1e-3f)
                2 -> assertEquals(73.5759f, x, 1e-3f)
            }
        }
    }

    @Test
    fun theBounceStepFromAnEdgeVelocityMatchesUIKit() {
        // Edge bounce, x₀ = 0, v₀ = 1000, dt = 1/60: 11.9422, 17.1139,
        // 18.3940 (the peak, at 50 ms).
        var x = 0f
        var v = 1000f
        val step = 1f / 60f
        repeat(3) {
            val next = bounceStep(x, v, step)
            x = next.first
            v = next.second
            when (it) {
                0 -> assertEquals(11.9422f, x, 1e-3f)
                1 -> assertEquals(17.1139f, x, 1e-3f)
                2 -> assertEquals(18.3940f, x, 1e-3f)
            }
        }
    }

    @Test
    fun theEdgeBouncePeaksAtVOmegaOverE() {
        // The analytic peak of an edge bounce: v₀ / (ω·e) = 0.018394·v₀.
        var x = 0f
        var v = 1000f
        var peak = 0f
        repeat(10) {
            val next = bounceStep(x, v, 1f / 60f)
            x = next.first
            v = next.second
            peak = maxOf(peak, x)
        }
        assertEquals(1000f / (BOUNCE_OMEGA * kotlin.math.E.toFloat()), peak, 0.02f)
    }

    // ------------------------------------------------ the indicator geometry

    /** The ticket's worked example: 68/100 insets, viewport 800, start values. */
    private fun thumb(offset: Float): Pair<Float, Float>? = scrollIndicatorThumb(
        offset = offset,
        minOffset = -68f,
        maxOffset = 2300f,
        contentHeight = 3000f,
        viewportHeight = 800f,
        topInset = 68f,
        bottomInset = 100f,
    )

    @Test
    fun theThumbGeometryMatchesTheWorkedExample() {
        // Track 71…697 (trackLen 626), span 2368, len 131.877.
        assertEquals(71f, thumb(-68f)!!.first, 1e-3f)
        assertEquals(131.877f, thumb(-68f)!!.second, 1e-3f)
        assertEquals(318.062f, thumb(1116f)!!.first, 1e-3f)
        assertEquals(131.877f, thumb(1116f)!!.second, 1e-3f)
        assertEquals(565.123f, thumb(2300f)!!.first, 1e-3f)
        // At the tail the thumb bottom is the track's end (697).
        assertEquals(697f, thumb(2300f)!!.first + thumb(2300f)!!.second, 1e-3f)
    }

    @Test
    fun aBandShrinksAndPinsTheThumb() {
        // 50 dp past the top: len 81.877 pinned to the track's top (71).
        val top = thumb(-118f)!!
        assertEquals(71f, top.first, 1e-3f)
        assertEquals(81.877f, top.second, 1e-3f)
        // 50 dp past the tail: len 81.877, the thumb bottom at 697.
        val bottom = thumb(2350f)!!
        assertEquals(81.877f, bottom.second, 1e-3f)
        assertEquals(697f, bottom.first + bottom.second, 1e-3f)
        // The thumb never collapses below the capsule's width.
        val collapsed = thumb(5000f)!!
        assertEquals(INDICATOR_WIDTH, collapsed.second, 1e-3f)
    }

    @Test
    fun shortContentShowsNoThumbAndLongContentFloorsTheLength() {
        // Content 500: maxOffset == minOffset, the span is 0 → no thumb.
        assertNull(
            scrollIndicatorThumb(
                offset = -68f,
                minOffset = -68f,
                maxOffset = -68f,
                contentHeight = 500f,
                viewportHeight = 800f,
                topInset = 68f,
                bottomInset = 100f,
            ),
        )
        // A 100 000 dp transcript: len = INDICATOR_MIN_LEN.
        val long = scrollIndicatorThumb(
            offset = 0f,
            minOffset = -68f,
            maxOffset = 99_300f,
            contentHeight = 100_000f,
            viewportHeight = 800f,
            topInset = 68f,
            bottomInset = 100f,
        )!!
        assertEquals(INDICATOR_MIN_LEN, long.second, 1e-3f)
    }
}
