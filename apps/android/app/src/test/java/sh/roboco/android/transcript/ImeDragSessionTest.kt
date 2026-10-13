package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interactive IME dismiss state machine (ticket 133), plain JUnit —
 * no Robolectric, no Compose: [ImeDragSession] is pure Kotlin behind an
 * [ImeControl] fake. The geometry is the ticket's: a 915-px window with
 * the IME shown at 300 px (`d = 1`, so px reads like dp), so the IME's
 * top edge sits at y 615 and the half line at y 765. The release math,
 * the gesture rules and the fallback live here; the pointer plumbing and
 * the settle spring are `InteractiveImeDismissTest`'s (Robolectric).
 */
class ImeDragSessionTest {

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

    /** A fake platform seam: mutable insets, a call log, a captured listener. */
    private class FakeControl(
        var ime: Int = 300,
        var target: Int = 300,
        private val windowH: Int = 915,
        private val autoReady: Boolean = false,
    ) : ImeControl {
        val requests = mutableListOf<FakeRequest>()
        var listener: ImeListener? = null
        var handle: FakeHandle? = null
        var hides = 0

        override fun windowHeight(): Int = windowH
        override fun imeNow(): Int = ime
        override fun imeTarget(): Int = target

        override fun request(l: ImeListener): ImeRequest {
            val r = FakeRequest()
            requests += r
            listener = l
            if (autoReady) {
                val h = handle ?: FakeHandle().also { handle = it }
                l.onReady(h)
            }
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

    /** One session over a fake control; `autoReady` delivers `onReady` inside `request`. */
    private class Rig(
        ime: Int = 300,
        target: Int = 300,
        autoReady: Boolean = false,
        forceFallback: Boolean = false,
    ) {
        val control = FakeControl(ime = ime, target = target, autoReady = autoReady)
        val session = ImeDragSession(control) { forceFallback }
    }

    // -------------------------------------------------------------- the math

    @Test
    fun theTargetMathClampsBetweenHiddenAndShown() {
        assertEquals(215, ImeDragSession.target(915, 700f, 0, 300))
        assertEquals(300, ImeDragSession.target(915, 500f, 0, 300)) // above shown
        assertEquals(0, ImeDragSession.target(915, 950f, 0, 300)) // below hidden
        assertEquals(150, ImeDragSession.target(915, 765f, 40, 340))
    }

    @Test
    fun theCloseRuleIsPastHalfOnly() {
        assertTrue(ImeDragSession.closes(149, 0, 300))
        assertFalse("exactly half reopens", ImeDragSession.closes(150, 0, 300))
        assertFalse(ImeDragSession.closes(215, 0, 300))
        assertTrue(ImeDragSession.closes(20, 40, 340))
        assertFalse(ImeDragSession.closes(190, 40, 340)) // exactly half of [40, 340]
    }

    @Test
    fun theFallbackRuleIsTheHalfLineOfTheImeAtTheDown() {
        assertTrue(ImeDragSession.fallbackCloses(915, 780f, 300)) // 135 < 150
        assertFalse(ImeDragSession.fallbackCloses(915, 700f, 300)) // 215 ≥ 150
    }

    // -------------------------------------------------------------- arming

    @Test
    fun theDownArmsOnlyWhileTheImeIsSettledAndVisible() {
        // imeNow 300 == imeTarget 300: armed, so a move into the IME requests.
        val armed = Rig()
        armed.session.onDown()
        armed.session.onMove(700f, dragging = true)
        assertEquals(1, armed.control.requests.size)

        // Animating up (150 → 300): never arms, no matter how deep the finger.
        val animating = Rig(ime = 150, target = 300)
        animating.session.onDown()
        animating.session.onMove(800f, dragging = true)
        assertTrue(animating.control.requests.isEmpty())

        // Hidden: never arms.
        val hidden = Rig(ime = 0, target = 0)
        hidden.session.onDown()
        hidden.session.onMove(915f, dragging = true)
        assertTrue(hidden.control.requests.isEmpty())
    }

    // --------------------------------------------------------- the start rule

    @Test
    fun movesAboveTheImeEdgeNeverRequestAndTheEdgeNeedsTheOwnDrag() {
        val r = Rig()
        r.session.onDown()
        r.session.onMove(500f, dragging = true) // above y 615
        assertTrue(r.control.requests.isEmpty())
        r.session.onMove(615f, dragging = false) // at the edge, but not the transcript's drag
        assertTrue(r.control.requests.isEmpty())
        r.session.onMove(615f, dragging = true) // the edge, dragging: request once
        assertEquals(1, r.control.requests.size)
        r.session.onMove(700f, dragging = true) // later moves never re-request
        assertEquals(1, r.control.requests.size)
    }

    @Test
    fun aGestureThatNeverReachedTheImeReleasesNone() {
        val r = Rig()
        r.session.onDown()
        r.session.onMove(500f, dragging = true)
        assertEquals(ImeDragSession.Release.None, r.session.onRelease())
        assertTrue(r.control.requests.isEmpty())
        assertFalse(r.session.active)
    }

    // ------------------------------------------------------------ tracking

    @Test
    fun onReadyAppliesTheLatestMoveAtOnceAndClamps() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(700f, dragging = true) // wanted; the request is pending, nothing applied
        val h = FakeHandle()
        r.session.onReady(h) // moves remembered before ready apply the latest at once
        assertEquals(listOf(215), h.sets)
        r.session.onMove(500f, dragging = true) // finger above the shown IME: clamped up
        assertEquals(listOf(215, 300), h.sets)
        r.session.onMove(950f, dragging = true) // finger past the window bottom: clamped down
        assertEquals(listOf(215, 300, 0), h.sets)
    }

    @Test
    fun movesWhileTrackingFollowTheFingerThroughTheController() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(700f, dragging = true)
        val h = FakeHandle()
        r.session.onReady(h)
        r.session.onMove(740f, dragging = true)
        r.session.onMove(766f, dragging = true)
        assertEquals(listOf(215, 175, 149), h.sets)
    }

    // ------------------------------------------------------------ the release

    @Test
    fun releaseSettlesClosedPastHalfAndReopensAtExactlyHalf() {
        // 215 (finger 700): above half → reopen to shown.
        val reopen = dragTo(700f)
        assertEquals(215, reopen.from)
        assertEquals(300, reopen.to)
        assertFalse(reopen.close)

        // 149 (finger 766): past half → close.
        val close = dragTo(766f)
        assertEquals(149, close.from)
        assertEquals(0, close.to)
        assertTrue(close.close)

        // 150 (finger 765): exactly half → reopen.
        val half = dragTo(765f)
        assertEquals(150, half.from)
        assertEquals(300, half.to)
        assertFalse(half.close)
    }

    @Test
    fun releaseBeforeReadyCancelsTheRequestAndUsesTheFallbackRule() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        assertTrue(r.session.active)
        assertEquals(ImeDragSession.Release.FallbackHide, r.session.onRelease()) // 915 − 780 = 135 < 150
        assertFalse(r.session.active)
        assertTrue(r.control.requests.single().cancelled)

        val r2 = Rig(autoReady = false)
        r2.session.onDown()
        r2.session.onMove(700f, dragging = true)
        assertEquals(ImeDragSession.Release.None, r2.session.onRelease()) // 215 ≥ 150
        assertTrue(r2.control.requests.single().cancelled)
    }

    @Test
    fun aRefusalBeforeReadySwitchesToTheDiscreteFallback() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        r.control.listener!!.onCancelled(beforeReady = true)
        assertFalse(r.session.active)
        assertEquals(ImeDragSession.Release.FallbackHide, r.session.onRelease())
    }

    @Test
    fun aSystemTakeoverAfterTrackingEndsTheGestureWithNoFurtherAction() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true) // finger deep in the IME
        r.session.onReady(FakeHandle())
        r.control.listener!!.onCancelled(beforeReady = false)
        assertFalse(r.session.active)
        assertEquals(ImeDragSession.Release.None, r.session.onRelease())
        // The gesture is dead: a later move cannot re-request the controller.
        r.session.onMove(700f, dragging = true)
        assertEquals(1, r.control.requests.size)
    }

    @Test
    fun aFloatingImeReportsNoInsetAndSwitchesToFallback() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        val h = FakeHandle(hidden = 0, shown = 0) // undocked: no bottom inset
        r.session.onReady(h)
        assertEquals(true, h.finishShown) // the controller is given back at once
        assertFalse(r.session.active)
        assertEquals(ImeDragSession.Release.FallbackHide, r.session.onRelease())
    }

    @Test
    fun theForcedFallbackNeverRequestsAndHidesOnlyPastHalf() {
        val r = Rig(forceFallback = true)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        assertTrue(r.control.requests.isEmpty())
        assertFalse(r.session.active)
        assertEquals(ImeDragSession.Release.FallbackHide, r.session.onRelease())

        val r2 = Rig(forceFallback = true)
        r2.session.onDown()
        r2.session.onMove(700f, dragging = true)
        assertEquals(ImeDragSession.Release.None, r2.session.onRelease())
    }

    // ------------------------------------------------------------ the flags

    @Test
    fun activeRunsFromTheRequestUntilSettledOrAFallbackRelease() {
        val r = Rig(autoReady = false)
        assertFalse(r.session.active)
        r.session.onDown()
        assertFalse(r.session.active)
        r.session.onMove(780f, dragging = true)
        assertTrue(r.session.active)
        r.session.onReady(FakeHandle())
        assertTrue(r.session.active) // through tracking
        val release = r.session.onRelease()
        assertTrue(release is ImeDragSession.Release.Settle)
        assertTrue("a settle still owns the IME", r.session.active)
        r.session.settled()
        assertFalse(r.session.active)

        val r2 = Rig(autoReady = false)
        r2.session.onDown()
        r2.session.onMove(780f, dragging = true)
        assertTrue(r2.session.active)
        r2.session.onRelease()
        assertFalse("a fallback release clears active at once", r2.session.active)

        val r3 = Rig(autoReady = false)
        r3.session.onDown()
        r3.session.onMove(780f, dragging = true)
        assertTrue(r3.session.active)
        r3.control.listener!!.onCancelled(beforeReady = true)
        assertFalse(r3.session.active)
    }

    @Test
    fun aDoneHandleReleasesThroughTheFallbackRule() {
        // The controller finished (the system took it back after finish):
        // the release is the discrete one, driven by the finger position.
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        val h = FakeHandle()
        r.session.onReady(h)
        h.finish(true) // done
        assertEquals(ImeDragSession.Release.FallbackHide, r.session.onRelease())
        assertFalse(r.session.active)
    }

    @Test
    fun cancelPendingCancelsTheRequestAndGivesAHeldHandleBackShown() {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(780f, dragging = true)
        r.session.cancelPending()
        assertTrue(r.control.requests.single().cancelled)
        assertFalse(r.session.active)

        val r2 = Rig(autoReady = false)
        r2.session.onDown()
        r2.session.onMove(780f, dragging = true)
        val h = FakeHandle()
        r2.session.onReady(h)
        r2.session.cancelPending() // host disposed mid-drag: never leak the controller
        assertEquals(true, h.finishShown)
    }

    // ------------------------------------------------------------- helpers

    /** A drag to [y] with a ready handle; returns the release decision's settle. */
    private fun dragTo(y: Float): ImeDragSession.Release.Settle {
        val r = Rig(autoReady = false)
        r.session.onDown()
        r.session.onMove(y, dragging = true)
        r.session.onReady(FakeHandle())
        return r.session.onRelease() as ImeDragSession.Release.Settle
    }
}
