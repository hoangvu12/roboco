package sh.roboco.android.transcript

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.Scroller

/**
 * [RowModel]'s layer grouping and the fade-assignment rule
 * (`ios:Transcript/RowModel.swift:23-54`) — pure logic over the generated
 * records. Plain JUnit 4, no Robolectric: like `TranscriptPaletteTest`,
 * constructing the data records touches no JNA and no `android.os`, and
 * the constructor only calls `android.util.Log` for malformed scroller
 * indices (that path is covered by [RowModelDrawTest] under Robolectric).
 * Drawing lives in [RowModelDrawTest] and [RowModelScreenshotTest].
 */
class RowModelTest {

    // ------------------------------------------------- the fixture row

    @Test
    fun theFixtureRowGroupsByLayerAndAssignsItsFade() {
        val model = RowModel(RowFixtures.row())

        // One scroller → two layers.
        assertEquals(2, model.layers)
        assertArrayEquals(intArrayOf(0, 2), model.runsByLayer[0])
        assertArrayEquals(intArrayOf(1), model.runsByLayer[1])
        assertArrayEquals(intArrayOf(0, 1), model.boxesByLayer[0])
        assertArrayEquals(intArrayOf(), model.boxesByLayer[1])

        // The trailing fade claims run 2 only.
        assertEquals(1, model.fadesByLayer[0].size)
        assertEquals(0, model.fadesByLayer[0][0].fade)
        assertArrayEquals(intArrayOf(2), model.fadesByLayer[0][0].runs)
        assertEquals(0, model.fadesByLayer[1].size)
        assertTrue(model.isFaded(2))
        assertFalse(model.isFaded(0))
        assertFalse(model.isFaded(1))
    }

    @Test
    fun aRowWithoutFadesMarksNothingFaded() {
        val model = RowModel(RowFixtures.rowOf(runs = listOf(RowFixtures.run(0u, 19u, 16f, 28f))))
        assertEquals(1, model.layers)
        for (layer in 0 until model.layers) assertTrue(model.fadesByLayer[layer].isEmpty())
        assertFalse(model.isFaded(0))
    }

    // ---------------------------------------------------- fade rules
    // The rule row: fade (x 100, y 50, w 40, h 20).

    /** One layer-0 run at (x, baseline) against one fade; was it claimed? */
    private fun claimed(baseline: Float, x: Float = 16f, edge: FadeEdge = FadeEdge.TRAILING): Boolean {
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(RowFixtures.run(0u, 5u, x, baseline)),
                fades = listOf(RowFixtures.fade(100f, 50f, 40f, 20f, edge)),
            ),
        )
        return model.isFaded(0)
    }

    @Test
    fun theVerticalBandIsOpenBelowAndClosedAboveWithHalfUnitSlack() {
        assertFalse("baseline == f.y is not > f.y", claimed(baseline = 50f))
        assertTrue(claimed(baseline = 50.01f))
        assertTrue("baseline == f.y + f.h + 0.5 is included", claimed(baseline = 70.5f))
        assertFalse(claimed(baseline = 70.51f))
    }

    @Test
    fun trailingFadesRequireTheRunToStartLeftOfTheirRightEdge() {
        assertTrue(claimed(baseline = 60f, x = 139.9f))
        assertFalse("x == f.x + f.w is not < f.x + f.w", claimed(baseline = 60f, x = 140f))
    }

    @Test
    fun bottomFadesClaimAnyX() {
        assertTrue(claimed(baseline = 60f, x = 500f, edge = FadeEdge.BOTTOM))
    }

    @Test
    fun aRunIsClaimedByAtMostOneFadeTheFirstInDisplayOrder() {
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(RowFixtures.run(0u, 5u, 16f, 60f)),
                fades = listOf(
                    RowFixtures.fade(100f, 50f, 40f, 20f),
                    RowFixtures.fade(100f, 50f, 40f, 20f),
                ),
            ),
        )

        assertEquals(2, model.fadesByLayer[0].size)
        // The first fade takes the run …
        assertEquals(0, model.fadesByLayer[0][0].fade)
        assertArrayEquals(intArrayOf(0), model.fadesByLayer[0][0].runs)
        // … and the second keeps an empty group, still recorded.
        assertEquals(1, model.fadesByLayer[0][1].fade)
        assertTrue(model.fadesByLayer[0][1].runs.isEmpty())
        assertTrue(model.isFaded(0))
    }

    @Test
    fun aFadeOnAScrollerLayerNeverClaimsLayerZeroRuns() {
        val model = RowModel(
            RowFixtures.rowOf(
                runs = listOf(RowFixtures.run(0u, 5u, 16f, 60f)), // layer 0
                scrollers = listOf(Scroller(0f, 0f, 100f, 20f, 100f)),
                fades = listOf(RowFixtures.fade(100f, 50f, 40f, 20f, FadeEdge.TRAILING, 0u)), // layer 1
            ),
        )

        assertFalse(model.isFaded(0))
        assertTrue(model.fadesByLayer[0].isEmpty())
        assertEquals(1, model.fadesByLayer[1].size)
        assertTrue("no layer-1 runs exist to claim", model.fadesByLayer[1][0].runs.isEmpty())
    }

    // ------------------------------------------------------ threading

    @Test
    fun constructionRunsOffTheMainThread() {
        // Ticket 117 builds models on the prefetch thread; nothing in the
        // constructor may touch fonts, paints or the main looper.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val model = executor.submit<RowModel> { RowModel(RowFixtures.row()) }.get(5, TimeUnit.SECONDS)
            assertEquals(2, model.layers)
            assertTrue(model.isFaded(2))
        } finally {
            executor.shutdownNow()
        }
    }
}
