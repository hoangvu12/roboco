package sh.roboco.android.design

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TypeScale math (plain JVM, for the merge check): the clamp edges, the
 * Swift `.rounded(.toNearestOrAwayFromZero)` semantics of `scaled`, the
 * fixed-dp roundtrip through `toSp`, and the unclamped body metric.
 *
 * Every expected value is from the ticket's table, which was derived from
 * `Palette.swift:110-118` (`TypeScale.factor` / `TypeScale.size`).
 */
class TypeScaleTest {

    // ------------------------------------------------------------------
    // roundHalfAway — Swift .rounded(.toNearestOrAwayFromZero)
    // ------------------------------------------------------------------

    @Test
    fun roundHalfAwayRoundsTiesAwayFromZero() {
        assertEquals(17f, roundHalfAway(16.5f))
        assertEquals(14f, roundHalfAway(13.5f))
        assertEquals(13f, roundHalfAway(12.5f))
        assertEquals(2f, roundHalfAway(2.4999f))
        assertEquals(-1f, roundHalfAway(-0.5f))
        assertEquals(0f, roundHalfAway(0f))
    }

    @Test
    fun kotlinRoundRoundsTiesToEvenSoRoundHalfAwayExists() {
        // The ticket's pitfall: kotlin.math.round(16.5) == 16, but iOS
        // draws 17. roundHalfAway must not delegate to round().
        assertEquals(16f, kotlin.math.round(16.5f))
        assertEquals(17f, roundHalfAway(16.5f))
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Converts a TextUnit back to dp with the density it will render in. */
    private fun TextUnit.inDp(density: Density): Float = with(density) { toDp().value }

    // ------------------------------------------------------------------
    // font scale 1.0 — factor 1, sizes round to themselves
    // ------------------------------------------------------------------

    @Test
    fun defaultFontScaleGivesFactorOne() {
        val ts = TypeScale.from(Density(2.625f, 1f))
        assertEquals(1f, ts.factor)
        assertEquals(1f, ts.bodyFactor)
    }

    @Test
    fun atFactorOneScaledRoundsHalfSizesUp() {
        // TypeScale.size(16.5) == 17 at the default category — keep iOS's
        // rounding, do not "fix" it to 16 (ties-to-even).
        val density = Density(2.625f, 1f)
        val ts = TypeScale.from(density)
        assertEquals(17f, ts.scaled(16.5f).inDp(density), 0.01f)
        assertEquals(14f, ts.scaled(13.5f).inDp(density), 0.01f)
        assertEquals(13f, ts.scaled(12.5f).inDp(density), 0.01f)
        assertEquals(13f, ts.scaled(13f).inDp(density), 0.01f)
        assertEquals(11f, ts.scaled(11f).inDp(density), 0.01f)
        assertEquals(17f, ts.scaled(17f).inDp(density), 0.01f)
        assertEquals(62.dp, ts.scaledDp(62f))
        assertEquals(46.dp, ts.scaledDp(46f))
        assertEquals(40.dp, ts.scaledDp(40f))
        // Half-point geometry rounds away from zero too.
        assertEquals(17.dp, ts.scaledDp(16.5f))
    }

    // ------------------------------------------------------------------
    // 0.85 — the clamp floor (linear: below Compose's 1.03 threshold)
    // ------------------------------------------------------------------

    @Test
    fun smallFontScaleIsLinearAtTheClampFloor() {
        val density = Density(2.625f, 0.85f)
        val ts = TypeScale.from(density)
        assertEquals(0.85f, ts.factor)
        assertEquals(0.85f, ts.bodyFactor)
        assertEquals(14f, ts.scaled(16.5f).inDp(density), 0.01f)
        assertEquals(11f, ts.scaled(13.5f).inDp(density), 0.01f)
        assertEquals(11f, ts.scaled(12.5f).inDp(density), 0.01f)
        assertEquals(11f, ts.scaled(13f).inDp(density), 0.01f)
        assertEquals(9f, ts.scaled(11f).inDp(density), 0.01f)
        assertEquals(14f, ts.scaled(17f).inDp(density), 0.01f)
        assertEquals(53.dp, ts.scaledDp(62f))
        assertEquals(39.dp, ts.scaledDp(46f))
        assertEquals(34.dp, ts.scaledDp(40f))
    }

    // ------------------------------------------------------------------
    // 2.0 — the clamp ceiling. Compose's baked FontScaleConverter tables
    // include 2.0 (17 sp → 29 dp, bodyFactor ≈ 1.706 — the ticket's own
    // comment, deliberately not pinned: the tables are a platform
    // detail). `factor` clamps to 1.6; the body ratio does not.
    // ------------------------------------------------------------------

    @Test
    fun largeFontScaleClampsFactorAtMaxButBodyStaysUnclamped() {
        val density = Density(2.625f, 2f)
        val ts = TypeScale.from(density)
        assertEquals(1.6f, ts.factor)
        // Unclamped: the body ratio at 2.0 (≈ 1.706 on the platform curve)
        // is past the 1.6 ceiling the factor uses. Not pinned exactly —
        // the ticket's Comments say the table values are a platform
        // detail — but it must never be clamped.
        assertTrue("bodyFactor ${ts.bodyFactor} must stay unclamped past 1.6", ts.bodyFactor > 1.6f)
        // Everything through `scaled`/`scaledDp` uses the clamped factor,
        // so the ticket's expected-value table holds exactly.
        assertEquals(26f, ts.scaled(16.5f).inDp(density), 0.01f)
        assertEquals(22f, ts.scaled(13.5f).inDp(density), 0.01f)
        assertEquals(20f, ts.scaled(12.5f).inDp(density), 0.01f)
        assertEquals(21f, ts.scaled(13f).inDp(density), 0.01f)
        assertEquals(18f, ts.scaled(11f).inDp(density), 0.01f)
        assertEquals(27f, ts.scaled(17f).inDp(density), 0.01f)
        assertEquals(99.dp, ts.scaledDp(62f))
        assertEquals(74.dp, ts.scaledDp(46f))
        assertEquals(64.dp, ts.scaledDp(40f))
    }

    @Test
    fun bodyScaledIsUnclampedAndUnrounded() {
        // At 2.0 the body ratio stays unclamped past the 1.6 ceiling while
        // `scaled` is clamped: bodyScaled(16.5) renders bigger than
        // scaled(16.5) (≈ 28.1 dp vs 26 dp). The exact 2.0 curve (17 sp ≈
        // 29 dp, ≈ 1.706 — see the ticket's Comments) is a platform
        // detail and deliberately not pinned here.
        val density = Density(2.625f, 2f)
        val ts = TypeScale.from(density)
        assertTrue(
            "bodyScaled ${ts.bodyScaled(16.5f).inDp(density)} must exceed the clamped ${ts.scaled(16.5f).inDp(density)}",
            ts.bodyScaled(16.5f).inDp(density) > ts.scaled(16.5f).inDp(density),
        )
        // At the floor the fractional size survives: 16.5 * 0.85 = 14.025,
        // unrounded (linear: below Compose's 1.03 threshold), converted
        // back with the density it will render in.
        val floor = Density(2.625f, 0.85f)
        val small = TypeScale.from(floor)
        assertEquals(14.025f, small.bodyScaled(16.5f).inDp(floor), 0.01f)
    }

    // ------------------------------------------------------------------
    // fixed — dp-accurate whatever the font scale
    // ------------------------------------------------------------------

    @Test
    fun fixedRendersAtDpRegardlessOfFontScale() {
        // fixed(13).toDp() == 13 dp at every scale: Dp.toSp uses the
        // converter's inverse, so the roundtrip is exact.
        for (fontScale in listOf(1f, 0.85f, 1.3f, 2f)) {
            val density = Density(2.625f, fontScale)
            val ts = TypeScale.from(density)
            assertEquals("font scale $fontScale", 13f, ts.fixed(13f).inDp(density), 0.01f)
        }
    }

    @Test
    fun fixedIsNotSp() {
        // 13.sp at font scale 1.3 renders at ~17.2 dp through Compose's
        // converter; fixed(13) renders at 13 dp.
        val density = Density(2.625f, 1.3f)
        val ts = TypeScale.from(density)
        val spDp = with(density) { 13.sp.toDp().value }
        assertEquals(13f, ts.fixed(13f).inDp(density), 0.01f)
        assertTrue("13.sp at 1.3x should render bigger than 15 dp, was $spDp", spDp > 15f)
    }

    // ------------------------------------------------------------------
    // non-linear converter — Compose's baked 1.3 table (17 sp ≈ 20.9 dp)
    // ------------------------------------------------------------------

    @Test
    fun fontScale1_3GoesThroughTheNonLinearTable() {
        // Compose's `Density(float, fontScale)` (DensityImpl) inherits the
        // FontScaling default conversions, which consult the shipped
        // Android 14 FontScaleConverter tables (baked for 1.15/1.3/1.5/
        // 1.8/2.0, applied at fontScale >= 1.03): 17 sp maps to 20.9 dp
        // (factor ≈ 1.229), not 22.1 dp (linear 1.3).
        val density = Density(2.625f, 1.3f)
        val ts = TypeScale.from(density)
        assertTrue("factor ${ts.factor} should use the non-linear curve", ts.factor > 1.0f)
        assertTrue("factor ${ts.factor} should stay under the linear 1.3", ts.factor < 1.3f)
        assertEquals(ts.bodyFactor, ts.factor, 0.0001f)
        // scaled(16.5) = roundHalfAway(16.5 * 1.229) = 20.
        assertEquals(20f, ts.scaled(16.5f).inDp(density), 0.01f)
        // The fixed roundtrip stays exact through the curve's inverse.
        assertEquals(13f, ts.fixed(13f).inDp(density), 0.01f)
    }

    // ------------------------------------------------------------------
    // equality — so remember/recomposition can skip
    // ------------------------------------------------------------------

    @Test
    fun equalDensitiesProduceEqualTypeScales() {
        assertEquals(TypeScale.from(Density(1f)), TypeScale.from(Density(1f)))
        assertEquals(TypeScale.from(Density(1f)).hashCode(), TypeScale.from(Density(1f)).hashCode())
    }

    @Test
    fun differentFontScalesProduceDifferentTypeScales() {
        assertNotEquals(TypeScale.from(Density(1f)), TypeScale.from(Density(2.625f, 1.3f)))
        assertNotEquals(TypeScale.from(Density(2.625f, 0.85f)), TypeScale.from(Density(2.625f, 2f)))
    }

    @Test
    fun clampEdgesAreTheDocumentedConstants() {
        assertEquals(0.85f, TypeScale.MIN)
        assertEquals(1.6f, TypeScale.MAX)
    }
}
