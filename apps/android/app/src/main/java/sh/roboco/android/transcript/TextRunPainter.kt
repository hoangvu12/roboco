package sh.roboco.android.transcript

import android.graphics.Canvas
import uniffi.roboco_core.TextRun

/**
 * Draws one [TextRun] of a [uniffi.roboco_core.RowDisplay]. This file
 * fixes the seam in ticket 62; ticket 63 finishes the body (Rust-exact
 * positions, decorations, mono double-width CJK cells). The seed here
 * only proves the orchestration: `drawText(text, start, end, …)` straight
 * from the row string (never a substring) at the run's `(x·d, baseline·d)`.
 *
 * All drawing happens on one thread, so mutating [StyleFonts.Entry.paint]'s
 * colour here is safe; the scratch objects live in these painter objects,
 * not in [RowModel].
 */
internal object TextRunPainter {

    /**
     * Draws `text[run.start, run.start + run.len)` with its origin at
     * `(run.x * d, run.baseline * d)`. The caller ([RowModel]) guarantees
     * `run.start + run.len <= text.length`.
     */
    fun draw(canvas: Canvas, text: String, run: TextRun, font: StyleFonts.Entry, color: Int, d: Float) {
        val start = run.start.toInt()
        font.paint.color = color
        canvas.drawText(text, start, start + run.len.toInt(), run.x * d, run.baseline * d, font.paint)
    }

    /**
     * Run-local px advance up to UTF-16 index [at] (clamped into the run):
     * the [RowModel.Pass.Range] split-clip edge. Must agree with [draw] —
     * the same paint, the same run bounds, the same text — or the clip and
     * the glyphs drift apart.
     */
    fun offset(text: String, run: TextRun, font: StyleFonts.Entry, at: Int): Float {
        val start = run.start.toInt()
        val end = start + run.len.toInt()
        return font.paint.getRunAdvance(text, start, end, start, end, false, at.coerceIn(start, end))
    }
}
