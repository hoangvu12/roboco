package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 24's geometry seam (plain JVM): `navLayerTransforms` against the
 * worked table from `android-app#19` §Navigation stacks (a 0.5 s critically
 * damped push, outgoing at −30 %, scrim 0 → 0.1) and `chromePlacement`
 * against the four-row chrome table.
 *
 * The expected values are the ticket's worked example (W = 400 px), not
 * recomputations of the implementation's formula.
 */
class NavLayersTest {

    // ------------------------------------------------------------ geometry

    @Test
    fun ltrPushAtHalfProgressMatchesTheWorkedTable() {
        val t = navLayerTransforms(NavKind.Push, progress = 0.5f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(200f, t.aboveTx, 1e-4f)
        assertEquals(-60f, t.belowTx, 1e-4f)
        assertEquals(0.05f, t.scrimAlpha, 1e-4f)
        assertEquals(1f, t.aboveAlpha, 1e-4f)
    }

    @Test
    fun ltrPushAtFullProgressMatchesTheWorkedTable() {
        val t = navLayerTransforms(NavKind.Push, progress = 1f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(0f, t.aboveTx, 1e-4f)
        assertEquals(-120f, t.belowTx, 1e-4f)
        assertEquals(0.1f, t.scrimAlpha, 1e-4f)
        assertEquals(1f, t.aboveAlpha, 1e-4f)
    }

    @Test
    fun ltrPushAtStartIsFullyOffscreen() {
        val t = navLayerTransforms(NavKind.Push, progress = 0f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(400f, t.aboveTx, 1e-4f)
        assertEquals(0f, t.belowTx, 1e-4f)
        assertEquals(0f, t.scrimAlpha, 1e-4f)
    }

    @Test
    fun rtlMirrorsTheSigns() {
        val t = navLayerTransforms(NavKind.Push, progress = 0.5f, widthPx = 400f, rtl = true, reduceMotion = false)
        assertEquals(-200f, t.aboveTx, 1e-4f)
        assertEquals(60f, t.belowTx, 1e-4f)
        assertEquals(0.05f, t.scrimAlpha, 1e-4f)
    }

    @Test
    fun popAtQuarterProgressEqualsPushAtThreeQuarters() {
        val pop = navLayerTransforms(NavKind.Pop, progress = 0.25f, widthPx = 400f, rtl = false, reduceMotion = false)
        val push = navLayerTransforms(NavKind.Push, progress = 0.75f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(push.aboveTx, pop.aboveTx, 1e-4f)
        assertEquals(push.belowTx, pop.belowTx, 1e-4f)
        assertEquals(push.scrimAlpha, pop.scrimAlpha, 1e-4f)
        assertEquals(push.aboveAlpha, pop.aboveAlpha, 1e-4f)
        // And the pop values themselves: above 3/4 off screen, below at −30 % × 3/4.
        assertEquals(100f, pop.aboveTx, 1e-4f)
        assertEquals(-90f, pop.belowTx, 1e-4f)
        assertEquals(0.075f, pop.scrimAlpha, 1e-4f)
    }

    @Test
    fun reducedMotionCrossfadesWithoutTranslationOrScrim() {
        val t = navLayerTransforms(NavKind.Push, progress = 0.5f, widthPx = 400f, rtl = false, reduceMotion = true)
        assertEquals(0f, t.aboveTx, 1e-4f)
        assertEquals(0f, t.belowTx, 1e-4f)
        assertEquals(0f, t.scrimAlpha, 1e-4f)
        assertEquals(0.5f, t.aboveAlpha, 1e-4f)
    }

    @Test
    fun reducedMotionDrivesAboveAlphaWithProgressForBothKinds() {
        // Under reduce the gesture drives above's alpha: 1 − progress on pop.
        val pop = navLayerTransforms(NavKind.Pop, progress = 0.25f, widthPx = 400f, rtl = false, reduceMotion = true)
        assertEquals(0.75f, pop.aboveAlpha, 1e-4f)
        val push = navLayerTransforms(NavKind.Push, progress = 0.75f, widthPx = 400f, rtl = false, reduceMotion = true)
        assertEquals(0.75f, push.aboveAlpha, 1e-4f)
    }

    @Test
    fun progressIsClampedToUnitRange() {
        val over = navLayerTransforms(NavKind.Push, progress = 2.5f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(0f, over.aboveTx, 1e-4f)
        val under = navLayerTransforms(NavKind.Push, progress = -1f, widthPx = 400f, rtl = false, reduceMotion = false)
        assertEquals(400f, under.aboveTx, 1e-4f)
    }

    // ------------------------------------------------------------ chrome

    @Test
    fun chromePlacementMatchesTheFourRowTable() {
        assertEquals(ChromeLayer.Fixed, chromePlacement(showBelow = true, showAbove = true))
        assertEquals(ChromeLayer.RidesBelow, chromePlacement(showBelow = true, showAbove = false))
        assertEquals(ChromeLayer.RidesAbove, chromePlacement(showBelow = false, showAbove = true))
        assertEquals(ChromeLayer.Hidden, chromePlacement(showBelow = false, showAbove = false))
    }

    @Test
    fun navMotionTokensCarryTheSpecValues() {
        // damping 1.0, stiffness (9.2334 / 0.5)² = 341.02; parallax 30 %; scrim 0.1;
        // reduced fade 150 ms; visibility threshold 0.001.
        assertEquals(1.0f, NavMotion.Push.damping, 1e-6f)
        assertEquals(0.50, NavMotion.Push.durationSec, 1e-9)
        assertEquals(341.0227f, NavMotion.Push.stiffness, 0.01f)
        assertEquals(0.3f, NavMotion.ParallaxFraction, 1e-6f)
        assertEquals(0.1f, NavMotion.ScrimMaxAlpha, 1e-6f)
        assertEquals(150, NavMotion.ReducedFadeMs)
        assertEquals(0.001f, NavMotion.VisibilityThreshold, 1e-6f)
    }
}
