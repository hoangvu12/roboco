package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directional prefetch ranges (ticket 117; iOS
 * `ios:Transcript/TranscriptListView.swift:470-475`): the worked-values
 * table (viewport 891 dp; `ahead = 1.5 x viewport`, trailing
 * `ahead / 2`), the `b <= 0` filter and the pure-vs-gated split. Plain
 * JVM.
 */
class PrefetchRangesTest {

    @Test
    fun towardTheTailAtOffsetZeroDropsTheRangeAboveTheContentTop() {
        // band [−768, 1779): ahead (1779, 3115.5); trailing
        // (−1436.25, −768) is dropped by the b > 0 filter.
        assertEquals(
            listOf(1779f to 3115.5f),
            prefetchRanges(-768f, 1779f, 891f, towardTop = false),
        )
    }

    @Test
    fun towardTheTailFromMidContentPrefetchesAheadFirstThenBehind() {
        assertEquals(
            listOf(4595f to 5931.5f, 1379.75f to 2048f),
            prefetchRanges(2048f, 4595f, 891f, towardTop = false),
        )
    }

    @Test
    fun towardTheTopMirrorsTheOrderAndShrinksTheTrailingRange() {
        assertEquals(
            listOf(711.5f to 2048f, 4595f to 5263.25f),
            prefetchRanges(2048f, 4595f, 891f, towardTop = true),
        )
    }

    @Test
    fun aZeroViewportStillReturnsRanges() {
        // The `viewport <= 0` stop lives in afterLayout, not here.
        assertEquals(
            listOf(100f to 100f),
            prefetchRanges(0f, 100f, 0f, towardTop = false),
        )
    }

    @Test
    fun aBandEntirelyAboveTheContentDropsBothRanges() {
        assertTrue(
            prefetchRanges(-4000f, -2000f, 891f, towardTop = true).isEmpty(),
        )
    }

    @Test
    fun bothTowardTopRangesSurviveWhenTheBandIsInsideTheContent() {
        assertEquals(
            listOf(163.5f to 1500f, 3000f to 3668.25f),
            prefetchRanges(1500f, 3000f, 891f, towardTop = true),
        )
    }
}
