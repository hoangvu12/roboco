package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.RowKind

/**
 * The S1–S4 selection (ticket 132, plain JVM over `RowLookup` fakes built
 * from maps — no native lib, no Compose UI): a missing old frame and
 * reduce motion suppress everything (and leave `pendingFold` set, as
 * iOS's guard runs before the fold check, TLV:336), a pending fold whose
 * height really changed wins FOLD and clears the mark, a pending fold
 * whose height did not change keeps the mark and still evaluates the
 * growth check, and growth is decided on **realized** Tools rows only —
 * missing-from-old counts as +∞, missing-from-new as 0, a non-realized
 * Tools row does not count (#609's 3-viewport band bug), and there is no
 * width special case (#609's other bug).
 */
class RowMotionSelectTest {

    private companion object {
        const val U1 = 1uL
        const val U2 = 2uL
        const val T1 = 3uL
    }

    /** A [RowLookup] fake over a map. */
    private fun lookup(vararg rows: Pair<ULong, RowBox>): RowLookup {
        val map = rows.toMap()
        return RowLookup { k -> map[k] }
    }

    private fun box(y: Float, height: Float) = RowBox(y, height)

    // ----------------------------------------------------------------- S1

    @Test
    fun noOldFrameSelectsNothingAndKeepsThePendingFold() {
        val m = RowMotion()
        m.pendingFold = U1
        assertNull(
            m.onFrameApplied(
                null,
                lookup(U1 to box(0f, 300f), T1 to box(300f, 260f)),
                listOf(U1 to RowKind.USER, T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
        assertEquals("pendingFold is untouched without an old frame", U1, m.pendingFold)
        assertNull(m.lastKind)
    }

    @Test
    fun reduceMotionSuppressesTheTweenAndKeepsThePendingFold() {
        val m = RowMotion()
        m.pendingFold = U1
        val old = lookup(U1 to box(0f, 100f))
        val new = lookup(U1 to box(0f, 300f))

        assertNull(
            "S1: reduce motion selects nothing",
            m.onFrameApplied(old, new, listOf(U1 to RowKind.USER), reduced = true),
        )
        assertEquals("pendingFold survives reduce motion (iOS's guard order)", U1, m.pendingFold)

        // The mark is still live: once motion returns and the height still
        // differs, the next frame folds.
        assertEquals(RowMotionKind.FOLD, m.onFrameApplied(old, new, listOf(U1 to RowKind.USER), reduced = false))
        assertNull(m.pendingFold)
    }

    // ----------------------------------------------------------------- S2

    @Test
    fun aPendingFoldWhoseHeightChangedFoldsAndClearsTheMark() {
        val m = RowMotion()
        m.pendingFold = U1
        assertEquals(
            RowMotionKind.FOLD,
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f)),
                lookup(U1 to box(0f, 300f)),
                listOf(U1 to RowKind.USER),
                reduced = false,
            ),
        )
        assertNull("the fold consumed the mark", m.pendingFold)
    }

    @Test
    fun aPendingFoldWhoseHeightDidNotChangeKeepsTheMarkAndFallsThroughToGrowth() {
        val m = RowMotion()
        m.pendingFold = U1
        // A streaming frame won the race: the pending row's height is
        // unchanged, but a realized Tools row grew.
        assertEquals(
            RowMotionKind.REVEAL,
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f), T1 to box(100f, 200f)),
                lookup(U1 to box(0f, 100f), T1 to box(100f, 260f)),
                listOf(U1 to RowKind.USER, T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
        assertEquals("an unchanged height keeps the mark", U1, m.pendingFold)
    }

    @Test
    fun aPendingFoldWithNoHeightChangeAndNoGrowthSelectsNothing() {
        val m = RowMotion()
        m.pendingFold = U1
        assertNull(
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f)),
                lookup(U1 to box(0f, 100f)),
                listOf(U1 to RowKind.USER),
                reduced = false,
            ),
        )
        assertEquals(U1, m.pendingFold)
    }

    @Test
    fun aPendingRowMissingFromEitherFrameIsNotAFold() {
        val m = RowMotion()
        m.pendingFold = U1

        // Missing from the old frame: `height(old, k)` is null.
        assertNull(
            m.onFrameApplied(
                lookup(U2 to box(0f, 10f)),
                lookup(U1 to box(0f, 300f)),
                listOf(U2 to RowKind.USER),
                reduced = false,
            ),
        )
        assertEquals(U1, m.pendingFold)

        // Missing from the new frame: `height(new, k)` is null (the row is
        // still realized — it is about to leave the frame).
        assertNull(
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f)),
                lookup(U2 to box(0f, 10f)),
                listOf(U1 to RowKind.USER),
                reduced = false,
            ),
        )
        assertEquals(U1, m.pendingFold)
    }

    @Test
    fun theFoldWinsOverAGrownToolsRowInTheSameFrame() {
        val m = RowMotion()
        m.pendingFold = U1
        assertEquals(
            "S2 precedes S3",
            RowMotionKind.FOLD,
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f), T1 to box(100f, 200f)),
                lookup(U1 to box(0f, 300f), T1 to box(100f, 260f)),
                listOf(U1 to RowKind.USER, T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
        assertNull(m.pendingFold)
    }

    // ----------------------------------------------------------------- S3

    @Test
    fun aRealizedToolsRowThatGrewReveals() {
        val m = RowMotion()
        assertEquals(
            RowMotionKind.REVEAL,
            m.onFrameApplied(
                lookup(T1 to box(0f, 200f), U1 to box(200f, 40f)),
                lookup(T1 to box(0f, 260f), U1 to box(260f, 40f)),
                listOf(T1 to RowKind.TOOLS, U1 to RowKind.MARKDOWN),
                reduced = false,
            ),
        )
    }

    @Test
    fun aToolsRowThatShrankSelectsNothing() {
        val m = RowMotion()
        assertNull(
            m.onFrameApplied(
                lookup(T1 to box(0f, 260f)),
                lookup(T1 to box(0f, 200f)),
                listOf(T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
    }

    @Test
    fun aRealizedUserOrMarkdownRowGrowingSelectsNothing() {
        val m = RowMotion()
        assertNull(
            "only RowKind.TOOLS counts as growth",
            m.onFrameApplied(
                lookup(U1 to box(0f, 100f)),
                lookup(U1 to box(0f, 300f)),
                listOf(U1 to RowKind.USER),
                reduced = false,
            ),
        )
    }

    @Test
    fun aToolsKeyMissingFromTheOldFrameIsNotGrowth() {
        val m = RowMotion()
        assertNull(
            "missing from old counts as +∞",
            m.onFrameApplied(
                lookup(U2 to box(0f, 10f)),
                lookup(T1 to box(0f, 260f)),
                listOf(T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
    }

    @Test
    fun aToolsKeyMissingFromTheNewFrameIsNotGrowth() {
        val m = RowMotion()
        assertNull(
            "missing from new counts as 0",
            m.onFrameApplied(
                lookup(T1 to box(0f, 200f)),
                lookup(U2 to box(0f, 10f)),
                listOf(T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
    }

    @Test
    fun aToolsRowThatGrewOutsideTheRealizedBandDoesNotReveal() {
        val m = RowMotion()
        // T1 grew but left the realized registry — #609 checked a 3-viewport
        // `rowsIn` band instead of the realized rows; iOS checks `visible`.
        assertNull(
            m.onFrameApplied(
                lookup(T1 to box(0f, 200f), U1 to box(200f, 40f)),
                lookup(T1 to box(0f, 260f), U1 to box(260f, 40f)),
                listOf(U1 to RowKind.MARKDOWN),
                reduced = false,
            ),
        )
    }

    @Test
    fun aWidthChangeIsNotSpecialCased() {
        val m = RowMotion()
        // RowLookup carries no width at all — a resize that makes a realized
        // Tools row taller is just growth, never a guard (#609 returned null
        // on any width change).
        assertEquals(
            RowMotionKind.REVEAL,
            m.onFrameApplied(
                lookup(T1 to box(0f, 200f)),
                lookup(T1 to box(0f, 260f)),
                listOf(T1 to RowKind.TOOLS),
                reduced = false,
            ),
        )
    }
}
