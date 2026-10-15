package sh.roboco.android.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull-to-refresh math (android-ui-parity #48): the iOS rubber band,
 * its inverse and the indicator's alpha/geometry, pinned against the
 * ticket's reference table (UIKit's `UIScrollView` resistance with
 * `c = 0.55`, values quoted from the UIScrollView math). Plus the ticket's
 * source scans: no reduce-motion branch in `design/PullToRefresh.kt`
 * (ticket 04's 13-site rule — the refresh control is not one of them) and
 * no Material import in either new file.
 *
 * Plain JUnit: `PullToRefreshMath` is pure and touches nothing Android.
 */
class PullToRefreshMathTest {

    // ------------------------------------------------------------------ the band

    @Test
    fun rubberBandMatchesUIKitReferenceValues() {
        // The ticket's table: drag 100 @ D 800, drag 300 @ D 700.
        assertEquals(51.462f, PullToRefreshMath.rubberBand(100f, 800f), 0.01f)
        assertEquals(133.526f, PullToRefreshMath.rubberBand(300f, 700f), 0.01f)
        // drag 50 @ D 700 and drag 200 @ D 800, from the same table.
        assertEquals(26.460f, PullToRefreshMath.rubberBand(50f, 700f), 0.01f)
        assertEquals(96.703f, PullToRefreshMath.rubberBand(200f, 800f), 0.01f)
    }

    @Test
    fun rubberBandSurvivesDegenerateViewports() {
        // v clamps to ≥ 1: no division by zero, no NaN, no throw.
        for (drag in listOf(0f, 10f, 1000f)) {
            val out = PullToRefreshMath.rubberBand(drag, 0f)
            assertFalse(out.isNaN())
            assertTrue(out.isFinite())
        }
        // Negative drag clamps to 0: the band never opens backwards.
        assertEquals(0f, PullToRefreshMath.rubberBand(-50f, 700f), 0f)
        // A saturating drag converges on the viewport height without NaN.
        val huge = PullToRefreshMath.rubberBand(1e12f, 800f)
        assertTrue(huge.isFinite())
        assertTrue(huge <= 800f)
    }

    // ------------------------------------------------------------------ the inverse

    @Test
    fun dragForMatchesUIKitReferenceValues() {
        // The ticket's table: offset 80 @ D 800 (≈ 162 dp of finger travel
        // for the release threshold).
        assertEquals(161.616f, PullToRefreshMath.dragFor(80f, 800f), 0.01f)
        assertEquals(164.223f, PullToRefreshMath.dragFor(80f, 700f), 0.01f)
        assertEquals(119.318f, PullToRefreshMath.dragFor(60f, 700f), 0.01f)
    }

    @Test
    fun dragForInvertsTheBand() {
        for (x in listOf(10f, 120f, 400f)) {
            val offset = PullToRefreshMath.rubberBand(x, 800f)
            assertEquals(x, PullToRefreshMath.dragFor(offset, 800f), 0.01f)
        }
    }

    @Test
    fun dragForClampsTheOffsetBelowTheViewport() {
        // offset ≥ viewport clamps to v − 1: the band never fully opens,
        // and the inverse of the clamped offset is finite.
        val clamped = PullToRefreshMath.dragFor(5000f, 800f)
        assertTrue(clamped.isFinite())
        assertTrue(clamped > 0f)
        // Zero and negative offsets are no drag at all.
        assertEquals(0f, PullToRefreshMath.dragFor(0f, 800f), 0f)
        assertEquals(0f, PullToRefreshMath.dragFor(-10f, 800f), 0f)
    }

    // ------------------------------------------------------- the indicator helpers

    @Test
    fun indicatorAlphaFollowsThePhaseTable() {
        // Idle: fades in with offset / threshold.
        assertEquals(0.5f, PullToRefreshMath.indicatorAlpha(PullPhase.Idle, 40f, 80f, 60f), 1e-3f)
        // Refreshing: full at the hold.
        assertEquals(1f, PullToRefreshMath.indicatorAlpha(PullPhase.Refreshing, 60f, 80f, 60f), 1e-3f)
        // Closing: fades out as the gap closes.
        assertEquals(0.5f, PullToRefreshMath.indicatorAlpha(PullPhase.Closing, 30f, 80f, 60f), 1e-3f)
        // Clamped on both ends.
        assertEquals(0f, PullToRefreshMath.indicatorAlpha(PullPhase.Idle, -5f, 80f, 60f), 1e-3f)
        assertEquals(1f, PullToRefreshMath.indicatorAlpha(PullPhase.Idle, 400f, 80f, 60f), 1e-3f)
        assertEquals(0f, PullToRefreshMath.indicatorAlpha(PullPhase.Closing, 0f, 80f, 60f), 1e-3f)
    }

    @Test
    fun indicatorCentreSitsInTheMiddleOfTheGap() {
        // At hold with topInset 24: navBottom 68, gap 68…128, centre 98.
        assertEquals(98f, PullToRefreshMath.indicatorCenterY(68f, 60f), 1e-3f)
        assertEquals(68f, PullToRefreshMath.indicatorCenterY(68f, 0f), 1e-3f)
    }

    // ---------------------------------------------------------------- source scans

    /**
     * Ticket 48's acceptance: the control adds **no** reduce-motion branch
     * of its own (ticket 04's 13-site rule; the system "Remove animations"
     * snap comes free from `MotionDurationScale`, ticket 11 owns the static
     * cells) — `design/PullToRefresh.kt` must not mention the local.
     */
    @Test
    fun pullToRefreshHasNoReduceMotionBranch() {
        val file = File("src/main/java/sh/roboco/android/design/PullToRefresh.kt")
        assertTrue("source not found: ${file.path}", file.isFile)
        val offenders = file.readLines().filter { it.contains("LocalReduceMotion") }
        assertTrue("design/PullToRefresh.kt branches on LocalReduceMotion: $offenders", offenders.isEmpty())
    }

    /** The ticket's Material scan: neither new file imports `androidx.compose.material`. */
    @Test
    fun noMaterialImportsInTheNewSources() {
        val files = listOf(
            "src/main/java/sh/roboco/android/design/PullToRefresh.kt",
            "src/main/java/sh/roboco/android/sessions/Refresher.kt",
        )
        for (path in files) {
            val file = File(path)
            assertTrue("source not found: $path", file.isFile)
            val offenders = file.readLines().filter { it.contains("androidx.compose.material") }
            assertTrue("$path imports Material: $offenders", offenders.isEmpty())
        }
    }
}
