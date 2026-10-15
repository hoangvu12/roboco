package sh.roboco.android.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The pointer plumbing and the settle of interactive IME dismiss
 * (ticket 133) on Robolectric at `w412dp-h915dp-mdpi` (1 dp = 1 px): a
 * 412×915 host with 68's real scroll chain and 133's observer, driven
 * through a fake [ImeControl] (`LocalImeControl`), whose `request`
 * delivers `onReady` synchronously with a recording handle. Robolectric
 * shows no IME, so the real [AndroidImeControl] and 56's blur-on-close
 * rule (which needs a visible-then-hidden IME) stay device/CI-only; the
 * state machine itself is `ImeDragSessionTest`'s (plain JUnit).
 *
 * The window is 915 px with the fake IME shown at 300 px, so the IME's
 * top edge is y 615 and the half line y 765; the finger maps through
 * `localToWindow` via the host's `onPlaced` coordinates, like the real
 * host. 81's tap-to-dismiss is not merged yet, so the "taps still fire"
 * regression runs against a plain `clickable` child (#585); a horizontal
 * child scroller stands in for 116.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class InteractiveImeDismissTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = TranscriptViewport()
    private val control = FakeImeControl()
    private var hostCoords: LayoutCoordinates? = null
    private var upConsumed: Boolean? = null
    private var childClicks = 0

    @Before
    fun setup() {
        compose.mainClock.autoAdvance = false
        InteractiveImeDismiss.active = false
        InteractiveImeDismiss.forceFallback = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalImeControl provides control) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onPlaced { hostCoords = it }
                            .interactiveImeDismiss(viewport) { hostCoords }
                            .then(rememberTranscriptScroll(viewport)),
                    ) {
                        // #585: a clickable child keeps its taps.
                        Box(Modifier.size(60.dp).clickable { childClicks++ })
                        // The consumption probe: a Final-pass child sees the
                        // up after the outer observer (Final is parent to
                        // child), so an unconsumed up proves the observer
                        // never consumed it.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .offset(y = 300.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                            val change = event.changes.firstOrNull() ?: continue
                                            if (!change.pressed) {
                                                upConsumed = change.isConsumed
                                                break
                                            }
                                        }
                                    }
                                },
                        )
                        // 116 stand-in: a horizontal child scroller deep in
                        // the IME region (y 860..900, the edge is 615).
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .offset(y = 860.dp)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Row { Box(Modifier.size(600.dp, 40.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame() // the first frame: layout, onPlaced, onSizeChanged
        compose.runOnIdle {
            viewport.setTopInset(68f)
            viewport.onFrameApplied(3000f, first = true)
        }
        compose.waitForIdle()
    }

    @After
    fun resetSharedState() {
        InteractiveImeDismiss.active = false
        InteractiveImeDismiss.forceFallback = false
    }

    // ------------------------------------------------------------- helpers

    /**
     * The drag of the ticket: down at y 300, eight moves down to [toY],
     * then a 0.1 s still hold — the release velocity is ~0. The up comes
     * in a separate injection so mid-gesture state can be asserted.
     */
    private fun dragIntoIme(toY: Float) {
        compose.onRoot().performTouchInput {
            val x = centerX
            down(Offset(x, 300f))
            val steps = 8
            repeat(steps) { i ->
                moveTo(Offset(x, 300f + (toY - 300f) * (i + 1) / steps), 100L)
            }
            repeat(4) { moveTo(Offset(x, toY), 25L) } // the still hold
        }
        compose.waitForIdle()
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    // -------------------------------------------------------- the drag ride

    /**
     * A drag from y 300 to 760: exactly one request (on the first or
     * second move at y ≥ 615 — `dragging` may lag one move), the IME
     * bottom follows `915 − y` clamped to [0, 300], and the release
     * settles back up (155 is above the half line) and finishes shown.
     */
    @Test
    fun aDragIntoTheImeTracksTheFingerAndReopensOnRelease() {
        dragIntoIme(toY = 760f)
        assertEquals("one controlWindowInsetsAnimation per gesture", 1, control.requests.size)
        val handle = requireNotNull(control.handle) { "the controller reported ready" }
        assertTrue("active while tracking", InteractiveImeDismiss.active)
        for (value in handle.sets) assertTrue("clamped to [0, 300]: $value", value in 0..300)
        assertEquals("the last tracking position is 915 − 760", 155, handle.sets.last())

        val settleStart = handle.sets.size
        release()

        // Motion.ImeSettle: 0.25 s critically damped — not instant, done
        // well within 600 ms (≈ 0.34 s from 155 at rest).
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertNull("still settling at 100 ms", handle.finishShown)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        val settleSets = handle.sets.subList(settleStart, handle.sets.size)
        assertTrue("the settle ran", settleSets.isNotEmpty())
        val first = settleSets.first()
        assertTrue("starts from the release position (155): $first", first in 155..200)
        var previous = 155
        for (value in settleSets) {
            assertTrue("monotonic toward the target: $value after $previous", value >= previous)
            assertTrue("never past the shown frame", value <= 300)
            previous = value
        }
        assertEquals("ends at the target", 300, settleSets.last())
        assertEquals("155 is above the half line: reopen", true, handle.finishShown)
        assertFalse("active clears after finish", InteractiveImeDismiss.active)
    }

    /** Ending at y 800 (ime 115 < 150): the release closes the IME. */
    @Test
    fun aReleasePastHalfClosesTheIme() {
        dragIntoIme(toY = 800f)
        assertEquals(1, control.requests.size)
        release()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("past half: close", false, control.handle!!.finishShown)
        assertFalse(InteractiveImeDismiss.active)
    }

    // ----------------------------------------------------------- no leakage

    /** A click (no drag) never requests control, and children keep their taps. */
    @Test
    fun aClickNeverRequestsControlAndChildrenStillGetTheirTaps() {
        compose.onRoot().performTouchInput { click(Offset(30f, 30f)) } // the clickable child
        compose.waitForIdle()
        assertTrue(control.requests.isEmpty())
        assertEquals("the child's tap still fires once (#585)", 1, childClicks)

        compose.onRoot().performTouchInput { click(Offset(centerX, 320f)) } // the probe strip
        compose.waitForIdle()
        assertTrue(control.requests.isEmpty())
        assertEquals("the observer never consumes: the probe's Final up is unconsumed", false, upConsumed)
    }

    /**
     * A horizontal drag inside a child scroller that ends inside the IME
     * region never requests control: `viewport.dragging` stays false (the
     * observer's start rule needs the transcript's own drag).
     */
    @Test
    fun aHorizontalChildDragNeverRequestsControl() {
        compose.onRoot().performTouchInput {
            swipe(Offset(100f, 880f), Offset(300f, 880f), 400)
        }
        compose.waitForIdle()
        assertFalse("the transcript never claimed a horizontal drag", viewport.dragging)
        assertTrue(control.requests.isEmpty())
        assertFalse(InteractiveImeDismiss.active)
    }

    // ------------------------------------------------------------- fixtures

    /** A fake ready-handle: records every set and the finish decision. */
    private class FakeHandle(
        override val hidden: Int = 0,
        override val shown: Int = 300,
    ) : ImeHandle {
        val sets = mutableListOf<Int>()
        var value = shown
        var finishShown: Boolean? = null

        override val current: Int get() = value
        override val done: Boolean get() = finishShown != null

        override fun set(bottom: Int) {
            value = bottom.coerceIn(hidden, shown)
            sets += value
        }

        override fun finish(shown: Boolean) {
            if (finishShown == null) finishShown = shown
        }
    }

    /**
     * The fake platform seam: the ticket's geometry (window 915, IME
     * shown 300 and settled), `request` delivering `onReady`
     * synchronously, a hide counter.
     */
    private class FakeImeControl : ImeControl {
        val requests = mutableListOf<FakeRequest>()
        var listener: ImeListener? = null
        var handle: FakeHandle? = null
        var hides = 0

        override fun windowHeight(): Int = 915
        override fun imeNow(): Int = 300
        override fun imeTarget(): Int = 300

        override fun request(l: ImeListener): ImeRequest {
            val r = FakeRequest()
            requests += r
            listener = l
            val h = handle ?: FakeHandle().also { handle = it }
            l.onReady(h)
            return r
        }

        override fun hide() {
            hides++
        }
    }

    private class FakeRequest : ImeRequest {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }
}
