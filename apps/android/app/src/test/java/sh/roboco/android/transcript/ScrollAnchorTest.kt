package sh.roboco.android.transcript

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchor math of ticket 130, plain JUnit — no Robolectric and no
 * core: the lookups run through fake rows ([FakeRows], Rust's `index_at`
 * semantics) and the viewport is the real [TranscriptViewport] (68,
 * plain snapshot state). Every row of the ticket's worked-examples table
 * plus the probe, clamp and dead-band cases, tolerance 1e-4. The
 * geometry is the ticket's setup: `topInset 68`, `bottomInset 100`,
 * `viewportHeight 800`, first frame content 3016 (30 rows of 100 dp),
 * so `maxOffset` is 2316 and the anchor probe at `offset 1010` is
 * content y 1078 — row `k10` at 1000, delta 10.
 */
class ScrollAnchorTest {

    // ------------------------------------------------------------- rows

    /**
     * Fake rows `(key, y, height)` — the [AnchorRows] seam anchoring
     * tests through. `rowAt` is Rust `index_at`
     * (`crates/mobile/src/layout/mod.rs:196-211`): the first row with
     * `y + height > y`, clamped to the last row; null when empty.
     */
    private class FakeRows(private val rows: List<Triple<ULong, Float, Float>>) : AnchorRows {
        override fun rowAt(y: Float): AnchorRow? {
            if (rows.isEmpty()) return null
            val hit = rows.indexOfFirst { it.second + it.third > y }
            val i = if (hit < 0) rows.lastIndex else hit
            return AnchorRow(rows[i].first, rows[i].second)
        }

        override fun yOf(key: ULong): Float? = rows.firstOrNull { it.first == key }?.second
    }

    /** Rows k0..k[n-1], each [height] dp tall, stacked from y 0. */
    private fun uniform(n: Int, height: Float = 100f): List<Triple<ULong, Float, Float>> {
        val rows = ArrayList<Triple<ULong, Float, Float>>(n)
        var y = 0f
        repeat(n) { i ->
            rows.add(Triple(i.toULong(), y, height))
            y += height
        }
        return rows
    }

    /** Rows k0..k[n-1] with per-index height overrides, stacked from y 0. */
    private fun stacked(n: Int, vararg at: Pair<Int, Float>): List<Triple<ULong, Float, Float>> {
        val rows = ArrayList<Triple<ULong, Float, Float>>(n)
        var y = 0f
        repeat(n) { i ->
            val h = at.firstOrNull { it.first == i }?.second ?: 100f
            rows.add(Triple(i.toULong(), y, h))
            y += h
        }
        return rows
    }

    /** [m] new rows (keys 100.., 100 dp tall) above [base], everything else shifted down. */
    private fun prepended(base: List<Triple<ULong, Float, Float>>, m: Int): List<Triple<ULong, Float, Float>> {
        val rows = ArrayList<Triple<ULong, Float, Float>>(base.size + m)
        repeat(m) { i -> rows.add(Triple(100uL + i.toULong(), i * 100f, 100f)) }
        base.forEach { rows.add(Triple(it.first, it.second + m * 100f, it.third)) }
        return rows
    }

    /** [base] without the row with [key], the rest re-stacked in order. */
    private fun without(base: List<Triple<ULong, Float, Float>>, key: ULong): List<Triple<ULong, Float, Float>> {
        var y = 0f
        val out = ArrayList<Triple<ULong, Float, Float>>()
        base.filter { it.first != key }.forEach {
            out.add(Triple(it.first, y, it.third))
            y += it.third
        }
        return out
    }

    // ---------------------------------------------------------- viewport

    /** The worked example's viewport: 68/100/800, first frame 3016 landed at the tail, follow released. */
    private fun landedViewport(): TranscriptViewport {
        val vp = TranscriptViewport()
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.setViewportHeight(800f)
        vp.onFrameApplied(3_016f, first = true)
        vp.releaseFollow()
        assertEquals(2_316f, vp.offset, 1e-4f)
        return vp
    }

    /** [landedViewport] with the offset pinned at [offset] (dp). */
    private fun viewportAt(offset: Float): TranscriptViewport = landedViewport().also { it.setOffset(offset) }

    /**
     * The 130 half of `TranscriptState.apply`: capture over the old rows
     * (before the swap), hand the new height to 68, then the guarded
     * restore over the new rows. Returns whether the restore wrote a
     * correction.
     */
    private fun applyAnchored(
        vp: TranscriptViewport,
        old: List<Triple<ULong, Float, Float>>,
        new: List<Triple<ULong, Float, Float>>,
        newTotalHeight: Float,
    ): Boolean {
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        val needsAnchor = vp.onFrameApplied(newTotalHeight, first = false)
        if (needsAnchor && anchor != null) {
            return ScrollAnchoring.restore(vp, anchor, FakeRows(new))
        }
        return false
    }

    /** The five states 130 must never touch (the acceptance item). */
    private data class Flags(
        val following: Boolean,
        val springRunning: Boolean,
        val tracking: Boolean,
        val dragging: Boolean,
        val flinging: Boolean,
    )

    private fun TranscriptViewport.flags() = Flags(following, springRunning, tracking, dragging, flinging)

    /** The worked example's old frame: 30 rows, total 3016. */
    private val old = uniform(30)

    // ---------------------------------------------- worked-examples table

    @Test
    fun appendedRowsBelowLeaveTheOffsetAlone() {
        val vp = viewportAt(1_010f)
        vp.onPointerDown() // a finger rests on the list (A9: anchoring applies either way)
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals("y = 1078 → k10, delta 10", ScrollAnchor(10uL, 10f), anchor)
        val before = vp.flags()

        // 35 rows: k10 stays at 1000, the total grows to 3516.
        val new = uniform(35)
        val needsAnchor = vp.onFrameApplied(3_516f, first = false)
        assertTrue(needsAnchor)
        if (needsAnchor && anchor != null) {
            assertFalse("1010 == target: inside the dead band, no write", ScrollAnchoring.restore(vp, anchor, FakeRows(new)))
        }
        assertEquals(1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun streamingGrowthBelowLeavesTheOffsetAlone() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertFalse(applyAnchored(vp, old, stacked(30, 29 to 400f), 3_316f))
        assertEquals(1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun prependedRowsShiftTheOffsetByTheirHeight() {
        val vp = viewportAt(1_010f)
        vp.onFlingStart() // momentum in flight: the shift must keep it (A7, the fling continuity rule)
        val before = vp.flags()
        assertTrue(applyAnchored(vp, old, prepended(old, 5), 3_516f))
        assertEquals(1_510f, vp.offset, 1e-4f)
        assertEquals("neither capture nor restore touches the five", before, vp.flags())
    }

    @Test
    fun aRowAboveGrowingShiftsTheOffsetDown() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertTrue(applyAnchored(vp, old, stacked(30, 3 to 140f), 3_056f))
        assertEquals(1_050f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aRowAboveShrinkingIntoTheDeadBandStaysPut() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertFalse(applyAnchored(vp, old, stacked(30, 3 to 99.6f), 3_015.6f))
        assertEquals("|1009.6 − 1010| = 0.4 ≤ 0.5: no move", 1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aRowBelowGrowingLeavesTheOffsetAlone() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertFalse(applyAnchored(vp, old, stacked(30, 12 to 300f), 3_216f))
        assertEquals(1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aWidthRewrapShiftsTheOffset() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertTrue(applyAnchored(vp, old, uniform(30, 130f), 3_916f))
        assertEquals("k10 re-wrapped to 1300", 1_310f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aRemovedAnchoredRowHasNoFallback() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        assertFalse(applyAnchored(vp, old, without(old, 10uL), 2_916f))
        assertEquals("k10 is gone: nothing moves (iOS, no #621 fallback)", 1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aFollowingViewportNeverCapturesAndSpringsInstead() {
        val vp = landedViewport()
        vp.scrollToBottom(animated = false) // re-follow, still at the tail
        assertNull(ScrollAnchoring.capture(vp, FakeRows(old)))
        // A later frame while following: 68 R3, not the anchor.
        val needsAnchor = vp.onFrameApplied(3_516f, first = false)
        assertFalse("following frames never need an anchor", needsAnchor)
        assertTrue(vp.springRunning)
    }

    // ---------------------------------------------------------- the probes

    @Test
    fun aProbeAtTheRestingTopAnchorsRowZero() {
        val vp = viewportAt(-68f) // the resting top: row 0 just under the header
        val before = vp.flags()
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals(ScrollAnchor(0uL, -68f), anchor)
        val new = prepended(old, 5)
        assertTrue(ScrollAnchoring.restore(vp, anchor ?: error("anchor"), FakeRows(new)))
        assertEquals(432f, vp.offset, 1e-4f)
        assertEquals("the old first row stays just under the header", 68f, 500f - vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun anOverscrolledProbeClampsToRowZero() {
        val vp = viewportAt(-120f) // pulled 52 dp above the resting top
        val before = vp.flags()
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals("y = max(0, −52) = 0 → k0, delta = −120", ScrollAnchor(0uL, -120f), anchor)
        assertTrue(ScrollAnchoring.restore(vp, anchor ?: error("anchor"), FakeRows(prepended(old, 5))))
        assertEquals(380f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun aProbePastTheContentClampsToTheLastRow() {
        // Rust index_at: a y past the content returns the last row.
        assertEquals(AnchorRow(29uL, 2_900f), FakeRows(old).rowAt(3_100f))
        val vp = viewportAt(3_100f) // past the old tail (maxOffset 2316)
        val before = vp.flags()
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals(ScrollAnchor(29uL, 200f), anchor)
        assertEquals(before, vp.flags())
    }

    // ------------------------------------------------------------ clamping

    @Test
    fun theClampExampleClampsToMaxOffset() {
        val vp = viewportAt(2_316f) // the old maxOffset
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals("y = 2384 → k23, delta 16", ScrollAnchor(23uL, 16f), anchor)
        val before = vp.flags()
        // One 100-tall row prepended, the 100-tall last row removed: the
        // total stays 3016, so maxOffset stays 2316 — but k23 moves to 2400.
        val new = prepended(old, 1).dropLast(1)
        assertTrue(applyAnchored(vp, old, new, 3_016f))
        assertEquals(2_316f, vp.maxOffset, 1e-4f)
        assertEquals("target 2416 clamps to maxOffset", 2_316f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    @Test
    fun contentShrinkingIntoTheDeadBandNeverClamps() {
        // Old: 35 rows (3516, maxOffset 2816), anchored at k28 with delta 0.
        val old35 = uniform(35)
        val vp = viewportAt(2_800f)
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old35))
        assertEquals(ScrollAnchor(28uL, 0f), anchor)
        // New: only k0..k28 (total 2916) — maxOffset drops to 2216, but the
        // target equals the offset, so the dead band fires first and the
        // offset is left alone even though it now exceeds maxOffset (68 R14).
        val new = old35.subList(0, 29)
        val before = vp.flags()
        assertFalse(applyAnchored(vp, old35, new, 2_916f))
        assertEquals(2_216f, vp.maxOffset, 1e-4f)
        assertEquals(2_800f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    // --------------------------------------------------- pure null/false cases

    @Test
    fun captureReturnsNullWhenFollowingOldIsMissingOrTheFrameIsEmpty() {
        // Following (the landed-then-refollowed viewport never captures).
        val following = viewportAt(1_010f)
        following.scrollToBottom(animated = false)
        assertTrue(following.following)
        assertNull(ScrollAnchoring.capture(following, FakeRows(old)))

        // No old frame (the first apply).
        assertNull(ScrollAnchoring.capture(viewportAt(1_010f), null))

        // An old frame with no rows has no indexAt.
        assertNull(ScrollAnchoring.capture(viewportAt(1_010f), FakeRows(emptyList())))
    }

    @Test
    fun restoreReturnsFalseForAnAbsentKeyAndInsideTheDeadBand() {
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        // The key is gone (the #working row ending, a removed entry).
        assertFalse(ScrollAnchoring.restore(vp, ScrollAnchor(999uL, 0f), FakeRows(old)))
        // The target lands within 0.5 dp — exactly on the boundary.
        assertFalse(ScrollAnchoring.restore(vp, ScrollAnchor(10uL, 9.5f), FakeRows(old)))
        assertEquals(1_010f, vp.offset, 1e-4f)
        assertEquals(before, vp.flags())
    }

    // ------------------------------------------------------- runway precedence

    @Test
    fun runwayPromotionBeatsTheAnchor() {
        val vp = viewportAt(1_010f)
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals(ScrollAnchor(10uL, 10f), anchor)

        // 69's queued promotion, the way RunwayDriver.heightFor does it:
        // `setFollowing(true)` (68's internal setter — no offset move, no
        // spring) before onFrameApplied, so R3 runs instead of the anchor.
        vp.setFollowing(true)
        val needsAnchor = vp.onFrameApplied(3_516f, first = false)
        assertFalse("the promotion wins: R3, not the anchor", needsAnchor)
        if (needsAnchor && anchor != null) {
            ScrollAnchoring.restore(vp, anchor, FakeRows(prepended(old, 5)))
        }
        assertEquals("not the anchor target 1510", 1_010f, vp.offset, 1e-4f)
        assertTrue(vp.following)
        assertTrue("R3 started the tail spring", vp.springRunning)
        assertEquals(2_816f, vp.maxOffset, 1e-4f)
    }

    // ------------------------------------------------------ the resting finger

    @Test
    fun aRestingFingerOnAFollowingListLandsAtTheBottomAndNeverCaptures() {
        val vp = TranscriptViewport()
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.setViewportHeight(800f)
        val rows = FakeRows(old)
        vp.onPointerDown() // a finger rests on the loading list (A10)
        assertNull(ScrollAnchoring.capture(vp, rows))
        assertNull(ScrollAnchoring.capture(vp, null))

        vp.onFrameApplied(16f, first = true) // the empty frame: 0 rows, breathing room only
        assertNull(ScrollAnchoring.capture(vp, rows))

        vp.onFrameApplied(3_016f, first = true) // the previous frame had 0 rows → still first
        assertEquals(2_316f, vp.maxOffset, 1e-4f)
        assertEquals("the first real frame still lands at the bottom", 2_316f, vp.offset, 1e-4f)
        assertTrue(vp.following)
        assertFalse("the landing never starts the spring", vp.springRunning)
        assertTrue(vp.tracking)
        assertNull(ScrollAnchoring.capture(vp, rows))
    }

    // ------------------------------------------------------------- sanity

    @Test
    fun theProbeIsTheHeaderBottomEdgeNotTheBoxTop() {
        // The #609 trap: probing at `offset` (1000 → k9, hidden under the
        // header) instead of `offset + topInset` (1078 → k10).
        val vp = viewportAt(1_010f)
        val before = vp.flags()
        val anchor = ScrollAnchoring.capture(vp, FakeRows(old))
        assertEquals(10uL, anchor?.key)
        assertEquals(10f, anchor?.delta ?: 0f, 1e-4f)
        assertTrue(abs(vp.scrollY - 1_078f) < 1e-4f)
        assertEquals(before, vp.flags())
    }
}
