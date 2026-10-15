package sh.roboco.android.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * [SessionScaffold] and [OpenRevealLayers] (android-ui-parity #75):
 * Robolectric + `createComposeRule` at `w412dp-h900dp-xxhdpi` (1 dp = 3
 * px), the ticket's fixture (24 dp status bar, no nav bar — Robolectric
 * reports no insets, so both are pinned). The freeze discipline is the
 * repo's recipe: `mainClock.autoAdvance = false`, state writes through
 * `runOnUiThread` + `Snapshot.sendApplyNotifications()`, then frames.
 * The glass fallback tier keeps the capsule deterministic.
 *
 * The pure state machine is [OpenRevealTest]; the nav wiring
 * ([OpenReveal.shouldHold] at first composition, landing through ticket
 * 24's callbacks) is [SessionOpenNavTest]; the goldens are
 * [SessionScaffoldScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlassAndReduceMotion() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    /** Composes the content and settles the first frames (the glass flag flip included). */
    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(Appearance.Light) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * A state write from the test: `runOnUiThread` runs inline under
     * Robolectric, `sendApplyNotifications` delivers it to the Recomposer
     * (the frozen clock never idles the paused main looper on its own);
     * the following `advanceTimeBy`/`advanceTimeByFrame` runs the
     * recomposition and the effects.
     */
    private fun writeState(change: () -> Unit) {
        compose.runOnUiThread(change)
        Snapshot.sendApplyNotifications()
    }

    /** True when some pixel of the dp region has red below [limit] (text ink). */
    private fun regionHasInk(px: PixelMap, xDp: IntRange, yDp: IntRange, limit: Float = 0.5f): Boolean {
        val d = compose.density.density
        for (y in yDp) {
            for (x in xDp) {
                if (px[(x * d).toInt(), (y * d).toInt()].red < limit) return true
            }
        }
        return false
    }

    // ----------------------------------------------------- the loader

    /**
     * While `Holding`, the loader sits at the fixture position:
     * (195.5, 433.5)–(216.5, 454.5) — centre x 206 (the window centre),
     * centre y 444 (the safe-area centre `(68 + 900) / 2 − 40`), a 21 dp
     * box (12 × 1.75).
     */
    @Test
    fun whileHoldingTheLoaderSitsAtTheFixturePosition() {
        val reveal = OpenReveal().apply { begin(true) }
        host { SessionOpenFixture(reveal) }
        val bounds = compose.onNodeWithTag("session-loading").getBoundsInRoot()
        assertEquals(195.5f, bounds.left.value, 0.5f)
        assertEquals(433.5f, bounds.top.value, 0.5f)
        assertEquals(216.5f, bounds.right.value, 0.5f)
        assertEquals(454.5f, bounds.bottom.value, 0.5f)
    }

    // ------------------------------------------- the hold: a11y + touches

    /**
     * While `Holding`, the transcript is out of the semantics tree (UIKit
     * hides alpha-0 views) and a click at (206, 300) never reaches it; the
     * same click lands after the reveal.
     */
    @Test
    fun whileHoldingTheTranscriptIsHiddenAndUntouchable() {
        val reveal = OpenReveal().apply { begin(true) }
        var clicks = 0
        host { SessionOpenFixture(reveal, onTranscriptTap = { clicks++ }) }

        compose.onNodeWithTag("session-loading").assertExists()
        compose.onNodeWithTag("transcript").assertDoesNotExist()

        compose.onRoot().performTouchInput { click(Offset(206.dp.toPx(), 300.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the blocker swallowed the tap", 0, clicks)

        // Rows in, landed: the reveal runs to `Shown` (180 ms + slack).
        writeState {
            reveal.onFirstRows()
            reveal.onLanded()
        }
        compose.mainClock.advanceTimeBy(250)
        assertEquals(OpenReveal.Phase.Shown, reveal.phase)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
        compose.onNodeWithTag("transcript").assertExists()

        compose.onRoot().performTouchInput { click(Offset(206.dp.toPx(), 300.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the transcript is tappable again (allowUserInteraction)", 1, clicks)
    }

    // ------------------------------------------------- the reveal curve

    /**
     * The reveal is the 180 ms ease-out: at 96 ms (minus up to two frames
     * of launch slack) `progress` sits in [0.50, 0.80] — the ease-out
     * value is ≈ 0.720 at 96 ms and ≈ 0.516 at 64 ms. After 200 ms more
     * the phase is `Shown`, `progress` is 1 and the loader is gone.
     */
    @Test
    fun theRevealRunsThe180MsEaseOut() {
        val reveal = OpenReveal().apply { begin(true) }
        host { SessionOpenFixture(reveal) }
        writeState {
            reveal.onFirstRows()
            reveal.onLanded()
        }
        compose.mainClock.advanceTimeBy(96)
        val progress = reveal.progress
        assertTrue("progress at 96 ms in [0.50, 0.80]: $progress", progress in 0.50f..0.80f)

        compose.mainClock.advanceTimeBy(200)
        assertEquals(OpenReveal.Phase.Shown, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
        compose.onNodeWithTag("transcript").assertExists()
    }

    /**
     * Under reduced motion the reveal snaps: the frame after `Revealing`
     * is `Shown` with `progress == 1` — no intermediate value.
     */
    @Test
    fun reducedMotionSnapsTheReveal() {
        ReduceMotion.debugOverride = true
        val reveal = OpenReveal().apply { begin(true) }
        host { SessionOpenFixture(reveal) }
        writeState {
            reveal.onFirstRows()
            reveal.onLanded()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(OpenReveal.Phase.Shown, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
    }

    // -------------------------------------------- the header edge settle

    /**
     * The header is composed under `LocalReduceMotion provides true` while
     * `Holding` and `Revealing` (the edge settle), and under the real
     * value once `Shown` — with the outer value `false` throughout.
     */
    @Test
    fun theHeaderSettlesItsEdgeWhileHoldingAndRevealing() {
        val reveal = OpenReveal().apply { begin(true) }
        val headerSees = mutableListOf<Boolean>()
        val outerSees = mutableListOf<Boolean>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                outerSees += LocalReduceMotion.current
                SessionScaffold(
                    reveal = reveal,
                    header = {
                        headerSees += LocalReduceMotion.current
                        Box(Modifier.fillMaxWidth().height(68.dp).testTag("header-probe"))
                    },
                ) {
                    SessionOpenFixtureBody(reveal)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the header settles while Holding", headerSees.first())
        assertFalse("the outer value stays false", outerSees.first())

        // Rows in, landed: Revealing — the settle flag is still up.
        writeState {
            reveal.onFirstRows()
            reveal.onLanded()
        }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("still settled while Revealing", headerSees.last())
        assertTrue("no unsettled header frame before Shown", headerSees.dropLast(1).all { it })

        // The reveal completes: the header sees the real value again.
        compose.mainClock.advanceTimeBy(300)
        assertEquals(OpenReveal.Phase.Shown, reveal.phase)
        assertFalse("the settle override is gone after Shown", headerSees.last())
        assertFalse(outerSees.last())
    }

    // ------------------------------------------------------ no hold

    /**
     * Without a hold the loader never exists and the transcript is
     * visible at alpha 1 on the first frame (ink in the first paragraph's
     * band) — and it is tappable at once.
     */
    @Test
    fun withoutAHoldTheTranscriptIsVisibleAndTappableAtOnce() {
        val reveal = OpenReveal()
        var clicks = 0
        host { SessionOpenFixture(reveal, onTranscriptTap = { clicks++ }) }
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
        compose.onNodeWithTag("transcript").assertExists()

        val px = compose.onRoot().captureToImage().toPixelMap()
        assertTrue(
            "the fixture text draws at full alpha on the first frame",
            regionHasInk(px, xDp = 40..350, yDp = 70..88),
        )

        compose.onRoot().performTouchInput { click(Offset(206.dp.toPx(), 300.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    // ---------------------------------------------------- the give-up

    /**
     * The give-up through the scaffold alone: landed with no rows, still
     * `Holding` after 7 984 ms, revealed by 64 ms more (frame slack for
     * the effect launch), with `reportedOpen` faked — so a probe would be
     * suppressed.
     */
    @Test
    fun anEmptySessionGivesUpEightSecondsAfterLanding() {
        val reveal = OpenReveal().apply { begin(true) }
        host { SessionOpenFixture(reveal) }
        writeState { reveal.onLanded() }
        compose.mainClock.advanceTimeBy(7_984)
        assertEquals(OpenReveal.Phase.Holding, reveal.phase)
        assertFalse(reveal.reportedOpen)

        compose.mainClock.advanceTimeBy(64)
        assertTrue("the hold gave up: ${reveal.phase}", reveal.phase != OpenReveal.Phase.Holding)
        assertTrue(reveal.reportedOpen)
    }
}
