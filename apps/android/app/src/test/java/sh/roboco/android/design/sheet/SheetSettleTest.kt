package sh.roboco.android.design.sheet

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's pure geometry and settle rule (ticket 17) — plain JUnit, no
 * Robolectric: `sheetAnchors` is pinned against the acceptance table
 * (H = 800, sb = 24: Large 34, Medium 400, Hidden 800, density 1) and
 * `settleTarget` against the release-rule table (25 % / 50 % /
 * 1200 dp/s), plus the sheet spring's settle-matched stiffness
 * (9.2334/0.5)² = 341.02.
 */
class SheetSettleTest {

    private val density: Density = Density(1f)
    private val flingPx = 1200f

    private fun anchors(
        parentHeight: Float = 800f,
        statusBar: Float = 24f,
        detents: List<SheetDetent> = listOf(SheetDetent.Medium, SheetDetent.Large),
    ) = sheetAnchors(parentHeight, statusBar, density, detents)

    // ------------------------------------------------------------- anchors

    @Test
    fun anchorsMatchTheAcceptanceTable() {
        val anchors = anchors()
        assertEquals(34f, anchors.positionOf(SheetDetent.Large), 0.001f)
        assertEquals(400f, anchors.positionOf(SheetDetent.Medium), 0.001f)
        assertEquals(800f, anchors.positionOf(SheetDetent.Hidden), 0.001f)
        assertEquals(3, anchors.size)
        assertEquals(34f, anchors.minPosition(), 0.001f)
        assertEquals(800f, anchors.maxPosition(), 0.001f)
    }

    @Test
    fun largeOnlySheetsSkipTheMediumAnchor() {
        val anchors = anchors(detents = listOf(SheetDetent.Large))
        assertFalse(anchors.hasPositionFor(SheetDetent.Medium))
        assertTrue(anchors.hasPositionFor(SheetDetent.Hidden))
        assertTrue(anchors.hasPositionFor(SheetDetent.Large))
        assertEquals(2, anchors.size)
    }

    @Test
    fun mediumIsDroppedOnTinyWindows() {
        // H × 0.5 = 30 ≤ sb + 10dp + 1px = 35: dropped.
        val anchors = anchors(parentHeight = 60f, statusBar = 24f)
        assertFalse(anchors.hasPositionFor(SheetDetent.Medium))
        assertEquals(2, anchors.size)
        // Just above the line (36 > 35): kept.
        assertTrue(anchors(parentHeight = 72f, statusBar = 24f).hasPositionFor(SheetDetent.Medium))
    }

    @Test
    fun missingAnchorsReportNanAndTheClosestAnchorIsNearest() {
        // A large-only sheet has no Medium anchor (the default set does).
        assertTrue(anchors(detents = listOf(SheetDetent.Large)).positionOf(SheetDetent.Medium).isNaN())
        val anchors = anchors()
        // The drag machinery asks for the closest anchor on settle.
        assertEquals(SheetDetent.Medium, anchors.closestAnchor(410f))
        assertEquals(SheetDetent.Large, anchors.closestAnchor(34f))
        assertEquals(SheetDetent.Hidden, anchors.closestAnchor(788f))
    }

    @Test
    fun equalAnchorsCompareEqualSoUpdateAnchorsCanSkip() {
        assertEquals(anchors(), anchors())
        assertEquals(anchors().hashCode(), anchors().hashCode())
    }

    // -------------------------------------------------------- settle rule

    @Test
    fun slowReleasePastAQuarterOfMediumToHiddenDismisses() {
        val anchors = anchors()
        // 20 % of Medium→Hidden (offset 480) stays; 26 % (504) dismisses.
        assertEquals(SheetDetent.Medium, settleTarget(480f, 0f, anchors, flingPx))
        assertEquals(SheetDetent.Hidden, settleTarget(504f, 0f, anchors, flingPx))
        // Exactly at the threshold crosses, exactly at the anchor stays.
        assertEquals(SheetDetent.Hidden, settleTarget(500f, 0f, anchors, flingPx))
        assertEquals(SheetDetent.Medium, settleTarget(400f, 0f, anchors, flingPx))
    }

    @Test
    fun slowReleaseBetweenLargeAndMediumCrossesAtHalf() {
        val anchors = anchors()
        // 49 % of Large→Medium (34 + 0.49 × 366) stays at Large; 51 % moves.
        assertEquals(SheetDetent.Large, settleTarget(34f + 0.49f * 366f, 0f, anchors, flingPx))
        assertEquals(SheetDetent.Medium, settleTarget(34f + 0.51f * 366f, 0f, anchors, flingPx))
    }

    @Test
    fun downwardFlingMovesOneStep() {
        val two = anchors()
        // 1300 dp/s down from Large → Medium (2 detents)…
        assertEquals(SheetDetent.Medium, settleTarget(34f, 1300f, two, flingPx))
        // …and dismisses a large-only sheet.
        val largeOnly = anchors(detents = listOf(SheetDetent.Large))
        assertEquals(SheetDetent.Hidden, settleTarget(34f, 1300f, largeOnly, flingPx))
        // From Medium (the first anchor below Medium is Hidden).
        assertEquals(SheetDetent.Hidden, settleTarget(400f, 1300f, two, flingPx))
    }

    @Test
    fun upwardFlingMovesOneStep() {
        val anchors = anchors()
        // 1300 dp/s up from Medium → Large.
        assertEquals(SheetDetent.Large, settleTarget(400f, -1300f, anchors, flingPx))
        // From Large there is nothing above: stays at Large.
        assertEquals(SheetDetent.Large, settleTarget(34f, -1300f, anchors, flingPx))
    }

    @Test
    fun belowFlingVelocityIsASlowRelease() {
        val anchors = anchors()
        // 1100 dp/s down at 20 % of Medium→Hidden: below the 1200 threshold,
        // so the fraction rule decides → Medium.
        assertEquals(SheetDetent.Medium, settleTarget(480f, 1100f, anchors, flingPx))
        assertEquals(SheetDetent.Medium, settleTarget(480f, -1100f, anchors, flingPx))
    }

    // --------------------------------------------------------------- spring

    @Test
    fun sheetSpringIsCriticallyDampedAtHalfASecondResponse() {
        // IosSpring("sheet", 0.50, 1.0): stiffness (9.2334/0.5)² = 341.02.
        assertEquals(341.02f, GlassSheetDefaults.Spring.stiffness, 0.01f)
        assertEquals(1.0f, GlassSheetDefaults.Spring.damping, 0.0f)
        assertEquals(0.50, GlassSheetDefaults.Spring.durationSec, 0.0)
    }
}
