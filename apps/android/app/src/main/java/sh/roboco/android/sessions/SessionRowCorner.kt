package sh.roboco.android.sessions

import androidx.compose.ui.graphics.Color
import sh.roboco.android.design.Palette
import sh.roboco.android.design.StatusGlyphKind

/**
 * What the title line's right corner shows — the discriminant of
 * `SessionCell.corner` (`SessionCell.swift:93-104`). [word] is the
 * verbatim English copy; [color] and [glyph] resolve the ticket's tables.
 *
 * `SendFailed` and [Failed] both read "Failed": a failed send paints it in
 * `danger`, an errored session in the softer `status.failed` tone.
 */
enum class RowCorner(val word: String) {
    SendFailed("Failed"),
    Working("Working"),
    Input("Input"),
    Failed("Failed"),
    Done("Done"),
}

/**
 * Desktop precedence (`SessionCell.swift:91-104`): a failed send beats
 * everything; then the live state; unseen-and-finished reads "Done";
 * otherwise the time label — null means "draw `vm.timeLabel` in
 * `status.time`, no glyph, the title extends to `wordX − 10`".
 */
fun corner(vm: SessionRowVM): RowCorner? {
    if (vm.sendFailed) return RowCorner.SendFailed
    return when (vm.status) {
        SessionRowVM.Status.Working -> RowCorner.Working
        SessionRowVM.Status.Awaiting -> RowCorner.Input
        SessionRowVM.Status.Errored -> RowCorner.Failed
        SessionRowVM.Status.Completed -> if (vm.unseen) RowCorner.Done else null
        SessionRowVM.Status.Idle -> null
    }
}

/** The corner word's colour — the "Corner precedence" table. */
fun RowCorner.color(p: Palette): Color = when (this) {
    RowCorner.SendFailed -> p.danger
    RowCorner.Working -> p.status.working
    RowCorner.Input -> p.status.input
    RowCorner.Failed -> p.status.failed
    RowCorner.Done -> p.status.done
}

/**
 * The corner's 12×12 glyph kind — the "Corner precedence" table. The
 * `Working` spinner's own cell tints belong to ticket 11's
 * `StatusGlyphKind.Spinner`; this only chooses the kind.
 */
fun RowCorner.glyph(p: Palette): StatusGlyphKind = when (this) {
    RowCorner.SendFailed -> StatusGlyphKind.Dot(p.danger)
    RowCorner.Working -> StatusGlyphKind.Spinner
    RowCorner.Input -> StatusGlyphKind.Dot(p.status.input)
    RowCorner.Failed -> StatusGlyphKind.Dot(p.status.failed)
    RowCorner.Done -> StatusGlyphKind.Check(p.status.done)
}
