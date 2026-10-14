package sh.roboco.android.transcript.widgets

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import sh.roboco.android.transcript.RowFixtures
import sh.roboco.android.transcript.RowModel
import uniffi.roboco_core.Scroller
import uniffi.roboco_core.TextRun

/**
 * The shimmer's run filter (ticket 122) — iOS `drawRuns`
 * (`ios:Transcript/RowModel.swift:90-94`), verbatim: layer 0 only, the
 * baseline strictly inside `(y, y + h + 2]`, the run starting strictly
 * left of the widget's right edge. Plain JUnit 4, no Robolectric: the
 * fixture is 62's `RowFixtures`, built straight from the records.
 */
class ShimmerRunsTest {

    /** A one-layer row over the given runs (RowFixtures.text is 90 units). */
    private fun model(vararg runs: TextRun): RowModel = RowModel(RowFixtures.rowOf(runs.toList()))

    /** A scroller row: one scroller (index 0), for runs assigned to its layer. */
    private fun scrollerModel(vararg runs: TextRun): RowModel = RowModel(
        RowFixtures.rowOf(runs.toList(), scrollers = listOf(Scroller(16f, 40f, 288f, 32f, 400f))),
    )

    @Test
    fun theFilterMatchesTheIosRules() {
        // The widget rect (10, 20, 100, 20): the baseline must sit in
        // (20, 42] and the run must start strictly left of x 110. The
        // output keeps run order, as iOS draws.
        val tooLow = RowFixtures.run(0u, 3u, 10f, 20f) // baseline == y: excluded (open below)
        val first = RowFixtures.run(3u, 3u, 10f, 20.01f)
        val exactlyAtTheTop = RowFixtures.run(6u, 3u, 50f, 42f) // y + h: included (closed above)
        val lastX = RowFixtures.run(9u, 3u, 109.9f, 21f)
        val tooHigh = RowFixtures.run(12u, 3u, 10f, 42.01f) // baseline > y + h + 2
        val atRightEdge = RowFixtures.run(15u, 3u, 110f, 30f) // x == x + w: excluded (strict)
        val m = model(tooLow, first, exactlyAtTheTop, lastX, tooHigh, atRightEdge)
        assertArrayEquals(intArrayOf(1, 2, 3), shimmerRuns(m, 10f, 20f, 100f, 20f))
    }

    @Test
    fun scrollerLayerRunsAreNeverRedrawn() {
        // The code block's scroller run never matches, even at the same
        // geometry: iOS's `runsByLayer[0]` is the row canvas alone.
        val scrolled = RowFixtures.run(19u, 3u, 10f, 30f, scroller = 0u)
        val plain = RowFixtures.run(3u, 3u, 10f, 30f)
        val m = scrollerModel(scrolled, plain)
        assertArrayEquals(intArrayOf(1), shimmerRuns(m, 10f, 20f, 100f, 20f))
    }

    @Test
    fun aRowWithNoMatchingRunsFiltersToNothing() {
        // A row whose only run misses the widget vertically: the draw's
        // early-out (runs.isEmpty()) path.
        val m = model(RowFixtures.run(0u, 3u, 10f, 90f))
        assertArrayEquals(intArrayOf(), shimmerRuns(m, 10f, 20f, 100f, 20f))
    }
}
