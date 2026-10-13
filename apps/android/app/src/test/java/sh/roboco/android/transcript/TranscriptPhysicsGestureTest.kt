package sh.roboco.android.transcript

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.design.RobocoTheme

/**
 * The iOS scroll physics of ticket 134 through the real pointer pipeline
 * of [rememberTranscriptScroll] (68, now with 134's defaults): the same
 * 400×800 dp mdpi box (1 dp = 1 px) with `topInset 68`, `bottomInset
 * 100` and content 3000 → `minOffset −68`, `maxOffset 2300`. The clock
 * is frozen (`autoAdvance = false`), so the rubber band's release
 * bounce is stepped frame by frame with `advanceTimeBy`.
 *
 * The content is wrapped in [RobocoTheme] because the chain's scroll
 * indicator (decision TRANSCRIPT-D3) reads ticket 01's theme for its
 * colour; the indicator test injects the modifier's fade state through
 * [rememberTranscriptScrollIndicatorState] and reads its alpha.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class TranscriptPhysicsGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = TranscriptViewport()

    /** The indicator's fade state, injected for the alpha assertions. */
    private var indicator: TranscriptScrollIndicatorState? = null

    @Before
    fun setup() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val state = rememberTranscriptScrollIndicatorState(viewport)
                indicator = state
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(rememberTranscriptScroll(viewport))
                        .transcriptScrollIndicator(viewport, state),
                )
            }
        }
        compose.waitForIdle() // the box lays out → setViewportHeight(800)
        compose.runOnIdle {
            viewport.setTopInset(68f)
            viewport.setBottomInset(100f)
            viewport.onFrameApplied(contentHeight = 3000f, first = true)
        }
        compose.waitForIdle()
        assertEquals(2300f, viewport.offset, 1e-4f)
        compose.mainClock.autoAdvance = false
    }

    // ------------------------------------------------------------- helpers

    /**
     * A 300 dp pull toward the top, from the resting top, held down.
     * Ends with the frozen-clock flush: the apply notification and one
     * frame are what let the indicator's `snapshotFlow` see `dragging`.
     */
    private fun pullAtTheTop() {
        compose.runOnIdle {
            viewport.releaseFollow()
            viewport.setOffset(viewport.minOffset)
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, 300.dp.toPx())) // the finger moves down; no up
        }
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // ------------------------------------------------- the rubber band

    @Test
    fun aPullPastTheTopRubberBandsAndSpringsBack() {
        pullAtTheTop()
        assertTrue("a 300 dp pull must band past the top", viewport.offset < viewport.minOffset)

        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle() // the release starts the bounce
        assertTrue("the bounce counts as flinging", viewport.flinging)

        // A 300 dp pull (f ≈ 136.8 dp) snaps back on tick 24 = 0.4 s.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("the band must spring back to the resting top", viewport.minOffset, viewport.offset, 0.5f)
        assertFalse(viewport.flinging)
        assertFalse("no frame loop is left running", viewport.springRunning)
    }

    @Test
    fun aHardFlingTowardTheTailReFollowsAndSettlesAtTheTail() {
        compose.runOnIdle {
            viewport.releaseFollow()
            viewport.setOffset(1500f)
        }
        compose.waitForIdle()
        // 640 px in 100 ms ≈ 6400 dp/s: it coasts ≈ 3200 dp, far past the
        // 800 dp to the tail, so the fling hits the end and the edge
        // bounce runs — R7 re-latches during it, iOS parity.
        compose.onRoot().performTouchInput {
            swipeUp(startY = bottom * 0.9f, endY = bottom * 0.1f, durationMillis = 100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6000)
        compose.waitForIdle()
        assertTrue("R7 must re-follow as the fling reaches the tail", viewport.following)
        assertEquals(2300f, viewport.offset, 0.5f)
        assertFalse("waitForIdle returns: no frame loop is left running", viewport.flinging)
    }

    // ------------------------------------------------ the scroll indicator

    @Test
    fun theIndicatorShowsWhileScrollingAndFadesOutAfterQuiet() {
        pullAtTheTop()
        compose.waitForIdle()
        // While the finger holds the 300 dp pull, the thumb is fully visible.
        assertEquals(1f, indicator!!.alpha.value, 1e-3f)

        // The release bounces for ≈ 0.4 s (flinging) — drive the clock until
        // the bounce ends, then measure from there.
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle() // the release starts the bounce
        assertTrue("the release must start the bounce", viewport.flinging)
        var guard = 0
        while (viewport.flinging && guard++ < 120) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertFalse("the bounce must end within 2 s", viewport.flinging)

        // The thumb holds 500 ms after scrolling stops: 400 ms after the
        // bounce ends it is still up…
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("400 ms after the bounce ends the thumb is still visible", 1f, indicator!!.alpha.value, 1e-3f)

        // …and 1 s after it ends the hold (500 ms) + fade (250 ms) are done.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("1 s after the bounce ends the thumb is gone", 0f, indicator!!.alpha.value, 1e-3f)
    }

    @Test
    fun theFollowSpringDoesNotShowTheIndicator() {
        compose.mainClock.advanceTimeBy(1500) // any initial fade settles
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertEquals(0f, indicator!!.alpha.value, 1e-3f)

        // A programmatic move (the follow spring) is not user scrolling:
        // UIKit shows indicators only for user scrolling.
        compose.runOnIdle {
            viewport.releaseFollow()
            viewport.setOffset(1500f) // a real 800 dp spring travel
            viewport.scrollToBottom(animated = true)
            Snapshot.sendApplyNotifications() // the frozen clock flushes here
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // the spring settles (68)
        compose.waitForIdle()
        assertEquals(2300f, viewport.offset, 0.5f)
        assertEquals("the follow spring must not show the thumb", 0f, indicator!!.alpha.value, 1e-3f)
    }
}
