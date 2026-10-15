package sh.roboco.android.transcript

import android.graphics.Canvas
import android.util.Log
import sh.roboco.android.core.FontChain
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
 * so theme and density changes only repaint. The one lazily filled cache
 * is [widePlan]'s per-run double-width plan: a plain [IntArray] derived
 * only from the text and the style's face, but filled on the drawing
 * (main) thread only — ticket 117's prefetch must not touch it.
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

    /** Ticket 63's per-run double-width plan cache; see [widePlan]. */
    private val widePlans = arrayOfNulls<IntArray>(display.runs.size)

    /** iOS `fadedRun.contains(i)`: the run paints only through its fade. */
    fun isFaded(run: Int): Boolean = faded[run]

    /**
     * iOS `RowModel.swift:28` guard: the run's UTF-16 range inside the text
     * and a known style — else the run is skipped entirely (a nil CTLine on
     * iOS draws no text *and* no bar). Shared by this class's own draw loop
     * and the [RunPainter] facade so both guards agree. The long addition
     * avoids UInt overflow on a malformed `start`/`len` pair.
     */
    internal fun entryFor(i: Int, fonts: StyleFonts): StyleFonts.Entry? {
        val r = display.runs[i]
        if (r.start.toLong() + r.len.toLong() > display.text.length) return null
        return fonts[r.style]
    }

    /**
     * Double-width clusters of run i ([MonoWide.NONE] unless the style's
     * face is Mono and the run has some), computed once and cached: the
     * plan depends only on the text and the style's face, and a style id's
     * face never changes within one view, so the cache survives
     * [StyleFonts] rebuilds. Drawing (main) thread only — the cache array
     * is unsynchronised.
     */
    internal fun widePlan(i: Int, font: StyleFonts.Entry): IntArray {
        widePlans[i]?.let { return it }
        val r = display.runs[i]
        val s = r.start.toInt()
        val p = if (!FontChain.isMono(font.face)) MonoWide.NONE
            else MonoWide.plan(display.text, s, s + r.len.toInt())
        widePlans[i] = p
        return p
    }

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

        /**
         * Runs run [i] with its guards: skipped (no text, no bar) when the
         * run's UTF-16 range exceeds [RowDisplay.text] or when [fonts] holds
         * no style for it (logged inside [StyleFonts.get]) — iOS
         * `RowModel.swift:28`. Local to [draw] — the only [StyleFonts]
         * reference this class makes outside its parameter — and called
         * directly for plain runs and through [FadePainter]'s lambda for
         * faded runs, so bars fade and clip with their text. Both are on
         * the drawing thread.
         */
        fun drawRun(i: Int) {
            val run = display.runs[i]
            val font = entryFor(i, fonts) ?: return
            TextRunPainter.draw(canvas, display.text, run, font, palette.color(run.color), density, widePlan(i, font))
        }

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
                    drawRun(i)
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
                    val font = entryFor(i, fonts) ?: continue
                    val plan = widePlan(i, font)
                    val x = run.x * density
                    val left =
                        if (start < range.from) x + TextRunPainter.offset(display.text, run, font, range.from, plan)
                        else -CLIP_EXTENT
                    val right =
                        if (end > range.to) x + TextRunPainter.offset(display.text, run, font, range.to, plan)
                        else CLIP_EXTENT
                    val save = canvas.save()
                    canvas.clipRect(left, -CLIP_EXTENT, right, CLIP_EXTENT)
                    drawRun(i)
                    canvas.restoreToCount(save)
                } else {
                    drawRun(i)
                }
            }
        }

        // 3. Fades with their masked runs (never in a Range pass).
        if (pass !is Pass.Range) {
            for (g in fadesByLayer[layer]) {
                if (g.runs.isEmpty()) continue // a fade that claimed nothing draws nothing
                val fade = display.fades[g.fade]
                FadePainter.draw(canvas, fade, density) {
                    for (i in g.runs) drawRun(i)
                }
            }
        }
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
