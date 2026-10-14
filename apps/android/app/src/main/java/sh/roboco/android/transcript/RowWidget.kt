package sh.roboco.android.transcript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import sh.roboco.android.transcript.widgets.CopyCodeButton
import sh.roboco.android.transcript.widgets.DisclosureTarget
import sh.roboco.android.transcript.widgets.ImageTile
import sh.roboco.android.transcript.widgets.RailSpec
import sh.roboco.android.transcript.widgets.ToolChevron
import sh.roboco.android.transcript.widgets.ToolRail
import sh.roboco.android.transcript.widgets.ToolToggleTarget
import sh.roboco.android.transcript.widgets.TranscriptIcon
import sh.roboco.android.transcript.widgets.UploadRing
import sh.roboco.android.transcript.widgets.isPendingReference
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * Dispatches one widget to its owning ticket's composable (ticket 65; iOS
 * `layoutWidgets`, `ios:Transcript/RowView.swift:193-200` — rebuilt on
 * every configure, in `d.widgets` order, so later widgets sit on top).
 *
 * Exhaustive on purpose, with **no** `else`: a new Rust `WidgetKind`
 * fails compilation here instead of silently rendering nothing. Each
 * branch is empty until its ticket lands (66's image tile is wired, with
 * 67's upload ring in its `overlay` slot).
 *
 * The subject is bound to a local so the Image branch's smart cast
 * survives into the `overlay` lambda (a property smart cast would not).
 */
@Composable
internal fun RowWidget(ctx: RowContext, w: Widget) {
    when (val k = w.kind) {
        // ticket 119 (code-block copy): the clipboard write, the Light
        // haptic and the 1400 ms checkmark swap live inside the button; a
        // null payload copies the empty string, like iOS `w.payload ?? ""`.
        WidgetKind.CopyCode -> CopyCodeButton(payload = w.payload ?: "")
        // ticket 120 (disclosure, tool toggle, chevron): three separate
        // widgets with separate rects — the chevron is never dimmed by a
        // disclosure press, and the toggles carry `open` as painted.
        is WidgetKind.Disclosure -> DisclosureTarget(
            expanded = k.expanded,
            onToggle = { ctx.state.toggleRow(ctx.model.display.key) },
        )
        is WidgetKind.ToolToggle -> ToolToggleTarget(
            open = k.open,
            onToggle = { ctx.state.toggleDetail(ctx.model.display.key, k.detail, k.open) },
        )
        is WidgetKind.Chevron -> ToolChevron(rowKey = ctx.model.display.key, expanded = k.expanded)
        // ticket 123 (status glyphs)
        is WidgetKind.ToolStatus -> Unit
        WidgetKind.Spinner -> Unit
        is WidgetKind.Working -> Unit
        // ticket 66 (+ 67's upload ring through `overlay`, 137 extends)
        is WidgetKind.Image -> ImageTile(
            reference = k.reference,
            widthDp = w.w,
            heightDp = w.h,
            actions = ctx.actions(),
            overlay = {
                // Ticket 67: the ring lives only over a pending reference,
                // keyed by it — a tile reused for another pending reference
                // starts a fresh ring at alpha 0, and a host-path reference
                // drops the ring at once, with no fade (iOS rebuilds the
                // widget view without one). The lambda (never the value)
                // keeps a progress change recomposing the ring scope only.
                if (isPendingReference(k.reference)) key(k.reference) {
                    UploadRing(
                        progress = { ctx.state.uploadProgress },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            },
        )
        // ticket 127 (the mono tool-detail sheet): an invisible tap target;
        // the actions are read at click time so a rebuilt TranscriptActions
        // is honoured.
        is WidgetKind.Detail -> DetailWidget(k.title, w.payload ?: "") { t, s ->
            ctx.actions().showDetail(t, s)
        }
        // ticket 124 (icon widget): the name decides the art — aspect-fit
        // template drawables for tool-*, untinted colour PNGs (with a
        // -dark variant per appearance) for fileicon-*, ticket 06 glyphs
        // at rect.h × 0.8 Medium for SF names, nothing (logged once) for
        // anything else. Decorative: no tap handling, no semantics node.
        is WidgetKind.Icon -> TranscriptIcon(
            name = k.name,
            color = k.color,
            heightDp = w.h.coerceAtLeast(0f),
            palette = ctx.palette,
        )
        // ticket 121 (the tool rail): the group's row key identifies the
        // group in the process-wide `revealed` memory, the model is the
        // re-plan token (a new iOS configure), and the cover runs to the
        // row's right edge — `rowWidth` in iOS is `d.width - rect.minX`.
        is WidgetKind.ToolRail -> ToolRail(
            groupKey = ctx.model.display.key,
            spec = remember(k) { RailSpec.of(k) },
            rowWidthDp = ctx.model.display.width - w.x,
            live = ctx.live,
            buildToken = ctx.model,
        )
        // ticket 122 (the shimmer sweep)
        WidgetKind.Shimmer -> Unit
    }
}
