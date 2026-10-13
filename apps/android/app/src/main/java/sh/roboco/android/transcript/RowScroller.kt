package sh.roboco.android.transcript

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import uniffi.roboco_core.Scroller

/**
 * One horizontal scroller slot, layer `index + 1` (ticket 65). The default
 * here is a **static** viewport — iOS's state right after `configure`
 * (`contentOffset = .zero`, `ios:Transcript/RowView.swift:158-172`): an
 * outer box clipped to `(s.x, s.y, s.w, s.h)`, a content box
 * `s.contentWidth` wide that draws the scroller's layer, and the widgets
 * whose `scroller` index points here placed inside it, so they scroll with
 * the content once scrolling exists.
 *
 * Ticket 116 replaces only this body: horizontal scroll, edge fades,
 * offset persistence (through [RealizedRow.scrollerOffsetDp]). No style
 * sync before the draw: ticket 61's apply syncs the table, ticket 62's
 * `RowModel.draw` calls `fonts.useDensity` itself.
 */
@Composable
internal fun RowScroller(ctx: RowContext, index: Int, s: Scroller) {
    val d = LocalDensity.current.density
    val fonts = ctx.state.fonts
    val model = ctx.model
    Box(
        Modifier
            .absoluteOffset(s.x.dp, s.y.dp)
            .size(s.w.dp, s.h.dp)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(s.contentWidth.dp, s.h.dp)
                .drawBehind {
                    drawIntoCanvas { c ->
                        model.draw(index + 1, c.nativeCanvas, d, ctx.palette, fonts)
                    }
                },
        ) {
            for (w in ctx.model.display.widgets) {
                if (w.scroller?.toInt() == index) WidgetBox(ctx, w)
            }
        }
    }
}
