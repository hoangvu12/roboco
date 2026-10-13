package sh.roboco.android.transcript

import androidx.compose.runtime.Immutable
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.Scroller

/**
 * The horizontal link slop, dp: `CGRect.insetBy(dx: -4, …)`
 * (`ios:Transcript/RowView.swift:310`). A tap within this far of a
 * fragment's left/right edge still opens it.
 */
internal const val LinkSlopX = 4f

/**
 * The vertical link slop, dp: `… insetBy(…, dy: -2)`
 * (`ios:Transcript/RowView.swift:310`).
 */
internal const val LinkSlopY = 2f

/**
 * A hit: the url and the fragment's **painted** rect, row-local dp with
 * the scroller offset applied, not inflated. The tap pipeline (ticket
 * 65's observer, completed in 126) turns this into a window-px
 * [LinkTap].
 */
@Immutable
internal data class LinkAt(val url: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The link hit-test (`ios:Transcript/RowView.swift:301-314`), pure so it
 * unit-tests without the core. Links are visited in [links] order and
 * the **first** match wins (inflated fragments of adjacent links can
 * overlap). Each fragment's rect is inflated by [LinkSlopX] /
 * [LinkSlopY] and containment is half-open on the right and bottom
 * edges, as `CGRect.contains` is.
 *
 * A link whose `scroller` index is in range is tested in that
 * scroller's **content** coordinates — `x − sc.x + offsetDp(s)`,
 * `y − sc.y` with the offset read at call time (116's live
 * [RealizedRow.scrollerOffsetDp], never a value cached at composition) —
 * and its [LinkAt] rect is reported where it is painted now:
 * `(sc.x + l.x − offset, sc.y + l.y)`. An out-of-range index is **not**
 * converted: the point is tested as a row point (iOS fact 4). There is
 * no viewport check (iOS parity; see the ticket's Comments).
 */
internal fun hitLink(
    links: List<LinkHit>,
    scrollers: List<Scroller>,
    offsetDp: (Int) -> Float,
    x: Float,
    y: Float,
): LinkAt? {
    for (l in links) {
        var px = x
        var py = y
        var dx = 0f
        var dy = 0f
        val s = l.scroller?.toInt()
        if (s != null && s < scrollers.size) {
            val sc = scrollers[s]
            val off = offsetDp(s)
            px = x - sc.x + off
            py = y - sc.y
            dx = sc.x - off
            dy = sc.y
        }
        if (px >= l.x - LinkSlopX && px < l.x + l.w + LinkSlopX &&
            py >= l.y - LinkSlopY && py < l.y + l.h + LinkSlopY
        ) {
            return LinkAt(l.url, l.x + dx, l.y + dy, l.w, l.h)
        }
    }
    return null
}

/**
 * Link at a point in row coordinates (dp; Rust points = dp). The row's
 * display's `links` against its `scrollers`, with the live scroller
 * offsets. `null` before the model is resolved or when nothing hit.
 */
internal fun linkAt(row: RealizedRow, xDp: Float, yDp: Float): LinkAt? {
    val d = row.model?.display ?: return null
    return hitLink(d.links, d.scrollers, row::scrollerOffsetDp, xDp, yDp)
}
