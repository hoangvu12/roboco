package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.Motion

/**
 * [LargeTitleMetrics]' pure math (android-ui-parity #25), as a **plain JUnit**
 * class — no Robolectric runner, no composition, part of the merge check.
 * Every value is pinned by the ticket's acceptance criteria.
 */
class LargeTitleMetricsTest {

    @Test
    fun largeBaselineIs3515() {
        assertEquals(35.15f, LargeTitleMetrics.largeBaseline(), 0.01f)
        // The formula's pieces, from the font metrics (hhea 1005/−295):
        // 52 − 8 − 1.3·30 + 1.005·30.
        assertEquals(
            52f - 8f - GeistMetrics.LINE_HEIGHT * 30f + GeistMetrics.ASCENT * 30f,
            LargeTitleMetrics.largeBaseline(),
            1e-4f,
        )
        // And it moves with the row height / inset / size as written.
        assertEquals(60f - 8f - 39f + 30.15f, LargeTitleMetrics.largeBaseline(rowH = 60f), 1e-4f)
        assertEquals(52f - 12f - 39f + 30.15f, LargeTitleMetrics.largeBaseline(bottomInset = 12f), 1e-4f)
        assertEquals(52f - 8f - 1.3f * 17f + 1.005f * 17f, LargeTitleMetrics.largeBaseline(pt = 17f), 1e-4f)
    }

    @Test
    fun inlineAlphaRampsOverTwelveDpCentredOnTheBaseline() {
        assertEquals(0f, LargeTitleMetrics.inlineAlpha(0f), 1e-4f)
        assertEquals(0f, LargeTitleMetrics.inlineAlpha(29.15f), 1e-4f)
        assertEquals(0.5f, LargeTitleMetrics.inlineAlpha(35.15f), 1e-4f)
        assertEquals(1f, LargeTitleMetrics.inlineAlpha(41.15f), 1e-4f)
        assertEquals(1f, LargeTitleMetrics.inlineAlpha(100f), 1e-4f)
        // Linear between the ramp's ends, never negative, never above 1.
        assertEquals(0.25f, LargeTitleMetrics.inlineAlpha(32.15f), 1e-4f)
        assertEquals(0.75f, LargeTitleMetrics.inlineAlpha(38.15f), 1e-4f)
    }

    @Test
    fun snapTargetUsesThe50PercentThresholdAndEdgesNeverSnap() {
        assertNull(LargeTitleMetrics.snapTarget(0f))
        assertEquals(0f, LargeTitleMetrics.snapTarget(10f)!!)
        assertEquals(0f, LargeTitleMetrics.snapTarget(25.9f)!!)
        assertEquals(52f, LargeTitleMetrics.snapTarget(26f)!!)
        assertEquals(52f, LargeTitleMetrics.snapTarget(51.9f)!!)
        assertNull(LargeTitleMetrics.snapTarget(52f))
        assertNull(LargeTitleMetrics.snapTarget(-1f))
    }

    @Test
    fun collapseClampsToTheRowHeightAndEscapesItemZero() {
        assertEquals(0f, LargeTitleMetrics.collapse(0, 0f))
        assertEquals(30f, LargeTitleMetrics.collapse(0, 30f))
        assertEquals(52f, LargeTitleMetrics.collapse(0, 70f)) // clamped to L
        assertEquals(52f, LargeTitleMetrics.collapse(1, 0f)) // item 0 scrolled away
        assertEquals(0f, LargeTitleMetrics.collapse(0, -5f)) // never negative
        assertEquals(52f, LargeTitleMetrics.collapse(3, 0f))
    }

    @Test
    fun inlineMaxWidthUsesTheLargerExtentPlusTheGap() {
        // 412 − 2 × (76 + 8) = 244.
        assertEquals(412f - 2 * 84f, LargeTitleMetrics.inlineMaxWidth(412f, 60f, 76f), 1e-4f)
        // The larger side wins, whichever it is.
        assertEquals(412f - 2 * 84f, LargeTitleMetrics.inlineMaxWidth(412f, 76f, 20f), 1e-4f)
        // A custom gap.
        assertEquals(412f - 2 * 86f, LargeTitleMetrics.inlineMaxWidth(412f, 60f, 76f, gap = 10f), 1e-4f)
        // Never negative.
        assertEquals(0f, LargeTitleMetrics.inlineMaxWidth(100f, 60f, 76f))
        assertEquals(0f, LargeTitleMetrics.inlineMaxWidth(0f, 0f, 0f))
    }

    @Test
    fun accessoryTopRidesUpAndPinsAtTheNavBottom() {
        // Always, at rest: under the large title (68 + 52).
        assertEquals(120f, LargeTitleMetrics.accessoryTop(68f, 0f, inlineOnly = false), 1e-4f)
        // Half-collapsed rides with it.
        assertEquals(101f, LargeTitleMetrics.accessoryTop(68f, 19f, inlineOnly = false), 1e-4f)
        // Fully collapsed (c ≥ L, also the c > L overflow): pinned.
        assertEquals(68f, LargeTitleMetrics.accessoryTop(68f, 52f, inlineOnly = false), 1e-4f)
        assertEquals(68f, LargeTitleMetrics.accessoryTop(68f, 100f, inlineOnly = false), 1e-4f)
        // InlineOnly: always pinned, no large title to ride with.
        assertEquals(68f, LargeTitleMetrics.accessoryTop(68f, 0f, inlineOnly = true), 1e-4f)
        assertEquals(68f, LargeTitleMetrics.accessoryTop(68f, 52f, inlineOnly = true), 1e-4f)
    }

    @Test
    fun snapSpringIsCarriedAsAnIosSpringNearStiffness400() {
        // FOUNDATION-D1: no pasted stiffness literal — the snap goes through
        // Motion.LargeTitleSnap (0.4617 s critically damped), whose
        // settle-matched stiffness is the ticket's tunable ≈ 400.
        assertEquals(1f, Motion.LargeTitleSnap.damping)
        assertTrue(
            "stiffness ${Motion.LargeTitleSnap.stiffness} should be ≈ 400",
            Motion.LargeTitleSnap.stiffness in 399f..401f,
        )
        assertEquals(Motion.LargeTitleSnap.stiffness, LargeTitleDefaults.SnapStiffness, 1e-6f)
    }
}
