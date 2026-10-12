package sh.roboco.android.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The glyph math (android-ui-parity #11): the `gspin_opacity` curve, the
 * phase tables, the keyframe alphas, the Long-modulo cycle and the
 * geometry helpers — the ticket's acceptance values, all pure Kotlin.
 */
class StatusGlyphMathTest {

    // -------------------------------------------------------- gspin_opacity

    @Test
    fun opacityMatchesTheTicketSpotValues() {
        assertEquals(1f, StatusGlyphMath.opacity(0f), 1e-5f)
        assertEquals(0.6f, StatusGlyphMath.opacity(0.2f), 1e-5f)
        assertEquals(0.1f, StatusGlyphMath.opacity(0.45f), 1e-5f)
        assertEquals(0.1f, StatusGlyphMath.opacity(0.7f), 1e-5f)
        assertEquals(0.1f, StatusGlyphMath.opacity(0.92f), 1e-5f)
        assertEquals(0.4375f, StatusGlyphMath.opacity(0.95f), 1e-5f)
        assertEquals(0.55f, StatusGlyphMath.opacity(0.96f), 1e-5f)
        assertEquals(1f, StatusGlyphMath.opacity(1f), 1e-5f)
        assertEquals(0.6f, StatusGlyphMath.opacity(1.2f), 1e-5f)
    }

    @Test
    fun opacityIsExactlyTheLinearKeyframeAnimation() {
        // A linear CAKeyframeAnimation with values [1, 0.1, 0.1, 1] at
        // keyTimes [0, 0.45, 0.92, 1] is opacity(u): the port can sample
        // the curve per frame instead of running an animation. Piecewise
        // lerp (the keyframe semantics) vs the closed-form port.
        for (i in 0..200) {
            val u = i / 200f
            assertEquals("opacity($u)", keyframeLerp(u), StatusGlyphMath.opacity(u), 1e-6f)
        }
    }

    /** Linear interpolation over the keyframes — CAKeyframeAnimation semantics. */
    private fun keyframeLerp(u: Float): Float {
        val times = floatArrayOf(0f, 0.45f, 0.92f, 1f)
        val values = floatArrayOf(1f, 0.1f, 0.1f, 1f)
        for (s in 0 until 3) {
            if (u < times[s + 1]) {
                val f = (u - times[s]) / (times[s + 1] - times[s])
                return values[s] + (values[s + 1] - values[s]) * f
            }
        }
        return values.last()
    }

    @Test
    fun opacityWrapsNegativeAndPastOneInputs() {
        // u = t − floor(t): negative values wrap, not clamp.
        assertEquals(StatusGlyphMath.opacity(0.2f), StatusGlyphMath.opacity(-0.8f), 1e-5f)
        assertEquals(1f, StatusGlyphMath.opacity(-1f), 1e-5f)
        assertEquals(StatusGlyphMath.opacity(0.95f), StatusGlyphMath.opacity(2.95f), 1e-5f)
        assertEquals(StatusGlyphMath.opacity(0f), StatusGlyphMath.opacity(6f), 1e-5f)
    }

    @Test
    fun opacityHonoursTheDimParameter() {
        // 1 + (0.4 − 1) · 0.2/0.45 = 0.73333; the fall scales with the dim.
        assertEquals(0.73333f, StatusGlyphMath.opacity(0.2f, dim = 0.4f), 1e-4f)
        assertEquals(0.4f, StatusGlyphMath.opacity(0.7f, dim = 0.4f), 1e-5f)
        assertEquals(0.7f, StatusGlyphMath.opacity(0.96f, dim = 0.4f), 1e-5f)
    }

    // ----------------------------------------------------------- phase tables

    @Test
    fun spinnerPhasesMatchTheRing() {
        assertArrayEquals(
            floatArrayOf(0f, 1f / 6f, 5f / 6f, 2f / 6f, 4f / 6f, 3f / 6f),
            StatusGlyphMath.SPINNER_PHASES,
            1e-6f,
        )
    }

    @Test
    fun spinnerPhaseFollowsTheRingTable() {
        val ring = arrayOf(intArrayOf(0, 1), intArrayOf(5, 2), intArrayOf(4, 3))
        for (row in 0..2) {
            for (col in 0..1) {
                assertEquals("ring[$row][$col]", ring[row][col] / 6f, StatusGlyphMath.spinnerPhase(row, col), 0f)
            }
        }
    }

    @Test
    fun trailerPhasesMatchTheTicketTable() {
        assertArrayEquals(
            floatArrayOf(0.75f, 0.5f, 0.75f, 0.5f, 0.25f, 0.5f, 0.25f, 0f, 0.25f),
            StatusGlyphMath.TRAILER_PHASES,
            1e-6f,
        )
    }

    @Test
    fun trailerPhaseFollowsTheFormula() {
        for (row in 0..2) {
            for (col in 0..2) {
                assertEquals(
                    "($row, $col)",
                    (2 - row + abs(col - 1)) / 4f,
                    StatusGlyphMath.trailerPhase(row, col),
                    0f,
                )
            }
        }
    }

    // ------------------------------------------------------ keyframe alphas

    @Test
    fun spinnerAlphasAtCycleZeroAreTheStaticPattern() {
        val out = FloatArray(6)
        StatusGlyphMath.fillCellAlphas(StatusGlyphMath.SPINNER_PHASES, 0f, out)
        assertArrayEquals(
            floatArrayOf(1f, 0.66667f, 0.1f, 0.33333f, 0.1f, 0.1f),
            out,
            1e-4f,
        )
    }

    @Test
    fun spinnerAlphasAtCycle04MatchTheTicketRow() {
        val out = FloatArray(6)
        StatusGlyphMath.fillCellAlphas(StatusGlyphMath.SPINNER_PHASES, 0.4f, out)
        assertArrayEquals(
            floatArrayOf(0.2f, 0.1f, 0.53333f, 0.1f, 0.86667f, 0.1f),
            out,
            1e-4f,
        )
    }

    @Test
    fun trailerAlphasAtCycleZeroAreTheStaticPattern() {
        val out = FloatArray(9)
        StatusGlyphMath.fillCellAlphas(StatusGlyphMath.TRAILER_PHASES, 0f, out)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.5f, 0.1f, 0.5f, 1f, 0.5f),
            out,
            1e-4f,
        )
    }

    @Test
    fun trailerAlphasAtCycle04MatchTheTicketRow() {
        val out = FloatArray(9)
        StatusGlyphMath.fillCellAlphas(StatusGlyphMath.TRAILER_PHASES, 0.4f, out)
        assertArrayEquals(
            floatArrayOf(0.7f, 0.1f, 0.7f, 0.1f, 0.1f, 0.1f, 0.1f, 0.2f, 0.1f),
            out,
            1e-4f,
        )
    }

    // ------------------------------------------------------------- the cycle

    @Test
    fun cycleUsesLongModuloAndKeepsPrecisionAtLargeUptimes() {
        assertEquals(0f, StatusGlyphMath.cycle(0L), 1e-6f)
        assertEquals(0.4f, StatusGlyphMath.cycle(300_000_000L), 1e-6f)
        assertEquals(0f, StatusGlyphMath.cycle(750_000_000L), 1e-6f)
        // 1.5e14 + 3e8 ns: Float division would lose the 3e8 remainder.
        assertEquals(0.4f, StatusGlyphMath.cycle(750_000_000L * 200_000L + 300_000_000L), 1e-6f)
        // 1e14 ns: 1e14 mod 7.5e8 = 2.5e8 → 1/3.
        assertEquals(0.333333f, StatusGlyphMath.cycle(100_000_000_000_000L), 1e-6f)
        // The trap the ticket calls out: this is 0.733333, not 0.4.
        assertEquals(0.733333f, StatusGlyphMath.cycle(100_000_000_000_000L + 300_000_000L), 1e-6f)
    }

    @Test
    fun cycleWrapsNegativeFrameTimesLikeTheFloorMod() {
        assertEquals(0f, StatusGlyphMath.cycle(-750_000_000L), 1e-6f)
        assertEquals(0.6f, StatusGlyphMath.cycle(-300_000_000L), 1e-6f)
    }

    @Test
    fun drawCycleIgnoresTheClockTimeWhenNotAnimated() {
        assertEquals(0f, StatusGlyphMath.drawCycle(false, 300_000_000L), 0f)
        assertEquals(0.4f, StatusGlyphMath.drawCycle(true, 300_000_000L), 1e-6f)
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun spinnerGeometryInATwelveDpBox() {
        val c = StatusGlyphMath.spinnerCellCenters(12f, 12f, 1f)
        val expected = listOf(
            3.5f to 1f, 8.5f to 1f,   // top row
            3.5f to 6f, 8.5f to 6f,   // middle row
            3.5f to 11f, 8.5f to 11f, // bottom row
        )
        for (i in expected.indices) {
            assertEquals("x$i", expected[i].first, c[2 * i], 1e-4f)
            assertEquals("y$i", expected[i].second, c[2 * i + 1], 1e-4f)
        }
        assertEquals(1.75f, StatusGlyphMath.cellRadius(1f), 1e-4f)
        assertEquals(3.5f, StatusGlyphMath.dotRadius(1f), 1e-4f)
    }

    @Test
    fun spinnerGeometryAtScale175InATwentyOneDpBox() {
        val c = StatusGlyphMath.spinnerCellCenters(21f, 21f, 1.75f)
        assertEquals(6.125f, c[0], 1e-4f)   // x, column 0
        assertEquals(14.875f, c[2], 1e-4f)  // x, column 1
        assertEquals(1.75f, c[1], 1e-4f)    // y, top row
        assertEquals(10.5f, c[5], 1e-4f)    // y, middle row
        assertEquals(19.25f, c[9], 1e-4f)   // y, bottom row
        assertEquals(3.0625f, StatusGlyphMath.cellRadius(1.75f), 1e-4f)
        // 1.6 × 1.75: only the check stroke scales explicitly with scale…
        assertEquals(2.8f, StatusGlyphMath.checkStrokeWidth(1.75f), 1e-4f)
    }

    @Test
    fun spinnerGeometryInTheTranscriptWidgetBox() {
        // 14 × 1.125 = 15.75 dp square at text scale 1.
        val c = StatusGlyphMath.spinnerCellCenters(15.75f, 15.75f, 1f)
        assertEquals(5.375f, c[0], 1e-4f)
        assertEquals(10.375f, c[2], 1e-4f)
        assertEquals(2.875f, c[1], 1e-4f)
        assertEquals(7.875f, c[5], 1e-4f)
        assertEquals(12.875f, c[9], 1e-4f)
    }

    @Test
    fun trailerGeometryInATwelveDpBox() {
        val c = StatusGlyphMath.trailerCellCenters(12f, 12f, 1f)
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            assertEquals("x$i", 6f + (col - 1f) * 5.25f, c[2 * i], 1e-4f)
            assertEquals("y$i", 6f + (row - 1f) * 5.25f, c[2 * i + 1], 1e-4f)
        }
        // x, y ∈ {0.75, 6, 11.25}: the 14-dp grid overhangs 1 dp per side.
        assertEquals(0.75f, c[0], 1e-4f)
        assertEquals(11.25f, c[4], 1e-4f)
    }

    @Test
    fun trailerGeometryInAFourteenDpBox() {
        val c = StatusGlyphMath.trailerCellCenters(14f, 14f, 1f)
        // x per column: {1.75, 7, 12.25}; y per row: {1.75, 7, 12.25}.
        assertEquals(1.75f, c[0], 1e-4f)   // x, cell 0 (row 0, col 0)
        assertEquals(7f, c[2], 1e-4f)      // x, cell 1 (row 0, col 1)
        assertEquals(12.25f, c[4], 1e-4f)  // x, cell 2 (row 0, col 2)
        assertEquals(1.75f, c[1], 1e-4f)   // y, row 0
        assertEquals(7f, c[7], 1e-4f)      // y, cell 3 (row 1)
        assertEquals(12.25f, c[13], 1e-4f) // y, cell 6 (row 2)
    }

    @Test
    fun checkGeometryInATwelveDpBox() {
        val p = StatusGlyphMath.checkPathPoints(12f, 12f)
        // s = 12/16 × 1.05 = 0.7875; o = (6 − 8s, 6 − 8s) = (−0.3, −0.3).
        assertEquals(2.45625f, p[0], 1e-4f)
        assertEquals(6.39375f, p[1], 1e-4f)
        assertEquals(4.81875f, p[2], 1e-4f)
        assertEquals(8.75625f, p[3], 1e-4f)
        assertEquals(9.54375f, p[4], 1e-4f)
        assertEquals(3.24375f, p[5], 1e-4f)
        assertEquals(1.6f, StatusGlyphMath.checkStrokeWidth(1f), 1e-4f)
    }

    @Test
    fun checkScalesThroughTheBoxWhileCellsStayFixed() {
        // In a 24 × 24 box the check doubles (s = 24/16 × 1.05)…
        val p = StatusGlyphMath.checkPathPoints(24f, 24f)
        assertEquals(2f * 2.45625f, p[0], 1e-3f)
        assertEquals(2f * 6.39375f, p[1], 1e-3f)
        // …but the spinner cells keep their 3.5 dp size, only re-centred.
        val c = StatusGlyphMath.spinnerCellCenters(24f, 24f, 1f)
        assertEquals(9.5f, c[0], 1e-4f)
        assertEquals(14.5f, c[2], 1e-4f)
        assertEquals(1.75f, StatusGlyphMath.cellRadius(1f), 1e-4f)
    }

    @Test
    fun geometryHelpersConvertDpToPxThroughTheDensityFactor() {
        // dpToPx is the only px conversion: xhdpi (2 px/dp) doubles the
        // centres of the same 12-dp box.
        val dp = StatusGlyphMath.spinnerCellCenters(12f, 12f, 1f)
        val px = StatusGlyphMath.spinnerCellCenters(24f, 24f, 1f, 2f)
        assertEquals(2f * dp[0], px[0], 1e-4f)
        assertEquals(2f * dp[1], px[1], 1e-4f)
        assertEquals(2f * StatusGlyphMath.cellRadius(1f), StatusGlyphMath.cellRadius(1f, 2f), 1e-4f)
        assertEquals(2f * StatusGlyphMath.dotRadius(1f), StatusGlyphMath.dotRadius(1f, 2f), 1e-4f)
        assertEquals(2f * StatusGlyphMath.checkStrokeWidth(1f), StatusGlyphMath.checkStrokeWidth(1f, 2f), 1e-4f)
    }
}
