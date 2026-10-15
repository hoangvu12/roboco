package sh.roboco.android.transcript

import android.graphics.Canvas
import android.graphics.Paint
import sh.roboco.android.core.FontChain
import uniffi.roboco_core.Decoration
import uniffi.roboco_core.TextRun

/**
 * Draws one [TextRun] of a [uniffi.roboco_core.RowDisplay] — iOS
 * `drawRun` (`ios:Transcript/RowModel.swift:66-81`) on the Android
 * canvas: the glyphs straight from the row string (never a slice
 * allocation) at
 * the Rust-placed origin `(run.x * d, run.baseline * d)`, then the
 * decoration bar in the same colour inside the same call, so overlay
 * fades (64) erase it and veil clips (118) split it together with its
 * text — never a separate pass. The bar is `run.width` wide (Rust's
 * measured advance, the value selection and hit rects use, never a
 * platform measurement) and exactly 1 pt = `d` px thick: iOS's
 * `max(hairline, 1)` is always 1 pt, not #609's ½ dp and not #621's
 * pt-space 1 px.
 *
 * [plan] (from [RowModel.widePlan]) carries the run's double-width
 * clusters for the mono path — each drawn centred in two `"0"` cells —
 * and is [MonoWide.NONE] for everything else, including every Sans run:
 * CJK and emoji in Sans come through the fallback chain at natural
 * width, exactly as the measurer measured them.
 *
 * All drawing happens on one thread, so mutating [StyleFonts.Entry.paint]'s
 * colour here is safe; the scratch [bar] lives in this object, as ticket
 * 62 prescribed, not in [RowModel].
 */
internal object TextRunPainter {

    /** The decoration bar's fill; its colour is set per run, alpha included. */
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Glyphs plus the decoration bar in the run's colour. The caller
     * ([RowModel] or [RunPainter]) guarantees `run.start + run.len <=
     * text.length` and a known style id — iOS's `RowModel.swift:28` guard:
     * a nil CTLine skips the text *and* the bar. The paint keeps the
     * typeface, size and feature settings [StyleFonts] built; only its
     * colour (alpha included, so alpha-carrying roles such as TEXT_SOFT
     * paint correctly) is written here.
     */
    fun draw(canvas: Canvas, text: String, run: TextRun, font: StyleFonts.Entry, color: Int, d: Float,
             plan: IntArray = MonoWide.NONE) {
        drawGlyphs(canvas, text, run, font, color, d, plan)
        if (run.decoration != Decoration.NONE) {
            val top = (run.baseline + if (run.decoration == Decoration.UNDERLINE) 2f else -5f) * d
            bar.color = color
            canvas.drawRect(run.x * d, top, (run.x + run.width) * d, top + d, bar)
        }
    }

    /**
     * Glyphs only, in an explicit colour, never a bar — the shimmer
     * re-draw entry point (iOS `drawRuns(in:color:…)`,
     * RowModel.swift:83-104; ticket 122). An empty run draws nothing; a
     * decorated one still gets its bar from [draw], not from here.
     */
    fun drawGlyphs(canvas: Canvas, text: String, run: TextRun, font: StyleFonts.Entry, color: Int, d: Float,
                   plan: IntArray = MonoWide.NONE) {
        val paint = font.paint
        paint.color = color
        val start = run.start.toInt()
        val end = start + run.len.toInt()
        if (start == end) return
        val x0 = run.x * d
        val y = run.baseline * d
        if (plan.isEmpty()) {
            canvas.drawText(text, start, end, x0, y, paint)
            return
        }
        val cell2 = 2f * FontChain.cellWidth(paint)
        var x = x0
        var seg = start
        var k = 0
        while (k < plan.size) {
            val cs = plan[k]
            val ce = plan[k + 1]
            if (cs > seg) {
                canvas.drawText(text, seg, cs, x, y, paint)
                x += FontChain.advance(paint, text, seg, cs)
            }
            val adv = FontChain.advance(paint, text, cs, ce)
            canvas.drawText(text, cs, ce, x + (cell2 - adv) / 2f, y, paint)
            x += cell2
            seg = ce
            k += 2
        }
        if (seg < end) canvas.drawText(text, seg, end, x, y, paint)
    }

    /**
     * Run-local px advance up to UTF-16 index [at] (clamped into the run) —
     * the [RowModel.Pass.Range] split-clip edge. Must agree with
     * [drawGlyphs] — the same paint, the same run bounds, the same text —
     * or the clip and the glyphs drift apart.
     *
     * Empty plan: 62's `getRunAdvance` formula, unchanged. With a plan: a
     * narrow stretch contributes `FontChain.advance(seg, at)`; a double-width
     * cluster contributes its **leading** edge for every [at] at or inside
     * it (a partly fresh cluster goes wholly to the fresh side) and two
     * cells at its trailing edge.
     */
    fun offset(text: String, run: TextRun, font: StyleFonts.Entry, at: Int, plan: IntArray = MonoWide.NONE): Float {
        val paint = font.paint
        val start = run.start.toInt()
        val end = start + run.len.toInt()
        val a = at.coerceIn(start, end)
        if (plan.isEmpty()) return paint.getRunAdvance(text, start, end, start, end, false, a)
        val cell2 = 2f * FontChain.cellWidth(paint)
        var x = 0f
        var seg = start
        var k = 0
        while (k < plan.size) {
            val cs = plan[k]
            val ce = plan[k + 1]
            if (a <= cs) return x + FontChain.advance(paint, text, seg, a)
            if (a < ce) return x + FontChain.advance(paint, text, seg, cs)
            x += FontChain.advance(paint, text, seg, cs) + cell2
            seg = ce
            k += 2
        }
        return x + FontChain.advance(paint, text, seg, a)
    }
}
