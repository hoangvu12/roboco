package sh.roboco.android.transcript

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript's rubber band (ticket 134) over a real [TranscriptViewport]
 * (68 is pure Kotlin), plain JUnit: `applyToScroll` is driven with the
 * test's `performScroll` mimic of Compose's `ScrollingLogic` under
 * `reverseDirection = true` — `{ d -> Offset(0f, -viewport.scrollByUser(-d.y)) }`
 * — and releases run under a 60 Hz [StepFrameClock] so the bounce-back
 * follows the ticket's per-tick reference table. Density 1 throughout
 * (dp == px), the worked example's geometry: height 800, `topInset 68`,
 * `bottomInset 100`, content 3000 → `minOffset −68`, `maxOffset 2300`.
 */
class TranscriptRubberBandTest {

    private val step60 = 16_666_667L

    // ------------------------------------------------------------- helpers

    /** The worked example's viewport, landed at the tail (offset 2300). */
    private fun newViewport(): TranscriptViewport {
        val vp = TranscriptViewport()
        vp.setViewportHeight(800f)
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.onFrameApplied(contentHeight = 3000f, first = true)
        return vp
    }

    private fun band(vp: TranscriptViewport) = TranscriptRubberBand(vp) { 1f }

    /** Compose's `ScrollingLogic` mimic for `reverseDirection = true`, density 1. */
    private fun scroll(vp: TranscriptViewport): (Offset) -> Offset = { d ->
        Offset(0f, -vp.scrollByUser(-d.y))
    }

    private fun neverFling(): suspend (Velocity) -> Velocity = {
        throw AssertionError("R-a must never fling the content (a release out of bounds bounces)")
    }

    // ----------------------------------------------------- the drag band

    @Test
    fun theTopBandMatchesTheWorkedExample() {
        val vp = newViewport()
        vp.setOffset(-68f) // the resting top
        val b = band(vp)
        vp.onPointerDown()
        // +100 px (the finger moves down): band(100) = 51.4620 above the top.
        b.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(-119.4620f, vp.offset, 1e-3f)
        // −60 px: the band relaxes to band(40) = 21.4112.
        b.applyToScroll(Offset(0f, -60f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(-89.4112f, vp.offset, 1e-3f)
        // −60 px again: 40 of it relaxes the rest of the band, 20 scrolls 1:1.
        b.applyToScroll(Offset(0f, -60f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(-48f, vp.offset, 1e-3f)
    }

    @Test
    fun theBandIsPathIndependent() {
        val vp = newViewport()
        vp.setOffset(-68f)
        val b = band(vp)
        vp.onPointerDown()
        repeat(4) {
            b.applyToScroll(Offset(0f, 25f), NestedScrollSource.UserInput, scroll(vp))
        }
        // 4 × 25 lands exactly where 1 × 100 did: the band is a pure
        // function of the total raw travel, applied as deltas.
        assertEquals(-119.4620f, vp.offset, 1e-3f)
    }

    @Test
    fun shortContentBandsBothWays() {
        // The ticket's interaction table: `maxOffset == minOffset` (the
        // content fits) still rubber-bands both ways — iOS
        // `alwaysBounceVertical`.
        fun short(): TranscriptViewport {
            val vp = newViewport()
            vp.onFrameApplied(contentHeight = 500f, first = true)
            assertEquals(-68f, vp.maxOffset, 1e-4f)
            vp.onPointerDown()
            return vp
        }
        // The finger moves down: a band above the top.
        val down = short()
        band(down).applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput, scroll(down))
        assertEquals(-119.4620f, down.offset, 1e-3f)
        // The finger moves up: a band past the tail.
        val up = short()
        band(up).applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(up))
        assertEquals(-16.5380f, up.offset, 1e-3f)
    }

    @Test
    fun theTailBandMatchesTheWorkedExample() {
        val vp = newViewport() // offset 2300 = maxOffset
        val b = band(vp)
        vp.onPointerDown()
        // −100 px (the finger moves up): 51.4620 past the tail.
        b.applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(2351.4620f, vp.offset, 1e-3f)
        assertEquals(-51.4620f, vp.distanceFromBottom, 1e-3f)
    }

    @Test
    fun theFirstBandDeltaStartsADragAndReleasesFollow() {
        val vp = newViewport()
        vp.setOffset(-68f)
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput, scroll(vp))
        // B4 always reaches scrollByUser, even when the whole delta bands:
        // R5 must have fired (the ticket's observable-result assertion).
        assertTrue("the band drag must set dragging", vp.dragging)
        assertFalse("the band drag must release follow", vp.following)
    }

    @Test
    fun aWheelOrFlingDeltaNeverBands() {
        // No `onPointerDown()` — a wheel or keyboard scroll has no finger
        // down, so the B0 gate passes it straight through and the scroll
        // refuses to leave the range.
        val wheel = newViewport()
        band(wheel).applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(wheel))
        assertEquals(2300f, wheel.offset, 1e-3f)
        assertFalse(band(wheel).isInProgress)

        // A `SideEffect` source (a running fling's deltas) also never bands.
        val fling = newViewport()
        fling.onPointerDown()
        band(fling).applyToScroll(Offset(0f, -100f), NestedScrollSource.SideEffect, scroll(fling))
        assertEquals(2300f, fling.offset, 1e-3f)
        assertFalse(band(fling).isInProgress)
    }

    // ----------------------------------------------------- the release

    @Test
    fun releaseFromTheTopPullBouncesBackAlongTheTable() {
        val vp = newViewport()
        vp.setOffset(-68f)
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(-119.4620f, vp.offset, 1e-3f) // x₀ = −51.4620
        vp.onPointerUp(0f)
        val offsets = mutableListOf<Float>()
        var flingingDuring = false
        val clock = StepFrameClock(step60) {
            offsets += vp.offset
            if (vp.flinging) flingingDuring = true
        }
        runBlocking(clock) {
            b.applyToFling(Velocity(0f, 0f), neverFling())
        }
        // The hook runs before each step, so offsets[k] (0-based) is the
        // offset after step k; offsets[0] is the pulled position itself.
        assertEquals(-117.1655f, offsets[1], 1e-3f)
        assertEquals(-112.0358f, offsets[2], 1e-3f)
        assertEquals(-105.8636f, offsets[3], 1e-3f)
        // Step 21 leaves x = −0.3754: tick 22 snaps to the resting top.
        assertEquals("the bounce must snap on tick 22", 22, clock.frame)
        assertEquals(-68f, vp.offset, 0f)
        assertTrue("the bounce must count as flinging (R7)", flingingDuring)
        assertFalse(vp.flinging)
    }

    @Test
    fun aTailPullReleasedWithAFlickDownwardsReFollowsDuringTheBounce() {
        val vp = newViewport()
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(2351.4620f, vp.offset, 1e-3f)
        assertFalse(vp.following)
        // R6 fails (300 dp/s > 50): the release itself does not re-follow.
        vp.onPointerUp(300f)
        assertFalse(vp.following)
        runBlocking(StepFrameClock(step60)) {
            b.applyToFling(Velocity(0f, 300f), neverFling())
        }
        // But the bounce is flagged as flinging, so R7 sees distance ≤ 0
        // on its first step and hands control back — iOS parity.
        assertTrue("R7 must re-latch during the bounce", vp.following)
        assertEquals(2300f, vp.offset, 0.5f)
    }

    @Test
    fun aFlingThatStopsAtTheTailCarriesItsVelocityIntoABounce() {
        val vp = newViewport() // offset 2300 = maxOffset
        val b = band(vp)
        var peak = 0f
        val clock = StepFrameClock(step60) { peak = maxOf(peak, vp.offset) }
        runBlocking(clock) {
            b.applyToFling(Velocity(0f, 0f)) { Velocity(0f, -1000f) }
        }
        // v₀ = 1000 dp/s into the end: peak 18.394 dp past it on tick 3.
        assertEquals(2318.394f, peak, 1e-3f)
        // Step 20 leaves x = 0.4242: tick 21 snaps back to the tail.
        assertEquals("the edge bounce must snap on tick 21", 21, clock.frame)
        assertEquals(2300f, vp.offset, 0f)
        assertFalse(vp.flinging)
    }

    @Test
    fun aFlingThatStopsMidRangeLeavesTheOffsetAlone() {
        val vp = newViewport()
        vp.setOffset(1500f)
        val b = band(vp)
        var called = 0
        runBlocking(StepFrameClock(step60)) {
            b.applyToFling(Velocity(0f, 0f)) {
                called++
                Velocity(0f, -1000f)
            }
        }
        assertEquals(1, called)
        assertEquals(1500f, vp.offset, 0f)
        assertFalse(vp.flinging)
        assertFalse(b.isInProgress)
    }

    // ------------------------------------------------- frame-clock hooks

    @Test
    fun aTouchDuringTheBounceCancelsItAtOnce() {
        val vp = newViewport()
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(vp))
        vp.onPointerUp(0f)
        var flingingAtGrab = true
        var offsetAtGrab = 0f
        val clock = StepFrameClock(step60) { frame ->
            if (frame == 3) {
                // A touch that lands while the list is still bouncing only
                // stops it (B0 needs the finger down, B1 cancels).
                vp.onPointerDown()
                b.applyToScroll(Offset(0f, 10f), NestedScrollSource.UserInput, scroll(vp))
                flingingAtGrab = vp.flinging
                offsetAtGrab = vp.offset
            }
        }
        runBlocking(clock) {
            b.applyToFling(Velocity(0f, 0f), neverFling())
        }
        assertFalse("the grab must stop the bounce at once", flingingAtGrab)
        assertEquals("later ticks must not write the offset", offsetAtGrab, vp.offset, 0f)
        assertEquals("the loop must exit at the grab frame", 3, clock.frame)
    }

    @Test
    fun aRestingFingerHoldsTheBounceAndTheReleaseResumesIt() {
        val vp = newViewport()
        vp.setOffset(-68f)
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, 100f), NestedScrollSource.UserInput, scroll(vp))
        vp.onPointerUp(0f)
        val offsets = mutableMapOf<Int, Float>()
        val clock = StepFrameClock(step60) { frame ->
            when (frame) {
                2 -> vp.onPointerDown() // the finger rests on the bouncing list
                6 -> vp.onPointerUp(0f)
            }
            offsets[frame] = vp.offset
        }
        runBlocking(clock) {
            b.applyToFling(Velocity(0f, 0f), neverFling())
        }
        // Ticks 2–5 are held: the offset stays at its tick-1 value…
        assertEquals(-117.1655f, offsets[2]!!, 1e-3f)
        assertEquals(offsets[2]!!, offsets[6]!!, 0f)
        // …tick 6's release resumes the step, and it still settles.
        assertTrue("tick 7 must resume the bounce", abs(offsets[7]!! - offsets[6]!!) > 0.1f)
        assertEquals(-68f, vp.offset, 0f)
    }

    @Test
    fun theTailSpringEndsTheBounce() {
        val vp = newViewport()
        val b = band(vp)
        vp.onPointerDown()
        b.applyToScroll(Offset(0f, -100f), NestedScrollSource.UserInput, scroll(vp))
        vp.onPointerUp(0f)
        val clock = StepFrameClock(step60) { frame ->
            if (frame == 2) vp.scrollToBottom(animated = true)
        }
        runBlocking(clock) {
            b.applyToFling(Velocity(0f, 0f), neverFling())
        }
        // 68's tail spring owns the offset from tick 2 on; the bounce
        // exits on its next frame and leaves flinging false.
        assertEquals("the bounce must end at the spring frame", 2, clock.frame)
        assertFalse(vp.flinging)
        assertTrue(vp.springRunning)
    }

    // -------------------------------------------------------- content shrink

    @Test
    fun contentShrinkDragsOneToOneAndBouncesBack() {
        val vp = newViewport() // offset 2300, content 3000
        vp.setContentHeight(2000f) // maxOffset 1300: the excess is 1000 ≥ 0.5·d
        val b = band(vp)
        vp.onPointerDown()
        // B2 refuses to re-grab such a large excess, so the drag is 1:1.
        b.applyToScroll(Offset(0f, 50f), NestedScrollSource.UserInput, scroll(vp))
        assertEquals(2250f, vp.offset, 1e-3f)
        vp.onPointerUp(0f)
        runBlocking(StepFrameClock(step60)) {
            b.applyToFling(Velocity(0f, 0f), neverFling())
        }
        assertEquals("the release bounces back to the new tail", 1300f, vp.offset, 0.5f)
    }
}
