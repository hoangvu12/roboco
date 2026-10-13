package sh.roboco.android.transcript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import uniffi.roboco_core.Scroller

/**
 * One horizontal scroller slot, layer `index + 1` (tickets 65 + 116): the
 * real scrolling viewport — a horizontal scroll over `s.contentWidth` with
 * ticket 10's 24 dp travel-ramped edge fades, the iOS deceleration
 * (decision TRANSCRIPT-D2), the platform overscroll at both ends and the
 * offset kept while the row's key stays realized and the scroller's origin
 * `(s.x, s.y)` is unchanged. The viewport clips on all four sides; the
 * widgets whose `scroller` index points here live inside the scrolling
 * content, so they move 1:1 and are masked with it.
 *
 * The row offset registry ([RealizedRow.scrollers]) is fed in a
 * `SideEffect` and cleared on dispose, so [RealizedRow.scrollerOffsetDp]
 * always answers for the scrollers that are composed right now.
 *
 * No style sync before the draw: ticket 61's apply syncs the table, ticket
 * 62's `RowModel.draw` calls `fonts.useDensity` itself, and the scroller
 * layer always paints the default pass — the streaming veil (118) is layer
 * 0 only.
 */
@Composable
internal fun RowScroller(ctx: RowContext, index: Int, s: Scroller) {
    val density = LocalDensity.current.density
    val state = rememberScrollerState(s)
    val row = ctx.row
    SideEffect { row.scrollers.register(index, state, density) }
    DisposableEffect(row, index, state) { onDispose { row.scrollers.unregister(index, state) } }

    val model = ctx.model
    val palette = ctx.palette
    val fonts = ctx.state.fonts
    ScrollerViewport(
        s = s,
        state = state,
        drawLayer = {
            drawIntoCanvas { c ->
                model.draw(index + 1, c.nativeCanvas, density, palette, fonts)
            }
        },
    ) {
        for (w in model.display.widgets) {
            if (w.scroller?.toInt() == index) WidgetBox(ctx, w)
        }
    }
}
