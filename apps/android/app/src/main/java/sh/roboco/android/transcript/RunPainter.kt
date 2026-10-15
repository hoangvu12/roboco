package sh.roboco.android.transcript

import android.graphics.Canvas
import sh.roboco.android.design.color

/**
 * The model-level facade over ticket 63's run painting, for the callers
 * that do not own a [RowModel] draw loop: the streaming veil (118) clips
 * at glyph edges with [advanceTo], and the shimmer re-draw (122) repaints
 * text in one explicit colour with [drawRunText], creating a facade with
 * [RunPainter] if the host has none.
 *
 * Stateless by design — every cached object lives in [TextRunPainter] or
 * the [RowModel] — and, like them, drawing (main) thread only. Call it
 * from inside a draw cycle that has already synced [StyleFonts] to the
 * canvas density (the first statement of [RowModel.draw] does).
 */
class RunPainter {

    /** The same per-run path as [RowModel]'s own draw loop: guard, glyphs, bar. */
    fun drawRun(canvas: Canvas, model: RowModel, i: Int, d: Float, fonts: StyleFonts, palette: IntArray) {
        val font = model.entryFor(i, fonts) ?: return // skipped run: no glyphs, no bar either
        val run = model.display.runs[i]
        TextRunPainter.draw(canvas, model.display.text, run, font, palette.color(run.color), d, model.widePlan(i, font))
    }

    /** Text only, in an explicit colour, never a decoration bar (shimmer, ticket 122). */
    fun drawRunText(canvas: Canvas, model: RowModel, i: Int, d: Float, fonts: StyleFonts, color: Int) {
        val font = model.entryFor(i, fonts) ?: return
        TextRunPainter.drawGlyphs(canvas, model.display.text, model.display.runs[i], font, color, d, model.widePlan(i, font))
    }

    /** px offset from `run.x * d` to UTF-16 index [at] (clamped into the run); 0 for a skipped run. */
    fun advanceTo(model: RowModel, i: Int, at: Int, fonts: StyleFonts): Float {
        val font = model.entryFor(i, fonts) ?: return 0f
        return TextRunPainter.offset(model.display.text, model.display.runs[i], font, at, model.widePlan(i, font))
    }
}
