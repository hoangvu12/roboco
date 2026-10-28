package sh.roboco.android.shell

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.sheet.GlassSheetDefaults

/**
 * The page sheet's pure half (android-ui-parity #31) — plain JUnit, no
 * Robolectric: the progress model, the recession scale, the release rule
 * (25 % / 1200 dp/s, the large-only case of ticket 17's settle), the
 * predictive-back mapping, and the [PageSheetDefaults] table — the shared
 * values pinned against [GlassSheetDefaults] so 17 and 31 stay one
 * system, plus the settle-matched spring stiffness (9.2334/0.5)² =
 * 341.02 (FOUNDATION-D1).
 */
class PageSheetMathTest {

    private val height = 880f
    private val threshold = 1200f

    // ----------------------------------------------------------- progress

    @Test
    fun progressIsOneAtRestAndZeroWhenGone() {
        assertEquals(1f, PageSheetMath.progress(0f, height, hidden = false), 0.001f)
        assertEquals(0f, PageSheetMath.progress(height, height, hidden = false), 0.001f)
        assertEquals(0.5f, PageSheetMath.progress(height / 2f, height, hidden = false), 0.001f)
    }

    @Test
    fun progressClampsOutsideTheRangeAndHandlesUnmeasuredSheets() {
        assertEquals(0f, PageSheetMath.progress(height * 1.5f, height, hidden = false), 0.001f)
        assertEquals(1f, PageSheetMath.progress(-50f, height, hidden = false), 0.001f)
        // NaN offset = not laid out yet; height 0 = never measured.
        assertEquals(0f, PageSheetMath.progress(Float.NaN, height, hidden = false), 0.001f)
        assertEquals(0f, PageSheetMath.progress(0f, 0f, hidden = false), 0.001f)
        // Hidden for handoff: 0 whatever the offset is.
        assertEquals(0f, PageSheetMath.progress(0f, height, hidden = true), 0.001f)
    }

    // ----------------------------------------------------------- recession

    @Test
    fun recededScaleRunsFromOneToNineTwoHundredths() {
        assertEquals(1f, PageSheetMath.recededScale(0f), 0.0001f)
        assertEquals(0.92f, PageSheetMath.recededScale(1f), 0.0001f)
        assertEquals(0.96f, PageSheetMath.recededScale(0.5f), 0.0001f)
        // The covered-sheet peek uses the same curve.
        for (i in 0..10) {
            val p = i / 10f
            assertEquals(1f - 0.08f * p, PageSheetMath.recededScale(p), 0.0001f)
        }
    }

    // -------------------------------------------------------- release rule

    @Test
    fun aDownwardFlingDismissesFromAnyOffset() {
        assertTrue(PageSheetMath.shouldDismiss(0f, height, threshold, threshold))
        assertTrue(PageSheetMath.shouldDismiss(0.10f * height, height, threshold + 1f, threshold))
    }

    @Test
    fun pastTheQuarterThresholdASlowReleaseDismisses() {
        val at = 0.25f * height
        assertTrue(PageSheetMath.shouldDismiss(at, height, 0f, threshold))
        assertTrue(PageSheetMath.shouldDismiss(0.30f * height, height, 500f, threshold))
    }

    @Test
    fun anUpwardFlingCancelsTheDismissEvenPastTheThreshold() {
        val at = 0.30f * height
        assertTrue("at just above -1200 the rule still dismisses", PageSheetMath.shouldDismiss(at, height, -1199f, threshold))
        assertFalse("past -1200 upward the sheet returns", PageSheetMath.shouldDismiss(at, height, -1200f, threshold))
        assertFalse(PageSheetMath.shouldDismiss(at, height, -3000f, threshold))
    }

    @Test
    fun belowTheQuarterThresholdASlowReleaseReturnsToRest() {
        assertFalse(PageSheetMath.shouldDismiss(0.20f * height, height, 0f, threshold))
        assertFalse(PageSheetMath.shouldDismiss(0.24f * height, height, 500f, threshold))
        assertFalse(PageSheetMath.shouldDismiss(0f, height, 0f, threshold))
    }

    @Test
    fun theThresholdComparisonsAreInclusiveOnBothSides() {
        // v ≥ threshold dismisses; the position threshold uses ≥ too.
        assertTrue(PageSheetMath.shouldDismiss(0f, height, threshold, threshold))
        assertFalse(PageSheetMath.shouldDismiss(0f, height, threshold - 1f, threshold))
        assertTrue(PageSheetMath.shouldDismiss(0.25f * height, height, -1199.5f, threshold))
    }

    // ---------------------------------------------------- predictive back

    @Test
    fun backProgressMapsToAQuarterOfTheHeight() {
        assertEquals(0f, PageSheetMath.backOffset(0f, height), 0.001f)
        assertEquals(0.125f * height, PageSheetMath.backOffset(0.5f, height), 0.001f)
        assertEquals(0.25f * height, PageSheetMath.backOffset(1f, height), 0.001f)
    }

    // ------------------------------------------------------------ defaults

    @Test
    fun sharedDefaultsAliasGlassSheetDefaults() {
        assertEquals(GlassSheetDefaults.LargeTopGap, PageSheetDefaults.TopInsetBelowStatusBar)
        assertEquals(GlassSheetDefaults.DismissFraction, PageSheetDefaults.DismissFraction)
        assertEquals(GlassSheetDefaults.FlingVelocity, PageSheetDefaults.DismissVelocity)
        assertEquals(GlassSheetDefaults.PredictiveBackTravel, PageSheetDefaults.PredictiveBackTravel)
        assertEquals(GlassSheetDefaults.ScrimAlphaLight, PageSheetDefaults.ScrimAlphaLight)
        assertEquals(GlassSheetDefaults.ScrimAlphaDark, PageSheetDefaults.ScrimAlphaDark)
        // The page-sheet-only values of the ticket's table.
        assertEquals(10.dp, PageSheetDefaults.TopInsetBelowStatusBar)
        assertEquals(38.dp, PageSheetDefaults.CornerRadius)
        assertEquals(0.92f, PageSheetDefaults.RecededScale)
        assertEquals(0.dp, PageSheetDefaults.RecessionTopOffset)
        assertEquals(10.dp, PageSheetDefaults.StackedPeek)
        assertEquals(704.dp, PageSheetDefaults.TabletMaxWidth)
        assertEquals("the split shell's breakpoint", 744.dp, PageSheetDefaults.TabletBreakpoint)
    }

    @Test
    fun theSpringIsGlassSheetDefaultsSpringWithAOnePxThreshold() {
        val spec: SpringSpec<Float> = PageSheetDefaults.spring()
        assertEquals(GlassSheetDefaults.Spring.damping, spec.dampingRatio, 0f)
        assertEquals(GlassSheetDefaults.Spring.stiffness, spec.stiffness, 0f)
        // Settle-matched (9.2334/0.5)² = 341.02, damping 1.0 — computed
        // through IosSpring, never pasted (decision FOUNDATION-D1).
        assertEquals(1f, spec.dampingRatio, 0.0001f)
        assertEquals(341.02f, spec.stiffness, 0.01f)
    }
}
