package sh.roboco.android.transcript

import androidx.compose.runtime.Composable
import sh.roboco.android.transcript.widgets.ImageTile
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * Dispatches one widget to its owning ticket's composable (ticket 65; iOS
 * `layoutWidgets`, `ios:Transcript/RowView.swift:193-200` — rebuilt on
 * every configure, in `d.widgets` order, so later widgets sit on top).
 *
 * Exhaustive on purpose, with **no** `else`: a new Rust `WidgetKind`
 * fails compilation here instead of silently rendering nothing. Each
 * branch is empty until its ticket lands (66's image tile is wired).
 */
@Composable
internal fun RowWidget(ctx: RowContext, w: Widget) {
    when (w.kind) {
        // ticket 119 (copy-code button)
        WidgetKind.CopyCode -> Unit
        // ticket 120 (disclosure, tool toggle, chevron)
        is WidgetKind.Disclosure -> Unit
        is WidgetKind.ToolToggle -> Unit
        is WidgetKind.Chevron -> Unit
        // ticket 123 (status glyphs)
        is WidgetKind.ToolStatus -> Unit
        WidgetKind.Spinner -> Unit
        is WidgetKind.Working -> Unit
        // ticket 66 (+ 67's upload ring through `overlay`, 137 extends)
        is WidgetKind.Image -> ImageTile(w.kind.reference, w.w, w.h, ctx.actions())
        // ticket 127 (the mono tool-detail sheet)
        is WidgetKind.Detail -> Unit
        // ticket 124 (SF-symbol-like icons)
        is WidgetKind.Icon -> Unit
        // ticket 121 (the tool rail)
        is WidgetKind.ToolRail -> Unit
        // ticket 122 (the shimmer sweep)
        WidgetKind.Shimmer -> Unit
    }
}
