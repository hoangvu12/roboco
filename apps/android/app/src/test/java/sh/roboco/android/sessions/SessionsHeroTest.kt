package sh.roboco.android.sessions

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero formulas and [ScrollTravel] (android-ui-parity #46) — plain
 * JVM, no Robolectric: pure dp/px maths over fake [LazyListItemInfo]s
 * (the interface is index/key/offset/size only; `contentType` keeps its
 * default).
 */
class SessionsHeroTest {

    // -------------------------------------------------- sessionsHeroHeight

    @Test
    fun heroHeightFollowsTheReferenceTable() {
        // 72 % of the screen height, capped at 760 dp.
        assertEquals(629.28f, sessionsHeroHeight(874.dp).value, 1e-3f)
        assertEquals(658.8f, sessionsHeroHeight(915.dp).value, 1e-3f)
        assertEquals(289.44f, sessionsHeroHeight(402.dp).value, 1e-3f)
        assertEquals(576f, sessionsHeroHeight(800.dp).value, 1e-3f)
    }

    @Test
    fun heroHeightCapsAt760() {
        // 0.72 × 1100 = 792 > 760.
        assertEquals(760f, sessionsHeroHeight(1100.dp).value, 1e-3f)
        assertEquals(760f, sessionsHeroHeight(2000.dp).value, 1e-3f)
    }

    // ------------------------------------------------- sessionsScrollFade

    @Test
    fun scrollFadeFollowsTheTravelTable() {
        // H = 874 → heroH = 629.28 → the fade distance is 377.568.
        val heroH = sessionsHeroHeight(874.dp).value
        assertEquals(1f, sessionsScrollFade(0f, heroH), 1e-5f)
        assertEquals(0.862277f, sessionsScrollFade(52f, heroH), 1e-5f)
        assertEquals(0.735147f, sessionsScrollFade(100f, heroH), 1e-5f)
        assertEquals(0.5f, sessionsScrollFade(188.784f, heroH), 1e-5f)
        assertEquals(0f, sessionsScrollFade(377.568f, heroH), 1e-5f)
    }

    @Test
    fun scrollFadeIsUnclampedPastTheDistance() {
        // The hero clamps to 0…1 (Wallpaper.swift:244-246); this side may
        // go negative.
        val heroH = sessionsHeroHeight(874.dp).value
        assertTrue(sessionsScrollFade(400f, heroH) < 0f)
        assertEquals(-0.05937f, sessionsScrollFade(400f, heroH), 1e-3f)
    }

    @Test
    fun aPulledDownListKeepsTheFadeAtOne() {
        // Rubber band: a negative travel is clamped away by max(0, …).
        assertEquals(1f, sessionsScrollFade(-52f, 629.28f), 1e-5f)
    }

    @Test
    fun aZeroHeroHeightDividesByOne() {
        // max(1, …): travel ≤ 1 maps to itself.
        assertEquals(0.5f, sessionsScrollFade(0.5f, 0f), 1e-5f)
        assertEquals(0f, sessionsScrollFade(1f, 0f), 1e-5f)
        assertEquals(-1f, sessionsScrollFade(2f, 0f), 1e-5f)
    }

    // ---------------------------------------------------------- ScrollTravel

    /** index / key / offset / size — the four members of the interface. */
    private data class FakeItem(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo

    @Test
    fun itemZeroIsExact() {
        val travel = ScrollTravel()
        // Rest: item 0 at offset 0.
        assertEquals(0f, travel.update(0, 0, 4, items(0 to "a")), 0f)
        // Scrolled 52 dp-equivalent px into item 0.
        assertEquals(52f, travel.update(0, 52, 4, items(0 to "a")), 0f)
        assertEquals(120f, travel.update(0, 120, 4, items(0 to "a")), 0f)
    }

    @Test
    fun aCommonKeyKeepsTheValueContinuousAcrossAnItemBoundary() {
        val travel = ScrollTravel()
        // Item 0 (60 px) at offset 0; item 1 visible at 60.
        assertEquals(0f, travel.update(0, 0, 4, listOf(FakeItem(0, "a", 0, 60), FakeItem(1, "b", 60, 40))), 0f)
        // Scroll 70 px: item 0 is gone, item 1 (the common key) moved from
        // 60 to -10 — the delta is 70, and first-sight is never consulted.
        assertEquals(
            70f,
            travel.update(1, 10, 4, listOf(FakeItem(1, "b", -10, 40), FakeItem(2, "c", 30, 40))),
            0f,
        )
        // And once more: item 1 at -35 → +25 px further.
        assertEquals(
            95f,
            travel.update(1, 35, 4, listOf(FakeItem(1, "b", -35, 40), FakeItem(2, "c", 5, 40))),
            0f,
        )
    }

    @Test
    fun anInsertAboveTheViewportDoesNotJumpTheValue() {
        val travel = ScrollTravel()
        // Establish: item 0 (60 px) at offset 0, item 1 at 60.
        assertEquals(
            0f,
            travel.update(0, 0, 4, listOf(FakeItem(0, "a", 0, 60), FakeItem(1, "b", 60, 40))),
            0f,
        )
        // Scrolled 70 px: item 0 is gone, item 1 (the common key) moved
        // from 60 to −10.
        assertEquals(
            70f,
            travel.update(1, 10, 4, listOf(FakeItem(1, "b", -10, 40), FakeItem(2, "c", 30, 40))),
            0f,
        )
        // A leading item (50's connection line) is inserted above; the
        // common item keeps its offset (LazyColumn anchors on the key),
        // the count grows and the indices shift — the travel must not
        // move.
        assertEquals(
            70f,
            travel.update(2, 10, 5, listOf(FakeItem(2, "b", -10, 40), FakeItem(3, "c", 30, 40))),
            0f,
        )
    }

    @Test
    fun firstSightEstimatesFromCachedSizesPlusTheMean() {
        val travel = ScrollTravel()
        // 1. A layout at item 0 — caches every visible size.
        assertEquals(
            0f,
            travel.update(0, 0, 5, listOf(FakeItem(0, "a", 0, 60), FakeItem(1, "b", 60, 40), FakeItem(2, "c", 100, 40))),
            0f,
        )
        // 2. A jump to index 3 with no common key: the estimate is
        // firstOffset + size(0) + size(1) + size(2) = 10 + 60 + 40 + 40.
        assertEquals(
            150f,
            travel.update(3, 10, 5, listOf(FakeItem(3, "d", -10, 40), FakeItem(4, "e", 30, 80))),
            0f,
        )
    }

    @Test
    fun aRepeatedLayoutIsIdempotent() {
        val travel = ScrollTravel()
        assertEquals(
            0f,
            travel.update(0, 0, 4, listOf(FakeItem(0, "a", 0, 60), FakeItem(1, "b", 60, 40))),
            0f,
        )
        val layout = listOf(FakeItem(1, "b", -10, 40), FakeItem(2, "c", 30, 40))
        assertEquals(70f, travel.update(1, 10, 4, layout), 0f)
        assertEquals(70f, travel.update(1, 10, 4, layout), 0f)
        assertEquals(70f, travel.update(1, 10, 4, layout), 0f)
    }

    @Test
    fun theTravelIsNeverNegative() {
        val travel = ScrollTravel()
        // A pull-down at the very top: offset 0, no room above.
        assertEquals(0f, travel.update(0, 0, 4, items(0 to "a")), 0f)
        assertTrue(travel.update(0, 0, 4, items(0 to "a")) >= 0f)
        // A common item that moved DOWN (a negative delta) never drags the
        // travel below zero: travel 5, then the anchor jumps from 10 to
        // 60 (a pull-back past the top) — 5 + (10 − 60) = −45, clamped.
        assertEquals(
            5f,
            travel.update(0, 5, 4, listOf(FakeItem(0, "a", -5, 60), FakeItem(1, "b", 10, 40))),
            0f,
        )
        assertEquals(
            0f,
            travel.update(1, 0, 4, listOf(FakeItem(1, "b", 60, 40), FakeItem(2, "c", 100, 40))),
            0f,
        )
    }

    /** One item per (index → key) pair, offset 0. */
    private fun items(vararg pairs: Pair<Int, Any>): List<LazyListItemInfo> =
        pairs.map { (index, key) -> FakeItem(index, key, 0, 40) }
}
