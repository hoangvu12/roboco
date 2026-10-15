package sh.roboco.android.transcript

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
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
 * The gesture plumbing of [rememberTranscriptScroll] (ticket 68) on a
 * bare 400×800 dp box at mdpi (1 dp = 1 px, so injected gestures and dp
 * assertions read the same): the first frame with rows lands at the
 * bottom, a drag past touch slop releases follow, the release and
 * momentum re-latch rules run through the real pointer pipeline, and the
 * spring's frame driver settles — a looping driver would never go idle.
 * No core and no rows: this tests only where the viewport sits.
 *
 * The Compose UI test frame clock is virtual, not `System.nanoTime()`, so
 * the first spring tick's `dt` clamps to 0 and later ticks advance at one
 * frame each; per-tick offsets are pinned by [TranscriptViewportTest]
 * with the injected clock, not here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class TranscriptScrollGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = TranscriptViewport()

    @Before
    fun setup() {
        // Ticket 134's scroll indicator sits at the end of the scroll chain
        // and reads the theme for its colour, so the chain (like the host,
        // 65) now requires a RobocoTheme — as every transcript test already
        // provided.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().then(rememberTranscriptScroll(viewport)))
            }
        }
        compose.waitForIdle() // the box lays out → setViewportHeight(800)
        compose.runOnIdle {
            viewport.setTopInset(68f)
            viewport.setBottomInset(100f)
            viewport.onFrameApplied(contentHeight = 3000f, first = true)
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- helpers

    /**
     * The iOS regression gesture (`iosui:SessionFlowTests.swift:474-490`):
     * a slow drag from [fromY] to [toY] (fractions of the height) over
     * [durationMillis], then a 0.1 s still hold and the release — the
     * finger's velocity at the up is ~0.
     */
    private fun slowDrag(fromY: Float, toY: Float, durationMillis: Long = 1000) {
        compose.onRoot().performTouchInput {
            val startY = height * fromY
            val endY = height * toY
            val steps = 8
            val stepDelay = durationMillis / steps
            val stepDistance = (endY - startY) / steps
            down(Offset(centerX, startY))
            repeat(steps) { i ->
                moveTo(Offset(centerX, startY + stepDistance * (i + 1)), stepDelay)
            }
            repeat(4) { moveTo(Offset(centerX, endY), 25) } // the 0.1 s still hold
            up()
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    fun theFirstFrameWithRowsLandsAtTheBottom() {
        assertEquals(2300f, viewport.offset, 1e-4f)
        assertEquals(0f, viewport.distanceFromBottom, 1e-4f)
        assertTrue(viewport.following)
        assertFalse("the landing never starts the spring", viewport.springRunning)
    }

    @Test
    fun aSlowDragUpWhileStreamingStaysPut() {
        // 30 % → 75 % of the height: the finger drags down, toward older
        // content, ~360 dp.
        slowDrag(fromY = 0.3f, toY = 0.75f)
        assertFalse("the drag must release follow", viewport.following)
        assertTrue("distance ${viewport.distanceFromBottom} must be > 140", viewport.distanceFromBottom > 140f)

        // Five more frames of +200 dp each while streaming: the offset
        // stays put (R4 — not following, no anchor in this ticket).
        val afterDrag = viewport.offset
        compose.runOnIdle {
            var content = 3000f
            repeat(5) {
                content += 200f
                viewport.onFrameApplied(content, first = false)
            }
        }
        compose.waitForIdle()
        assertEquals(afterDrag, viewport.offset, 0.01f)
        assertFalse(viewport.following)
    }

    @Test
    fun aStillReleaseNearTheTailReFollowsButAFlickDoesNot() {
        // From the bottom: a slow 40 dp drag down, then a still release.
        slowDrag(fromY = 0.7f, toY = 0.75f)
        assertTrue("a still release within 70 dp re-follows", viewport.following)

        compose.runOnIdle { viewport.scrollToBottom(animated = false) }
        compose.waitForIdle()

        // A 40 dp flick down in 80 ms: 500 dp/s > 50 — no re-follow.
        compose.onRoot().performTouchInput {
            swipeWithVelocity(
                start = Offset(centerX, height * 0.7f),
                end = Offset(centerX, height * 0.75f),
                endVelocity = 500f,
                durationMillis = 80,
            )
        }
        compose.waitForIdle()
        assertFalse("a flick away from the tail must not re-follow", viewport.following)
    }

    @Test
    fun aTapWithoutMovementNeverReleasesFollow() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()
        assertTrue(viewport.following)
        assertFalse("a tap never passes the drag slop", viewport.dragging)
    }

    @Test
    fun anAnimatedScrollToBottomSettlesAndTheFrameDriverGoesIdle() {
        compose.runOnIdle {
            viewport.releaseFollow()
            viewport.setOffset(0f) // a 2300 dp gap
            viewport.scrollToBottom(animated = true)
        }
        compose.waitForIdle() // a looping frame driver would never go idle
        assertTrue(viewport.following)
        assertEquals(2300f, viewport.offset, 0.5f)
        assertFalse(viewport.springRunning)
    }
}
