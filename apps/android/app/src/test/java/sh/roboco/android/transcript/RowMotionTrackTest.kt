package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.RowKind

/**
 * The additive segment tracks (ticket 132, plain JVM with explicit `tick`
 * times — `T0 = 1_000_000_000L` ns): the ticket's worked examples at their
 * exact curve samples (EaseOut 0.6846 at half time, ExpoOut 0.8256 at a
 * quarter), the fraction-0 rule until the first tick, the reversal that
 * continues from the drawn position (UIKit's additive interruption), the
 * un-animated frame mid-flight that shifts the final without cancelling
 * the tween, running segments surviving reduce motion, `debugTimeScale`
 * fixing a segment's duration at creation, and the `version`/`drop`
 * bookkeeping. No Compose animators, no `snapTo(0f)` restarts (#609's
 * jump) — the segments carry the in-flight remainder themselves.
 */
class RowMotionTrackTest {

    private companion object {
        const val T0 = 1_000_000_000L
        const val A = 1uL
        const val B = 2uL
        const val G = 3uL
        const val C = 4uL
    }

    /** A [RowLookup] fake over a map. */
    private fun lookup(vararg rows: Pair<ULong, RowBox>): RowLookup {
        val map = rows.toMap()
        return RowLookup { k -> map[k] }
    }

    /** A long user row A and the markdown row B below it (the ticket's fold example). */
    private fun foldFrames(): Pair<RowLookup, RowLookup> =
        lookup(A to RowBox(0f, 100f), B to RowBox(100f, 50f)) to
            lookup(A to RowBox(0f, 300f), B to RowBox(300f, 50f))

    private val foldRealized = listOf(A to RowKind.USER, B to RowKind.MARKDOWN)

    @Test
    fun theFoldExampleSlidesBAndGrowsAOver140MsEaseOut() {
        val m = RowMotion()
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        assertTrue(m.hasTracks)

        // Until the first tick the fraction is 0: both rows draw exactly
        // where they were, at the new final values.
        assertEquals(100f, m.visualY(B, 300f), 1e-3f)
        assertEquals(100f, m.visualHeight(A, 300f), 1e-3f)

        m.tick(T0) // stamps the starts; the fractions are still 0
        assertEquals(100f, m.visualY(B, 300f), 1e-3f)

        val v0 = m.version
        m.tick(T0 + 70_000_000L) // half time, EaseOut(0.5) = 0.6846
        assertEquals(236.93f, m.visualY(B, 300f), 0.05f)
        assertEquals(236.93f, m.visualHeight(A, 300f), 0.05f)
        assertEquals("intermediate ticks never bump version", v0, m.version)

        m.tick(T0 + 140_000_000L) // done: finished segments and empty tracks removed
        assertFalse(m.hasTracks)
        assertEquals(v0 + 1, m.version) // the final removal bumps
        assertEquals(300f, m.visualY(B, 300f), 1e-3f)
        assertEquals(300f, m.visualHeight(A, 300f), 1e-3f)
        assertFalse(m.isAnimating(B))
    }

    @Test
    fun reversingAMidFlightFoldContinuesFromTheDrawnPosition() {
        val m = RowMotion()
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        m.tick(T0)
        m.tick(T0 + 70_000_000L)
        assertEquals(236.93f, m.visualY(B, 300f), 0.05f)

        // "Show less" 70 ms in: the mark is re-set (the first apply cleared
        // it) and the reversed frame lands — the new segment is measured
        // against the old frame's placement (y 300), never the drawn value,
        // so with the first segment still live the sum is continuous.
        m.pendingFold = A
        assertEquals(
            RowMotionKind.FOLD,
            m.onFrameApplied(new, old, foldRealized, reduced = false),
        )
        m.tick(T0 + 70_000_000L)
        assertEquals("continuous with the drawn position", 236.93f, m.visualY(B, 100f), 0.05f)

        m.tick(T0 + 140_000_000L) // the first segment ended; only the second is live
        assertEquals(163.07f, m.visualY(B, 100f), 0.05f)
        assertTrue("the reversal's own track is still running", m.hasTracks)

        m.tick(T0 + 210_000_000L)
        assertFalse(m.hasTracks)
        assertEquals(100f, m.visualY(B, 100f), 1e-3f)
    }

    @Test
    fun anUnAnimatedFrameMidFlightShiftsTheFinalAndTheTweenSettlesOnIt() {
        val m = RowMotion()
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        m.tick(T0)
        m.tick(T0 + 70_000_000L)

        // A streaming token arrives: no fold (the mark was consumed), no
        // Tools growth — S4 selects nothing, so no segment is added (had one
        // been added, B would still read 236.93, 10 dp off); B's final y
        // simply moved to 310 and the old segment keeps settling toward it.
        assertNull(
            m.onFrameApplied(
                new,
                lookup(A to RowBox(0f, 300f), B to RowBox(310f, 50f)),
                foldRealized,
                reduced = false,
            ),
        )
        assertEquals(246.93f, m.visualY(B, 310f), 0.05f)

        m.tick(T0 + 140_000_000L)
        assertFalse(m.hasTracks)
        assertEquals(310f, m.visualY(B, 310f), 1e-3f)
    }

    @Test
    fun runningSegmentsFinishNormallyWhenReduceMotionTurnsOnMidFlight() {
        val m = RowMotion()
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        m.tick(T0)

        // Reduce motion is on when the next frame applies: S1 suppresses the
        // new selection, but the running segments are not cancelled (iOS
        // does not cancel them either).
        assertNull(
            m.onFrameApplied(
                new,
                lookup(A to RowBox(0f, 400f), B to RowBox(400f, 50f)),
                foldRealized,
                reduced = true,
            ),
        )
        m.tick(T0 + 70_000_000L)
        assertEquals(336.93f, m.visualY(B, 400f), 0.05f)
        m.tick(T0 + 140_000_000L)
        assertFalse(m.hasTracks)
    }

    @Test
    fun theRevealExampleSlidesCAndGrowsGOver360MsExpoOut() {
        val m = RowMotion()
        val old = lookup(G to RowBox(0f, 200f), C to RowBox(200f, 40f))
        val new = lookup(G to RowBox(0f, 260f), C to RowBox(260f, 40f))
        val realized = listOf(G to RowKind.TOOLS, C to RowKind.MARKDOWN)
        assertEquals(RowMotionKind.REVEAL, m.onFrameApplied(old, new, realized, reduced = false))
        m.tick(T0)

        m.tick(T0 + 90_000_000L) // quarter time, ExpoOut(0.25) = 0.8256
        assertEquals(249.54f, m.visualY(C, 260f), 0.05f)
        assertEquals(249.54f, m.visualHeight(G, 260f), 0.05f)

        m.tick(T0 + 360_000_000L)
        assertFalse(m.hasTracks)
        assertEquals(260f, m.visualY(C, 260f), 1e-3f)
        assertEquals(260f, m.visualHeight(G, 260f), 1e-3f)
    }

    @Test
    fun unchangedRowsAndRowsOutsideTheRealizedSetGetNoTrack() {
        val m = RowMotion()
        m.pendingFold = A
        val old = lookup(A to RowBox(0f, 100f), B to RowBox(100f, 50f))
        val new = lookup(A to RowBox(0f, 300f), B to RowBox(100f, 50f))
        // B is realized but its placement is identical: no track. C is in
        // neither frame's realized set: no track.
        val realized = listOf(A to RowKind.USER, B to RowKind.MARKDOWN)
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, realized, reduced = false))
        assertTrue(m.isAnimating(A))
        assertFalse("an unchanged row has no track", m.isAnimating(B))
        assertFalse("a row outside the realized set has no track", m.isAnimating(G))
        assertEquals(100f, m.visualY(B, 100f), 0f)
        assertEquals(50f, m.visualHeight(B, 50f), 0f)
    }

    @Test
    fun dropRemovesTheTrackAndBumpsVersionOnlyWhenOneExisted() {
        val m = RowMotion()
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        assertEquals(1, m.version)

        m.drop(B) // B has a track: removed and bumped
        assertEquals(2, m.version)
        assertFalse(m.isAnimating(B))
        assertTrue("A's track survives B's drop", m.hasTracks)

        m.drop(B) // no track now: no bump
        assertEquals(2, m.version)

        m.drop(A)
        assertEquals(3, m.version)
        assertFalse(m.hasTracks)
    }

    @Test
    fun debugTimeScaleFixesANewSegmentsDurationAtCreation() {
        val m = RowMotion()
        m.debugTimeScale = 10f
        m.pendingFold = A
        val (old, new) = foldFrames()
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, foldRealized, reduced = false))
        // Flipping the control back mid-tween must not jump or retime the
        // running segment: it keeps its created-at-10× duration (1400 ms).
        m.debugTimeScale = 1f

        m.tick(T0)
        m.tick(T0 + 700_000_000L) // half of the stored 1400 ms
        assertEquals(236.93f, m.visualY(B, 300f), 0.05f)
        assertTrue(m.hasTracks)

        m.tick(T0 + 1_400_000_000L)
        assertFalse(m.hasTracks)
    }
}
