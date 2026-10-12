package sh.roboco.android.transcript

import android.graphics.Canvas
import android.util.Log
import sh.roboco.android.design.color
import uniffi.roboco_core.FadeEdge
import uniffi.roboco_core.RowDisplay

/**
 * One row's display list plus everything cheap to derive from it: the
 * layer grouping of runs and boxes, and which runs each overflow fade
 * masks (iOS `RowModel`, `ios:Transcript/RowModel.swift:7-60`). Built
 * once per (row, version, width) — construction is pure Kotlin over the
 * record, safe on any thread (ticket 117 builds these on the prefetch
 * thread) — and it holds no colours, fonts or [android.graphics.Paint]s,
 * so theme and density changes only repaint.
 *
 * Unlike iOS, Android pre-builds nothing per run at construction (no
 * CTLines): [draw] paints straight from [RowDisplay.text] through
 * [StyleFonts], so a style missing at build time cannot permanently
 * blank a run.
 */
class RowModel(val display: RowDisplay) {

    /** A fade (index into [RowDisplay.fades]) and the runs it masks, in assignment order. */
    class FadeGroup(val fade: Int, val runs: IntArray)

    /**
     * Which runs to paint — the streaming veil (ticket 118). The iOS cases
     * map exactly: `.settled(v)` = [Chrome] + [Range]`(0, v)` and
     * `.fresh(v)` = [Range]`(v, Int.MAX_VALUE)`; the only ordering
     * difference is that fades paint before settled text instead of
     * after, which is invisible because fade runs and plain runs are
     * disjoint.
     */
    sealed interface Pass {
        /** Boxes, every non-faded run, fades. */
        data object All : Pass

        /** Boxes and fades only; the text comes from [Range] passes. */
        data object Chrome : Pass

        /** Only text in `[from, to)` (UTF-16), clipped at glyph edges; no boxes, no fades. */
        data class Range(val from: Int, val to: Int) : Pass {
            init { require(from in 0..to) { "bad range [$from, $to)" } }
        }
    }

    /** Layer 0 is the row canvas; layer n is scroller n−1's content. */
    val layers: Int = display.scrollers.size + 1

    /** Run indices per layer, ascending (run n of scroller s is in layer s+1). */
    val runsByLayer: Array<IntArray>

    /** Box indices per layer, ascending. */
    val boxesByLayer: Array<IntArray>

    /** Per layer, in display order: each fade and the runs it masks. */
    val fadesByLayer: Array<Array<FadeGroup>>

    private val faded = BooleanArray(display.runs.size)

    /** iOS `fadedRun.contains(i)`: the run paints only through its fade. */
    fun isFaded(run: Int): Boolean = faded[run]

    init {
        // A scroller index >= display.scrollers.size is malformed (Rust
        // never emits it; iOS would crash). The primitive is dropped from
        // every layer and the model logs once.
        var malformedScroller = false

        fun layerOf(s: UInt?): Int {
            val layer = if (s == null) 0 else s.toInt() + 1
            if (layer >= layers) malformedScroller = true
            return layer
        }

        runsByLayer = group(display.runs.size) { layerOf(display.runs[it].scroller) }
        boxesByLayer = group(display.boxes.size) { layerOf(display.boxes[it].scroller) }

        // Fade assignment, iOS RowModel.swift:43-54. Fades are visited in
        // display order, runs in index order within the fade's own layer;
        // a run is masked by at most one fade — the first that claims it.
        // The vertical test is open below and closed above with a half-unit
        // slack; Trailing fades also need the run to start left of the
        // fade's right edge, Bottom fades take any x. A fade that claims
        // no runs is still recorded (it draws nothing).
        val groups = Array(layers) { ArrayList<FadeGroup>() }
        for (fi in display.fades.indices) {
            val f = display.fades[fi]
            val layer = layerOf(f.scroller)
            if (layer >= layers) continue // malformed: dropped
            val candidates = runsByLayer[layer]
            val masked = IntArray(candidates.size)
            var n = 0
            for (i in candidates) {
                if (faded[i]) continue
                val r = display.runs[i]
                if (!(r.baseline > f.y && r.baseline <= f.y + f.h + 0.5f)) continue
                if (f.edge != FadeEdge.BOTTOM && r.x >= f.x + f.w) continue
                masked[n++] = i
            }
            for (k in 0 until n) faded[masked[k]] = true
            groups[layer].add(FadeGroup(fi, masked.copyOf(n)))
        }
        fadesByLayer = Array(layers) { groups[it].toTypedArray() }

        if (malformedScroller) {
            Log.w(TAG, "row ${display.key}: dropping primitives with scroller index >= ${display.scrollers.size}")
        }
    }

    /**
     * Paints one layer (0 = the row's top-left, n = scroller n−1's content
     * top-left — the host applies the scroller offset) onto a plain [Canvas]
     * in px, in the fixed order boxes → non-faded runs → fades (each fade
     * then paints its own masked runs).
     *
     * Drawing (main) thread only: the first statement syncs [fonts] to
     * [density] (one float compare when unchanged, so hosts pass no other
     * density plumbing) and the element painters mutate shared scratch.
     * [palette] is read per call — theme switches only repaint, the model
     * is never rebuilt. [layer] out of range is a no-op, not a crash.
     */
    fun draw(
        layer: Int,
        canvas: Canvas,
        density: Float,
        palette: IntArray,
        fonts: StyleFonts,
        pass: Pass = Pass.All,
    ) {
        fonts.useDensity(density)
        if (layer !in 0 until layers) return

        // 1. Boxes (never in a Range pass: Range is text-only).
        if (pass !is Pass.Range) {
            for (i in boxesByLayer[layer]) {
                val box = display.boxes[i]
                BoxPainter.draw(canvas, box, palette.color(box.color), density)
            }
        }

        // 2. Non-faded runs (never in a Chrome pass: text comes from Range).
        if (pass !is Pass.Chrome) {
            val range = if (pass is Pass.Range) pass else null
            for (i in runsByLayer[layer]) {
                if (faded[i]) continue // faded runs paint only through their fade
                if (range == null) {
                    drawRun(i, canvas, density, palette, fonts)
                    continue
                }
                val run = display.runs[i]
                val start = run.start.toInt()
                val end = start + run.len.toInt()
                if (end > display.text.length) continue // nil line on iOS: never drawn
                if (end <= range.from || start >= range.to) continue // no intersection
                if (start < range.from || end > range.to) {
                    // Split a straddling run at the glyph edge, as iOS clips
                    // at CTLineGetOffsetForStringIndex; the span covers any
                    // row (a full-height clip, ±CLIP_EXTENT).
                    val font = fonts[run.style] ?: continue
                    val x = run.x * density
                    val left =
                        if (start < range.from) x + TextRunPainter.offset(display.text, run, font, range.from)
                        else -CLIP_EXTENT
                    val right =
                        if (end > range.to) x + TextRunPainter.offset(display.text, run, font, range.to)
                        else CLIP_EXTENT
                    val save = canvas.save()
                    canvas.clipRect(left, -CLIP_EXTENT, right, CLIP_EXTENT)
                    drawRun(i, canvas, density, palette, fonts)
                    canvas.restoreToCount(save)
                } else {
                    drawRun(i, canvas, density, palette, fonts)
                }
            }
        }

        // 3. Fades with their masked runs (never in a Range pass).
        if (pass !is Pass.Range) {
            for (g in fadesByLayer[layer]) {
                if (g.runs.isEmpty()) continue // a fade that claimed nothing draws nothing
                val fade = display.fades[g.fade]
                FadePainter.draw(canvas, fade, density) {
                    for (i in g.runs) drawRun(i, canvas, density, palette, fonts)
                }
            }
        }
    }

    /**
     * Draws run [i] with its guards: skipped when the run's UTF-16 range
     * exceeds [RowDisplay.text] (iOS `RowModel.swift:28`) or when [fonts]
     * holds no style for it (logged inside [StyleFonts.get]). Called by
     * [draw] for plain runs and through [FadePainter]'s lambda for faded
     * runs — the only [StyleFonts] references this class makes, both on
     * the drawing thread.
     */
    private fun drawRun(i: Int, canvas: Canvas, density: Float, palette: IntArray, fonts: StyleFonts) {
        val run = display.runs[i]
        val start = run.start.toInt()
        if (start + run.len.toInt() > display.text.length) return
        val font = fonts[run.style] ?: return
        TextRunPainter.draw(canvas, display.text, run, font, palette.color(run.color), density)
    }

    /** Two counting passes into per-layer [IntArray]s, ascending index order. */
    private fun group(n: Int, layerAt: (Int) -> Int): Array<IntArray> {
        val counts = IntArray(layers)
        for (i in 0 until n) layerAt(i).let { if (it in 0 until layers) counts[it]++ }
        val out = Array(layers) { IntArray(counts[it]) }
        val fill = IntArray(layers)
        for (i in 0 until n) layerAt(i).let { if (it in 0 until layers) out[it][fill[it]++] = i }
        return out
    }

    private companion object {
        const val TAG = "RobocoTranscript"

        /** Half-extent of a Range split: ±100 000 px spans any row (iOS uses ±10 000 pt). */
        const val CLIP_EXTENT = 100_000f
    }
}
