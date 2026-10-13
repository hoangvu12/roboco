package sh.roboco.android.transcript

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.Motion
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind
import kotlin.math.max

/**
 * The context a row's slots share (ticket 65): the state (offset, fonts,
 * engine toggles), the realized row (coordinates, fade), the row's kind
 * and model, the paint palette and the actions provider. [actions] is a
 * **provider**, not the actions: widgets call `ctx.actions()` at click
 * time, never at composition, so a screen can rebuild its callbacks
 * freely — except 66's image tile, which takes the actions as a value
 * (its synchronous cache peek must run during composition) and keeps
 * them fresh through `rememberUpdatedState`. [live] is "the row has a
 * `Shimmer` widget" — the tool rail (121) reads it.
 */
class RowContext(
    val state: TranscriptState,
    val row: RealizedRow,
    val kind: RowKind,
    val model: RowModel,
    val palette: IntArray,
    val actions: () -> TranscriptActions,
    val live: Boolean,
)

/**
 * One realized row (ticket 65): exactly **one** root layout node (the host
 * indexes measurables by placement order), tagged by its kind, its layer-0
 * canvas drawn behind everything at row-local origin, the fade-in applied
 * to the whole row through [Modifier.graphicsLayer], and the row's
 * scrollers and row-level widgets as children in iOS z-order (scrollers in
 * index order, then widgets in display order).
 */
@Composable
internal fun RowHost(
    state: TranscriptState,
    frame: LayoutFrame,
    p: RowPlacement,
    palette: IntArray,
    actionsProvider: () -> TranscriptActions,
) {
    val d = LocalDensity.current.density

    // Composition replaces iOS's view pool: `key(p.key)` in the host
    // reuses this row when it stays realized and discards it (forgetting
    // the fade, scroll offsets and veil state) when it leaves the band.
    val row = remember(p.key) { RealizedRow(p.key, state, p).also { TranscriptPerf.viewsCreated++ } }

    // A never-seen key starts at alpha 0 and fades in; a known key (the
    // first non-empty frame pre-marked them) starts at 1 and never runs.
    val appear = remember(p.key) { Animatable(if (state.knownRows.claim(p.key)) 0f else 1f) }
    LaunchedEffect(p.key) { if (appear.value < 1f) appear.animateTo(1f, Motion.rowFadeIn()) }

    // `display()` must be called on the frame the placement came from
    // (indices are per frame); a width or version change rebuilds.
    val model = remember(p.key, p.version, frame.width()) { state.modelFor(frame, p) }

    // Published in composition; equal writes do not invalidate.
    row.placement = p
    row.density = d
    row.model = model
    row.appear = appear

    Box(
        Modifier
            .testTag(rowTag(p.kind))
            .graphicsLayer { alpha = appear.value } // read in the layer phase
            .drawBehind {
                // No style sync here: 61's apply synced the table, 62's
                // draw calls `fonts.useDensity` itself. Pass.All.
                val m = model ?: return@drawBehind
                drawIntoCanvas { c -> m.draw(0, c.nativeCanvas, d, palette, state.fonts) }
            },
    ) {
        if (model != null) {
            val ctx = RowContext(
                state = state,
                row = row,
                kind = p.kind,
                model = model,
                palette = palette,
                actions = actionsProvider,
                live = model.display.widgets.any { it.kind is WidgetKind.Shimmer },
            )
            model.display.scrollers.forEachIndexed { i, s -> RowScroller(ctx, i, s) }
            val n = model.display.scrollers.size
            for (w in model.display.widgets) {
                // A bad scroller index falls back to the row (iOS's rule;
                // Rust never emits either case).
                val sc = w.scroller?.toInt()
                if (sc == null || sc >= n) WidgetBox(ctx, w)
            }
        }
    }
}

/**
 * The row identifier of a kind (iOS `RowView.swift:146-153`). Ticket 129
 * later moves the tag to the row's full-size text node (same count and
 * bounds, so these tags' tests keep passing).
 */
internal fun rowTag(kind: RowKind): String = when (kind) {
    RowKind.MARKDOWN -> "row-markdown"
    RowKind.USER -> "row-user"
    RowKind.TOOLS -> "row-tools"
    RowKind.CHIP -> "row-chip"
    RowKind.IMAGE -> "row-image"
    RowKind.WORKING -> "row-working"
}

/**
 * A widget's slot (ticket 65): the rect `(w.x, w.y, max(w.w, 0),
 * max(w.h, 0))` dp at `absoluteOffset` (Rust x is left-origin regardless
 * of layout direction), filled by its owner's composable. The host is the
 * scroller's content box when `w.scroller` points at one (its content
 * coordinates), else the row (row coordinates).
 */
@Composable
internal fun WidgetBox(ctx: RowContext, w: Widget) {
    Box(
        Modifier
            .absoluteOffset(w.x.dp, w.y.dp)
            .size(max(w.w, 0f).dp, max(w.h, 0f).dp),
    ) {
        RowWidget(ctx, w)
    }
}
