package sh.roboco.android.transcript.widgets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure ShimmerView maths (ticket 122): the gradient stop table, the
 * peak centres, the triangle alpha, and the phase wrap — the numbers the
 * mask and the goldens are built on (`ios:Transcript/ToolViews.swift:245-273`
 * and the desktop constants it mirrors). Plain JUnit 4, no Robolectric:
 * every value here is arithmetic.
 */
class ShimmerMathTest {

    @Test
    fun stopsMatchTheIosTable() {
        // (1.5 + 3k)/9 ± 0.36/9 for k = 0, 1, 2 (ToolViews.swift:253-260).
        assertArrayEquals(
            floatArrayOf(
                0.126667f, 0.166667f, 0.206667f,
                0.46f, 0.5f, 0.54f,
                0.793333f, 0.833333f, 0.873333f,
            ),
            ShimmerMath.STOPS,
            1e-5f,
        )
        // Ascending, inside [0, 1]: a valid CAGradientLayer stop list.
        for (j in 1 until ShimmerMath.STOPS.size) {
            assertTrue(ShimmerMath.STOPS[j - 1] < ShimmerMath.STOPS[j])
        }
    }

    @Test
    fun colorsAlternateTransparentAndOpaqueBlack() {
        for (j in ShimmerMath.COLORS.indices) {
            assertEquals("alpha at stop $j", if (j % 3 == 1) 255 else 0, ShimmerMath.COLORS[j] ushr 24)
            assertEquals("rgb at stop $j", 0, ShimmerMath.COLORS[j] and 0xFFFFFF)
        }
    }

    @Test
    fun peakCentresTravelLeftToRight() {
        // x_k = (−4.5 + 6t + 3k)·w: at t = 0 the peaks sit at −4.5, −1.5,
        // 1.5 (only the title's [0, 1] band matters — nothing is visible).
        assertEquals(-4.5f, ShimmerMath.centre(0, 0f), 1e-6f)
        assertEquals(-1.5f, ShimmerMath.centre(1, 0f), 1e-6f)
        assertEquals(1.5f, ShimmerMath.centre(2, 0f), 1e-6f)
        // The k = 1 highlight crosses the title: peak at x 0 at t 0.25,
        // x 0.5 at t 1/3, x 1 at t 0.4167.
        assertEquals(0f, ShimmerMath.centre(1, 0.25f), 1e-6f)
        assertEquals(0.5f, ShimmerMath.centre(1, 1f / 3f), 1e-6f)
        assertEquals(1f, ShimmerMath.centre(1, 0.4167f), 0.001f)
        // The wrap is seamless: t → 1 equals t = 0 shifted by one period
        // (centre(0, 1) == centre(2, 0)).
        assertEquals(ShimmerMath.centre(2, 0f), ShimmerMath.centre(0, 1f), 1e-6f)
    }

    @Test
    fun alphaSamples() {
        // The k = 1 peak at t = 1/3 sits at x 0.5; the shoulders fall to
        // 0 at ±0.36 (a 0.72-wide band, ~0.7 title widths).
        assertEquals(1f, ShimmerMath.alpha(0.5f, 1f / 3f), 1e-4f)
        assertEquals(0.5f, ShimmerMath.alpha(0.32f, 1f / 3f), 0.001f)
        assertEquals(0f, ShimmerMath.alpha(0.14f, 1f / 3f), 1e-4f)
        assertEquals(0f, ShimmerMath.alpha(0.86f, 1f / 3f), 1e-4f)
        // k = 1's peak at x 0 at t 0.25.
        assertEquals(1f, ShimmerMath.alpha(0f, 0.25f), 1e-4f)
        // The k = 0 peak at x 0.5 at t 5/6.
        assertEquals(1f, ShimmerMath.alpha(0.5f, 5f / 6f), 1e-4f)
    }

    @Test
    fun nothingIsVisibleAtTheQuietPhases() {
        // t = 0: the peaks are at −4.5/−1.5/1.5, all farther than the
        // 0.36 half-width from the title — the sweep's quiet phase.
        for (x in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals("alpha(x=$x, t=0)", 0f, ShimmerMath.alpha(x, 0f), 1e-4f)
        }
    }

    @Test
    fun theWrapIsSeamless() {
        // Just before the wrap the phase looks like t = 0 (both quiet),
        // so the restart is invisible.
        for (x in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals("x=$x wraps to the same alpha", ShimmerMath.alpha(x, 0f), ShimmerMath.alpha(x, 0.99999f), 1e-3f)
        }
    }

    @Test
    fun phaseWrapsAtThePeriod() {
        // 3.4 s linear, restart: 850 ms is a quarter, 3400 wraps to 0,
        // 4250 is a quarter again.
        assertEquals(0.25f, ShimmerMath.phase(850_000_000L), 1e-5f)
        assertEquals(0f, ShimmerMath.phase(3_400_000_000L), 1e-5f)
        assertEquals(0.25f, ShimmerMath.phase(4_250_000_000L), 1e-5f)
        // At or before the start instant the phase is 0, never negative.
        assertEquals(0f, ShimmerMath.phase(0L), 1e-5f)
        assertEquals(0f, ShimmerMath.phase(-1L), 1e-5f)
    }
}
