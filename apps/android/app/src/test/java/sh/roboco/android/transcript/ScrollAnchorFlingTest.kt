package sh.roboco.android.transcript

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlin.math.abs
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
 * Fling continuity across an anchor shift (ticket 130's Robolectric
 * acceptance item): on the 400×800 dp, mdpi (1 dp = 1 px) box of
 * [rememberTranscriptScroll] (68) with content 10 000 landed at the
 * bottom, a fast swipe toward older content flings on; a prepend that
 * shifts the offset by +2 000 dp must not disturb the decay — the fling
 * keeps its momentum because the shift is a plain `setOffset` write,
 * never a `ScrollableState` scroll that would contend the scroll mutex
 * and cancel it.
 *
 * The clock is frozen (`autoAdvance = false`) so the per-frame deltas
 * can be measured frame by frame; `advanceTimeByFrame()` is the 16 ms
 * display frame of the ticket's recipe.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class ScrollAnchorFlingTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = TranscriptViewport()

    @Before
    fun setup() {
        // Ticket 134's scroll indicator sits at the end of the scroll chain
        // and reads the theme for its colour, so the chain (like the host,
        // 65) now requires a RobocoTheme.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().then(rememberTranscriptScroll(viewport)))
            }
        }
        compose.waitForIdle() // the box lays out → setViewportHeight(800)
        compose.runOnIdle {
            viewport.setTopInset(68f)
            viewport.setBottomInset(100f)
            viewport.onFrameApplied(contentHeight = 10_000f, first = true)
        }
        compose.waitForIdle()
        assertEquals(9_300f, viewport.offset, 1e-4f) // 10 000 + 100 − 800
        compose.mainClock.autoAdvance = false
    }

    // ------------------------------------------------------------- helpers

    /** 100 rows of 100 dp (content 10 000), shifted by [shift] for the prepend frame. */
    private class ShiftedRows(
        private val rows: List<Triple<ULong, Float, Float>>,
        private val shift: Float,
    ) : AnchorRows {
        override fun rowAt(y: Float): AnchorRow? {
            if (rows.isEmpty()) return null
            val hit = rows.indexOfFirst { it.second + it.third > y }
            val i = if (hit < 0) rows.lastIndex else hit
            return AnchorRow(rows[i].first, rows[i].second + shift)
        }

        override fun yOf(key: ULong): Float? = rows.firstOrNull { it.first == key }?.second?.plus(shift)
    }

    private val rows: List<Triple<ULong, Float, Float>> = (0 until 100).map {
        Triple(it.toULong(), it * 100f, 100f)
    }

    // --------------------------------------------------------------- tests

    @Test
    fun anAnchorShiftKeepsTheFlingMomentum() {
        // The finger moves down 60 % of the box in 100 ms — 480 dp at
        // ~4 800 dp/s — and the release flings toward older content.
        compose.onRoot().performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 100)
        }
        compose.waitForIdle() // the drag releases follow, the fling starts
        assertFalse("the drag must release follow", viewport.following)
        assertTrue("the fling must be running", viewport.flinging)
        assertFalse("the fling never starts the spring", viewport.springRunning)

        // 5 frames of 16 ms; d1 is the offset change over the 5th.
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val beforeFrame5 = viewport.offset
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val d1 = viewport.offset - beforeFrame5
        assertTrue("the fling must keep moving (d1 = $d1)", abs(d1) > 1f)

        // The prepend apply, the `TranscriptState.apply` way: capture over
        // the old rows, hand the new height to 68, then the guarded
        // restore over the same rows shifted by +2 000 (20 rows of 100).
        val beforeShift = viewport.offset
        compose.runOnUiThread {
            val anchor = ScrollAnchoring.capture(viewport, ShiftedRows(rows, 0f))
                ?: error("the flinging, unfollowed viewport must capture an anchor")
            val needsAnchor = viewport.onFrameApplied(12_000f, first = false)
            if (needsAnchor) {
                ScrollAnchoring.restore(viewport, anchor, ShiftedRows(rows, 2_000f))
            }
        }
        assertEquals("the anchor shift moves the offset by +2 000", beforeShift + 2_000f, viewport.offset, 0.5f)
        assertTrue("the shift must not cancel the fling", viewport.flinging)

        // One more frame: the remaining momentum continues from the new
        // position — a relative scrollBy fling is undisturbed.
        val beforeFrame6 = viewport.offset
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val d2 = viewport.offset - beforeFrame6
        assertTrue(
            "d2 ($d2) must stay within 10 % of d1 ($d1): the shift must not disturb the decay",
            abs(d2 - d1) <= 0.1f * abs(d1),
        )
        assertTrue("the fling is still running", viewport.flinging)
        assertFalse("the shift never re-follows here", viewport.following)
    }
}
