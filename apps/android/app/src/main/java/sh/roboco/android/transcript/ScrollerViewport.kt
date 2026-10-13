package sh.roboco.android.transcript

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import sh.roboco.android.design.horizontalScrollEdgeFade
import uniffi.roboco_core.Scroller

/**
 * The constants of the transcript scrollers (ticket 116), all from the iOS
 * source: the no-scroll threshold that gates both dragging and the mask
 * (`ios:Design/EdgeFade.swift:72`, maxX > 0.5), and the test tag the
 * ticket's tests address the viewport by (iOS sets no identifier).
 */
object ScrollerDefaults {
    /**
     * Below this overflow (dp) a scroller neither scrolls nor fades. Must
     * equal ticket 10's `EdgeFade.ScrollThreshold` (0.5 dp), which gates the
     * mask: the same number, so a scroller never captures a drag it cannot
     * visibly act on (upstream PR #621 used `w + 2` for the same reason).
     */
    const val ScrollThresholdDp = 0.5f

    /** The viewport's test tag (tests only). */
    const val TestTag = "transcript-scroller"
}

/**
 * The iOS scroller coordinate conversions
 * (`ios:Transcript/RowView.swift:78-90`, `:301-314`): link hit-testing
 * (126) and the accessibility elements (129) use these to move between
 * row-local and scroller-content coordinates, together with the live
 * offset from [RealizedRow.scrollerOffsetDp]. All values are dp (Rust
 * points), like every [Scroller] field. Pure functions, unit-tested.
 */
object ScrollerGeometry {
    /** The scroll range end: `max(0, contentWidth − w)` (EdgeFade.swift:71). */
    fun maxOffset(s: Scroller): Float = max(0f, s.contentWidth - s.w)

    /** iOS `maxX > 0.5` (EdgeFade.swift:72): this scroller can move. */
    fun isScrollable(s: Scroller): Boolean = s.contentWidth - s.w > ScrollerDefaults.ScrollThresholdDp

    /** Row-local point → scroller content point: `x − s.x + offset, y − s.y`. */
    fun rowToContent(x: Float, y: Float, s: Scroller, offsetDp: Float): Offset =
        Offset(x - s.x + offsetDp, y - s.y)

    /** Scroller content rect → row-local rect (where it is painted now). */
    fun contentToRow(r: Rect, s: Scroller, offsetDp: Float): Rect = r.translate(s.x - offsetDp, s.y)

    /** The viewport in row-local dp: `(s.x, s.y)` to `(s.x + w, s.y + h)`. */
    fun viewport(s: Scroller): Rect = Rect(s.x, s.y, s.x + max(s.w, 0f), s.y + max(s.h, 0f))
}

/**
 * One [ScrollState] per scroller identity: the same index (the caller's
 * `key(i)`) and the same origin `(s.x, s.y)`. A new version, a new
 * `contentWidth` or a new `h` keeps the state — `horizontalScroll` updates
 * `ScrollState.maxValue` during measure and its setter clamps `value` to
 * the new max, so the offset rides content changes clamped, never
 * snapping back (iOS resets `contentOffset` to zero on every `configure`;
 * see the ticket's Roboco adaptation). A moved origin means a different
 * scroller, so it starts at 0.
 *
 * Not `rememberSaveable`: the offset must not survive the row leaving the
 * realized band, only its time inside it.
 */
@Composable
internal fun rememberScrollerState(s: Scroller): ScrollState = remember(s.x, s.y) { ScrollState(0) }

/**
 * The real horizontal scroller viewport (ticket 116), replacing 65's
 * static one: a clipped box at the Rust viewport rect whose content box
 * is `contentWidth` wide and draws `drawLayer` (layer `i + 1`, painted
 * with the default pass — the streaming veil never applies to scroller
 * layers). Pure: previews, the gallery and the tests use it with no core.
 *
 * The modifier order is the contract: `clipToBounds` clips vertically too
 * (the scroll modifier's own clip covers the scroll axis only);
 * [horizontalScrollEdgeFade] comes **before** [horizontalScroll] so the
 * mask stays fixed to the viewport while the content moves under it; the
 * scroll itself is enabled only when the content overflows past
 * [ScrollerDefaults.ScrollThresholdDp] — a fitting scroller never
 * captures a horizontal drag (iOS `alwaysBounceHorizontal = false`), and
 * an overflowing one gets the platform overscroll at both ends (the
 * rubber-band stand-in) and ticket 134's iOS deceleration on release
 * (decision TRANSCRIPT-D2). Scroll state is read only in the layer and
 * draw phases: scrolling recomposes nothing.
 *
 * Everything inside runs left-to-right whatever the app's layout
 * direction: Rust x is left-origin and UIScrollView never mirrors.
 */
@Composable
internal fun ScrollerViewport(
    s: Scroller,
    state: ScrollState,
    drawLayer: DrawScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val scrollable = ScrollerGeometry.isScrollable(s)
        Box(
            modifier
                .absoluteOffset(s.x.dp, s.y.dp)
                .size(max(s.w, 0f).dp, max(s.h, 0f).dp)
                .testTag(ScrollerDefaults.TestTag)
                .clipToBounds()
                .horizontalScrollEdgeFade(state)
                .horizontalScroll(
                    state = state,
                    enabled = scrollable,
                    flingBehavior = rememberIosFlingBehavior(),
                ),
        ) {
            Box(
                Modifier
                    .size(max(s.contentWidth, 0f).dp, max(s.h, 0f).dp)
                    .drawBehind(drawLayer),
                content = content,
            )
        }
    }
}
