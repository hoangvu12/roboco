package sh.roboco.android.sessions

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The Sessions tab's wallpaper hero maths (android-ui-parity #46) — the
 * port of `SessionsViewController`'s two geometry pieces
 * (`ios:Threads/SessionListController.swift:372-381`):
 *
 * - the hero frame: `min(0.72 × screen height, 760 dp)` at the very top
 *   of the screen, fixed behind the list (`backgroundView`);
 * - the scroll fade: `1 − max(0, travel) / max(1, 0.6 × hero height)`,
 *   unclamped — `WallpaperArtwork` clamps the alpha to 0…1
 *   (`Wallpaper.swift:244-246`).
 */

/** `viewDidLayoutSubviews`: 72 % of the viewport, at most 760 dp. */
internal fun sessionsHeroHeight(screenHeight: Dp): Dp = minOf(screenHeight * 0.72f, HeroHeightCap)

private val HeroHeightCap = 760.dp

/**
 * `listDidScroll`: the fade from the list's scroll travel. **Not** clamped
 * on this side (the hero clamps to 0…1 when it multiplies the opacity);
 * `max(1, …)` keeps a zero-height hero from dividing by zero, with
 * `travel ≤ 1` then mapping to itself.
 */
internal fun sessionsScrollFade(travelDp: Float, heroHeightDp: Float): Float =
    1f - max(0f, travelDp) / max(1f, heroHeightDp * 0.6f)

/**
 * The list's scroll travel in px, measured over the **whole** `LazyColumn`
 * of `LargeTitleScaffold` starting at its item 0 (the `__large_title`
 * row) — the Android shape of UIKit's
 * `contentOffset.y + adjustedContentInset.top`, which is 0 at rest and
 * grows 1:1 with the scroll, including the 52 dp during which the large
 * title collapses.
 *
 * LazyColumn does not know the heights of items that have scrolled away,
 * so the distance is tracked incrementally:
 *
 * - **item 0 first** → exact: its scroll offset *is* the travel. This
 *   covers the whole fade range in practice (the large-title row is 52 dp
 *   and the fade ends at 0.6 × heroH ≥ 173 dp).
 * - **a common key** → continuous: the first visible item that was also
 *   visible in the previous layout anchors the delta, so items inserted
 *   or removed above the viewport do not jump the value (LazyColumn keeps
 *   the first visible item by key, so the common item's offset does not
 *   move — the same content-offset behaviour UIKit has).
 * - **first sight** (a restored scroll position, a jump) → estimated from
 *   the cached sizes of the items above plus the mean visible size.
 *
 * Calling [update] twice with the same layout returns the same value (the
 * delta is 0 for the common key); the result is never negative (a
 * pull-down's rubber band makes UIKit's travel negative, and the fade
 * stays pinned at 1 either way).
 */
internal class ScrollTravel {

    /** Key → offset (px) in the previous layout. */
    private var prev = HashMap<Any, Int>()

    /** Index → size (px), the sizes seen so far. */
    private val sizeByIndex = HashMap<Int, Int>()

    /** The item count of the layout `sizeByIndex` was collected for. */
    private var count = -1

    private var travel = 0f

    /**
     * Total scrolled distance in px from list item 0's rest position.
     * Idempotent for an unchanged layout.
     */
    fun update(firstIndex: Int, firstOffset: Int, totalItems: Int, visible: List<LazyListItemInfo>): Float {
        if (totalItems != count) {
            sizeByIndex.clear()
            count = totalItems
        }
        for (item in visible) sizeByIndex[item.index] = item.size
        travel = if (firstIndex == 0) {
            firstOffset.toFloat()
        } else {
            val common = visible.firstOrNull { prev.containsKey(it.key) }
            if (common != null) {
                travel + (prev.getValue(common.key) - common.offset)
            } else {
                // First sight / jump / restored position: estimate.
                val mean = if (visible.isEmpty()) 0f else visible.sumOf { it.size }.toFloat() / visible.size
                firstOffset + (0 until firstIndex).fold(0f) { acc, i -> acc + (sizeByIndex[i]?.toFloat() ?: mean) }
            }
        }.coerceAtLeast(0f)
        val next = HashMap<Any, Int>(visible.size * 2)
        for (item in visible) next[item.key] = item.offset
        prev = next
        return travel
    }
}
