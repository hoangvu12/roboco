package sh.roboco.android.transcript

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
import uniffi.roboco_core.Scroller

/**
 * UIKit's nested-scroll-view axis lock, as Compose provides it (ticket
 * 116): a vertical `scrollable` parent hosting the synthetic scroller at
 * (0, 100) in a `w320dp-h480dp-mdpi` window, so the touch slop is 8 dp =
 * 8 px and each 10 px step below crosses it on the first move.
 *
 * The contract: before the slop nothing moves; a mostly-horizontal drag
 * over an overflowing scroller scrolls only the scroller; a
 * mostly-vertical drag (or any drag over a fitting scroller, which cannot
 * capture) scrolls only the transcript; a gesture that starts horizontal
 * and turns vertical never moves the transcript for its whole length —
 * the child's detector wins the slop and consumes every later change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class ScrollerAxisLockTest {

    @get:Rule
    val compose = createComposeRule()

    private var consumed = 0f
    private val scrollerState = ScrollState(0)

    /** The synthetic layer: black content 600 dp wide. */
    private val drawLayer: DrawScope.() -> Unit = {
        drawRect(Color.Black, size = Size(600.dp.toPx(), 40.dp.toPx()))
    }

    @Before
    fun reset() {
        consumed = 0f
    }

    // --------------------------------------------------------------- host

    private fun host(contentWidth: Float) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .scrollable(
                            state = remember {
                                ScrollableState { px -> consumed += px; px }
                            },
                            orientation = Orientation.Vertical,
                        ),
                ) {
                    ScrollerViewport(
                        Scroller(0f, 100f, 200f, 40f, contentWidth),
                        scrollerState,
                        drawLayer = drawLayer,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    fun aMostlyHorizontalDragScrollsOnlyTheScroller() {
        host(600f)
        compose.onNodeWithTag(ScrollerDefaults.TestTag).performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16L)
                moveBy(Offset(-10f, 2f))
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("the scroller did not scroll: ${scrollerState.value}", scrollerState.value > 0)
        assertEquals("the transcript consumed the horizontal drag", 0f, consumed, 0f)
    }

    @Test
    fun aMostlyVerticalDragScrollsOnlyTheTranscript() {
        host(600f)
        compose.onNodeWithTag(ScrollerDefaults.TestTag).performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16L)
                moveBy(Offset(2f, -10f))
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("the scroller moved", 0, scrollerState.value)
        assertTrue("the transcript did not consume the vertical drag: $consumed", consumed != 0f)
    }

    @Test
    fun aDragThatStartsHorizontalNeverMovesTheTranscript() {
        host(600f)
        compose.onNodeWithTag(ScrollerDefaults.TestTag).performTouchInput {
            down(center)
            repeat(5) {
                advanceEventTime(16L)
                moveBy(Offset(-10f, 0f))
            }
            repeat(10) {
                advanceEventTime(16L)
                moveBy(Offset(0f, -10f))
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("the scroller did not scroll: ${scrollerState.value}", scrollerState.value > 0)
        assertEquals("the gesture turned vertical and moved the transcript", 0f, consumed, 0f)
    }

    @Test
    fun aFittingScrollerNeverCapturesTheDrag() {
        host(150f)
        compose.onNodeWithTag(ScrollerDefaults.TestTag).performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16L)
                moveBy(Offset(-10f, -3f))
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("the transcript did not get the drag: $consumed", consumed != 0f)
        assertEquals(0, scrollerState.value)
    }
}
