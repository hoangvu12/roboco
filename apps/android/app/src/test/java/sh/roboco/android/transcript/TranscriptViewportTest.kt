package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll model's pure rules (ticket 68), plain JUnit — no Robolectric
 * and no core: every input is a plain float, the spring runs on an
 * injected clock, and Compose snapshot state needs no Android framework.
 * The geometry is the ticket's worked example: `topInset 68`,
 * `bottomInset 100`, `viewportHeight 800`, so content 3000 gives
 * `maxOffset 2300` and content 500 rests at `-68`. The spring numbers are
 * the ticket's reference table (`TranscriptViewportTest` tolerance 1e-3
 * for the curve, 0 for snaps).
 */
class TranscriptViewportTest {

    // ------------------------------------------------------------- helpers

    /** The worked example's geometry (68: [newViewport], 68: [tailGap]). */
    private fun newViewport(): TranscriptViewport {
        val vp = TranscriptViewport { T0 }
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.setViewportHeight(800f)
        return vp
    }

    /**
     * A landed viewport with a running spring and a 100 dp gap: the first
     * frame (content 3000) lands at `offset = 2300`, then a streaming
     * frame (content 3100) moves `maxOffset` to 2400 and starts the
     * spring. Ticks are then driven at [T0] + n·[STEP60] (or [STEP120]).
     */
    private fun tailGap100(): TranscriptViewport {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.onFrameApplied(3100f, first = false)
        assertTrue("the streaming frame must start the spring", vp.springRunning)
        return vp
    }

    /**
     * A drag released at [distance] dp from the tail with the finger at
     * [fingerVelocityY] dp/s (positive = finger down): lands at the tail,
     * drags [distance] down, releases.
     */
    private fun releasedAt(distance: Float, fingerVelocityY: Float): TranscriptViewport {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.onPointerDown()
        vp.scrollByUser(-distance)
        vp.onPointerUp(fingerVelocityY)
        return vp
    }

    // ------------------------------------------------------- construction

    @Test
    fun constructionDefaults() {
        val vp = TranscriptViewport()
        assertTrue("following is on by default (R1)", vp.following)
        assertFalse(vp.springRunning)
        assertFalse(vp.tracking)
        assertFalse(vp.dragging)
        assertFalse(vp.flinging)
        assertFalse(vp.glide)
        assertEquals(0f, vp.offset, 0f)
        assertEquals(0f, vp.viewportHeight, 0f)
        assertEquals(0f, vp.topInset, 0f)
        assertEquals(0f, vp.bottomInset, 0f)
        assertEquals(0f, vp.contentHeight, 0f)
        assertEquals(0f, vp.minOffset, 0f)
        assertEquals(0f, vp.maxOffset, 0f)
        assertEquals(0f, vp.scrollY, 0f)
        assertEquals(0f, vp.distanceFromBottom, 0f)
    }

    @Test
    fun theRangeFormulasMatchIos() {
        val vp = newViewport()
        vp.setContentHeight(3000f)
        assertEquals(2300f, vp.maxOffset, 1e-4f)
        vp.setContentHeight(500f) // short content rests at the top
        assertEquals(-68f, vp.maxOffset, 1e-4f)
        // Back to 3000 (maxOffset 2300): 2300 − 1234 = 1066 from the tail.
        vp.setContentHeight(3000f)
        vp.setOffset(1234f)
        assertEquals(1302f, vp.scrollY, 1e-4f) // offset + topInset
        assertEquals(1066f, vp.distanceFromBottom, 1e-4f)
        assertEquals(-68f, vp.minOffset, 1e-4f)
    }

    // ------------------------------------------------------ frame applies

    @Test
    fun aFirstFrameLandsAtTheBottomIgnoringFollowAndTracking() {
        val vp = newViewport()
        vp.releaseFollow() // even when following was false …
        vp.onPointerDown() // … and a finger rests on the list
        val needsAnchor = vp.onFrameApplied(3000f, first = true)
        assertEquals(2300f, vp.offset, 1e-4f)
        assertFalse("the landing never starts the spring", vp.springRunning)
        assertFalse("first frames never need an anchor", needsAnchor)
        assertFalse("the snap does not touch following", vp.following)
        assertTrue(vp.tracking)
    }

    @Test
    fun anEmptyFirstFrameThenARowsFrameLandsAtTheBottom() {
        val vp = newViewport()
        vp.onFrameApplied(16f, first = true) // an empty frame: 16 dp of breathing room
        assertEquals(-68f, vp.offset, 1e-4f) // short content rests at the top
        vp.onFrameApplied(3000f, first = true) // the previous frame had 0 rows → still first
        assertEquals(2300f, vp.offset, 1e-4f)
    }

    @Test
    fun aNonFirstFrameSpringsWhileFollowingAndReturnsNoAnchor() {
        val vp = newViewport()
        assertFalse(vp.onFrameApplied(3000f, first = true))
        assertFalse(vp.springRunning)
        assertFalse("following frames never need an anchor", vp.onFrameApplied(3100f, first = false))
        assertTrue("R3: not first + following + not tracking → spring", vp.springRunning)
        vp.stopSpring()

        vp.onPointerDown()
        vp.onFrameApplied(3200f, first = false) // R3: while tracking, no spring
        assertFalse(vp.springRunning)

        // R4: not following → needsAnchor, and the offset is left alone.
        val quiet = newViewport()
        quiet.onFrameApplied(3000f, first = true)
        quiet.releaseFollow()
        assertTrue(quiet.onFrameApplied(3200f, first = false))
        assertEquals(2300f, quiet.offset, 0f)
    }

    /** Wave-10 42 (upstream #981): a re-wrap after a width change pins to the tail while following. */
    @Test
    fun aWidthChangeWhileFollowingPinsToTheTailInsteadOfSpringing() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.noteWidthChange()
        vp.onFrameApplied(4200f, first = false) // narrower: every row re-wrapped taller
        assertFalse("no spring from the old geometry", vp.springRunning)
        assertEquals(vp.maxOffset, vp.offset, 0f)
        // The mark is consumed: the next streaming frame springs as before.
        vp.onFrameApplied(4300f, first = false)
        assertTrue(vp.springRunning)

        // Not following: the offset is left alone (130's anchor keeps the reading place).
        val reading = newViewport()
        reading.onFrameApplied(3000f, first = true)
        reading.releaseFollow()
        reading.noteWidthChange()
        assertTrue(reading.onFrameApplied(4200f, first = false))
        assertEquals(2300f, reading.offset, 0f)
    }

    // -------------------------------------------------------- tail spring

    @Test
    fun springTicksMatchTheSettleCurveAt60Hertz() {
        val vp = tailGap100()
        assertTrue(vp.springTick(T0 + STEP60))
        assertEquals(2323.4072f, vp.offset, 1e-3f) // +23.4072
        assertTrue(vp.springTick(T0 + 2 * STEP60))
        assertEquals(2341.3354f, vp.offset, 1e-3f) // +41.3354
        assertTrue(vp.springTick(T0 + 3 * STEP60))
        assertEquals(2355.0671f, vp.offset, 1e-3f) // +55.0671

        for (n in 4..20) assertTrue("tick $n should still move", vp.springTick(T0 + n * STEP60))
        assertFalse("tick 21 snaps (the gap after tick 20 is 0.4828)", vp.springTick(T0 + 21 * STEP60))
        assertEquals(2400f, vp.offset, 0f) // exactly maxOffset
        assertFalse(vp.springRunning)
    }

    @Test
    fun springTicksAt120HertzSnapOnTick41() {
        val vp = tailGap100()
        for (n in 1..40) assertTrue("tick $n should still move", vp.springTick(T0 + n * STEP120))
        assertFalse(vp.springTick(T0 + 41 * STEP120))
        assertEquals(2400f, vp.offset, 0f)
        assertFalse(vp.springRunning)
    }

    @Test
    fun aLongGapClampsDtToOneThirtiethOfASecond() {
        val vp = tailGap100()
        assertTrue(vp.springTick(T0 + 100_000_000L)) // 100 ms → the 1/30 s clamp
        assertEquals(2341.3354f, vp.offset, 1e-3f) // 100 · 0.413354
    }

    @Test
    fun glideStepsUseTheRunwayCurve() {
        val at60 = tailGap100()
        at60.glide = true
        assertTrue(at60.springTick(T0 + STEP60))
        assertEquals(2315f, at60.offset, 1e-3f) // 100 · 0.15

        val at120 = tailGap100()
        at120.glide = true
        assertTrue(at120.springTick(T0 + STEP120))
        assertEquals(2307.8046f, at120.offset, 1e-3f) // 100 · 0.078046
    }

    @Test
    fun theSpringRereadsItsTargetEveryTick() {
        val vp = tailGap100()
        assertTrue(vp.springTick(T0 + STEP60)) // 2323.4072
        vp.setContentHeight(3200f) // the target moves: maxOffset → 2500
        val delta = 2500f - 2323.4072f
        assertTrue(vp.springTick(T0 + 2 * STEP60))
        assertEquals(2323.4072f + delta * 0.2340716f, vp.offset, 1e-3f)
        assertTrue("the spring keeps chasing the moved tail", vp.springRunning)
    }

    @Test
    fun aTickWhileTrackingOrAfterReleaseFollowStopsTheSpringWithoutMoving() {
        val vp = tailGap100()
        vp.onPointerDown()
        assertFalse(vp.springTick(T0 + STEP60))
        assertEquals(2300f, vp.offset, 0f)
        assertFalse(vp.springRunning)

        val released = tailGap100()
        released.releaseFollow() // R8: stops the spring …
        released.startSpring() // … re-armed as if a frame had arrived
        assertFalse(released.springTick(T0 + STEP60))
        assertEquals(2300f, released.offset, 0f)
        assertFalse(released.springRunning)
    }

    // ------------------------------------------------------------- gestures

    @Test
    fun aDragStartsOnTheFirstUserScrollDeltaWhileAPointerIsDown() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.onFrameApplied(3100f, first = false) // the spring is running
        assertTrue(vp.springRunning)

        vp.onPointerDown()
        assertEquals(-10f, vp.scrollByUser(-10f), 1e-4f)
        assertTrue("R5: the first delta while down is the drag start", vp.dragging)
        assertFalse("the drag hands control to the user", vp.following)
        assertFalse("R8: following false stops the spring", vp.springRunning)

        // Without a pointer down, a scroll delta never marks a drag.
        val noPointer = newViewport()
        noPointer.onFrameApplied(3000f, first = true)
        noPointer.releaseFollow()
        noPointer.scrollByUser(-10f)
        assertFalse(noPointer.dragging)
    }

    @Test
    fun dragReleaseThresholds() {
        assertTrue(releasedAt(69.9f, 50f).following)
        assertFalse(releasedAt(69.9f, 50.1f).following)
        assertFalse(releasedAt(70f, 0f).following)
        assertTrue(releasedAt(10f, -2000f).following) // toward the tail, fast
        // The release never starts the spring (iOS leaves it to the next frame).
        listOf(69.9f to 50f, 69.9f to 50.1f, 70f to 0f, 10f to -2000f).forEach { (d, v) ->
            assertFalse(releasedAt(d, v).springRunning)
        }
    }

    @Test
    fun momentumRelatchesOnlyWhenFlingingBackIntoTheTail() {
        // A fling delta ending at distance 69 re-latches.
        val near = newViewport()
        near.onFrameApplied(3000f, first = true)
        near.releaseFollow()
        near.setOffset(2226f) // distance 74
        near.onFlingStart()
        near.scrollByUser(5f) // moving down, ends at distance 69
        assertTrue(near.following)

        // The same ending at distance 71 does not.
        val far = newViewport()
        far.onFrameApplied(3000f, first = true)
        far.releaseFollow()
        far.setOffset(2224f)
        far.onFlingStart()
        far.scrollByUser(5f) // ends at distance 71
        assertFalse(far.following)

        // A move toward older content ending at distance 60 does not.
        val older = newViewport()
        older.onFrameApplied(3000f, first = true)
        older.releaseFollow()
        older.setOffset(2250f)
        older.onFlingStart()
        older.scrollByUser(-10f) // ends at distance 60, moving up
        assertFalse(older.following)

        // Without flinging, nothing re-latches.
        val still = newViewport()
        still.onFrameApplied(3000f, first = true)
        still.releaseFollow()
        still.setOffset(2226f)
        still.scrollByUser(5f) // ends at distance 69, but not flinging
        assertFalse(still.following)

        // Distance ≤ 0 without movement re-latches during a fling.
        val past = newViewport()
        past.onFrameApplied(3000f, first = true)
        past.releaseFollow()
        past.setOffset(2300f) // distance 0; not flinging → no re-latch
        assertFalse(past.following)
        past.onFlingStart()
        past.scrollByUser(0f) // no movement, distance == 0
        assertTrue(past.following)
    }

    @Test
    fun aRestingPressRearmsTheSpringWhenAFrameArrivedWhileHolding() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.onPointerDown()
        vp.onFrameApplied(3200f, first = false) // R3: while tracking, no spring
        assertFalse(vp.springRunning)
        vp.onPointerUp(0f) // R13: no drag, |2500 − 2300| > 0.5
        assertTrue("R13 re-arms the spring for a resting press", vp.springRunning)

        // With a drag in that gesture, it does not.
        val dragged = newViewport()
        dragged.onFrameApplied(3000f, first = true)
        dragged.onPointerDown()
        dragged.scrollByUser(-10f) // the drag releases follow
        dragged.onFrameApplied(3200f, first = false)
        dragged.onPointerUp(0f) // release rule: distance 210 > 70
        assertFalse(dragged.springRunning)
        assertFalse(dragged.following)
    }

    // ------------------------------------------------- programmatic API

    @Test
    fun scrollToBottomAndReleaseFollow() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true)
        vp.releaseFollow()
        assertFalse("R9: releaseFollow", vp.following)

        vp.setOffset(0f)
        vp.scrollToBottom(animated = false)
        assertTrue(vp.following)
        assertEquals(2300f, vp.offset, 0f)
        assertFalse(vp.springRunning)

        vp.releaseFollow()
        vp.setOffset(0f)
        vp.scrollToBottom(animated = true)
        assertTrue(vp.following)
        assertTrue("R10: animated starts the spring", vp.springRunning)
    }

    @Test
    fun releaseFollowStopsARunningSpring() {
        val vp = tailGap100()
        vp.releaseFollow()
        assertFalse(vp.following)
        assertFalse(vp.springRunning)
    }

    // ------------------------------------------------------ geometry

    @Test
    fun bottomInsetChangesSnapWhileFollowingAndIgnoreSmallDeltas() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true) // offset 2300
        var offsetSeenByHook = Float.NaN
        vp.onGeometryChanged = { offsetSeenByHook = vp.offset }
        vp.setBottomInset(150f) // maxOffset → 2350
        assertEquals("the hook runs before the snap", 2300f, offsetSeenByHook, 0f)
        assertEquals(2350f, vp.offset, 0f)

        vp.releaseFollow()
        vp.setBottomInset(200f) // maxOffset 2400, not following
        assertEquals("no snap while not following", 2350f, vp.offset, 0f)

        var hookCalls = 0
        vp.onGeometryChanged = { hookCalls++ }
        vp.setBottomInset(200.4f) // ≤ 0.5: ignored entirely
        assertEquals(0, hookCalls)
        assertEquals(2350f, vp.offset, 0f)
    }

    @Test
    fun topInsetAndViewportHeightChangesSnapWhileFollowing() {
        // Short content: maxOffset == −topInset, so the change is observable.
        val short = newViewport()
        short.onFrameApplied(500f, first = true) // offset −68
        var offsetSeenByHook = Float.NaN
        short.onGeometryChanged = { offsetSeenByHook = short.offset }
        short.setTopInset(88f)
        assertEquals("the hook runs before the snap", -68f, offsetSeenByHook, 0f)
        assertEquals(-88f, short.offset, 0f)
        var hookCalls = 0
        short.onGeometryChanged = { hookCalls++ }
        short.setTopInset(88.4f) // ≤ 0.5: ignored entirely
        assertEquals(0, hookCalls)
        assertEquals(-88f, short.offset, 0f)

        val tall = newViewport()
        tall.onFrameApplied(3000f, first = true) // offset 2300
        tall.setViewportHeight(700f) // maxOffset → 2400
        assertEquals(2400f, tall.offset, 0f)
        tall.setViewportHeight(700.4f) // ≤ 0.5: ignored entirely
        assertEquals(2400f, tall.offset, 0f)
    }

    // --------------------------------------------------------- the clamp

    @Test
    fun theClampRefusesOutOfRangeButAllowsReturn() {
        val vp = newViewport()
        vp.onFrameApplied(3000f, first = true) // offset 2300 == maxOffset
        assertEquals(0f, vp.scrollByUser(50f), 1e-4f) // further out of range: refused

        vp.setContentHeight(2000f) // maxOffset 1300; the offset is NOT clamped
        assertEquals(2300f, vp.offset, 0f)
        assertEquals(0f, vp.scrollByUser(10f), 1e-4f) // still refused
        assertEquals(-10f, vp.scrollByUser(-10f), 1e-4f) // back into range: allowed

        vp.setOffset(-68f) // minOffset
        assertEquals(0f, vp.scrollByUser(-10f), 1e-4f)
    }

    private companion object {
        /** An arbitrary, non-zero clock base for the injected [TranscriptViewport.nanoTime]. */
        const val T0 = 1_000_000_000L

        /** 60 Hz display frames. */
        const val STEP60 = 16_666_667L

        /** 120 Hz display frames. */
        const val STEP120 = 8_333_333L
    }
}
