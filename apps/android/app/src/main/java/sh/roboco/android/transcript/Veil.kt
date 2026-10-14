package sh.roboco.android.transcript

import android.graphics.Canvas
import android.graphics.Paint
import sh.roboco.android.design.Motion

/**
 * Streaming veil for one realized row (iOS `RowView.swift:111-141`): each
 * inserted chunk of text fades 0 → 1 over [Motion.VeilMs] ms easeOut on its
 * own clock, so tokens arriving a few frames apart overlap smoothly instead
 * of restarting one overlay (iOS snaps the previous token's half-faded words
 * to opaque on every token; here each chunk keeps its progress, so nothing
 * already visible ever flickers back). Chunks are found by the prefix +
 * suffix diff ([VeilDiff]): Rust appends a list item's bullet *after* the
 * item text (mob markdown.rs:561-567), so streamed words land before the
 * row's tail, which iOS's `hasPrefix` test would miss.
 *
 * One instance per [RowHost] (per row key): the first model it ever sees
 * only becomes the diff baseline, so a re-realized row shows its text
 * fully. A chunk's clock starts at the **first frame tick after the
 * composition that recorded it** ([tick]), matching UIKit's next-commit
 * animation start and making the timing deterministic under Compose's
 * test clock.
 *
 * Layer 0 only; scroller layers, boxes, overflow fades, the runs they mask
 * and widgets are never veiled (iOS paints them on the settled canvas and
 * rebuilds widgets per configure). The fade goes through a bounded
 * [Canvas.saveLayerAlpha] per chunk, never the run paints' own colour
 * alpha, so alpha-carrying roles and decorations stay right.
 *
 * Main thread only (grow from composition, tick from the frame clock, draw
 * from drawBehind). Not thread-safe and never shared.
 */
internal class Veil(private val durationNanos: Long = Motion.VeilMs * 1_000_000L) {

    /**
     * One inserted UTF-16 range `[from, to)` of the row's text on its own
     * clock: [started] is [UNSTARTED] until the first frame tick after the
     * chunk was recorded (it paints at alpha 0 until then), then the frame
     * time in nanos.
     */
    internal class Chunk(var from: Int, var to: Int, var started: Long)

    private var last: RowModel? = null
    private val list = ArrayList<Chunk>(4)

    /** Reusable [Paint.FontMetrics] for the chunk layer bounds; main thread only. */
    private val fm = Paint.FontMetrics()

    /** Live chunks, sorted by `from`, disjoint (tests read this). */
    val chunks: List<Chunk> get() = list

    /** Whether any chunk is still fading (the host frame loop's condition). */
    val hasChunks: Boolean get() = list.isNotEmpty()

    /**
     * Records the chunk [model] inserted relative to the previous model,
     * which only becomes the baseline when it exists, carries the same
     * display key, and [VeilDiff] sees a pure insertion. Anything else —
     * a re-flow, a rewrite, a shrink, an equal-length rebuild, a key
     * change — drops every chunk and the row paints fully opaque from
     * this model on (iOS `grew == false` → `.all`). Idempotent per model
     * instance: recomposition calls this with the same instance and grows
     * nothing. `animate = false` (reduce motion, or the defensive call
     * from [draw]) records nothing and drops what it has.
     */
    fun grow(model: RowModel, animate: Boolean) {
        val prev = last
        last = model
        if (prev == null || prev === model) return
        if (!animate || prev.display.key != model.display.key) {
            list.clear()
            return
        }
        val old = prev.display.text
        val new = model.display.text
        val p = VeilDiff.insertionAt(old, new)
        if (p < 0) {
            list.clear()
            return
        }
        val delta = new.length - old.length
        // Shift / split the existing chunks around the insertion point: a
        // chunk at or after it moves by delta; one strictly containing it
        // splits into two halves on the old clock (the fix over #609, whose
        // shift-only rule leaves a containing chunk covering the wrong
        // characters).
        var i = 0
        while (i < list.size) {
            val c = list[i]
            when {
                c.from >= p -> {
                    c.from += delta
                    c.to += delta
                }
                c.to > p -> { // from < p < to: split
                    list.add(i + 1, Chunk(p + delta, c.to + delta, c.started))
                    c.to = p
                    i++ // skip the half just added
                }
            }
            i++
        }
        list.add(Chunk(p, p + delta, UNSTARTED))
        list.sortBy { it.from }
    }

    /**
     * Stamps unstarted chunks with [frameNanos] (their clock starts now),
     * drops the expired ones (`frameNanos − started ≥ duration`). Returns
     * [hasChunks]: the host's frame loop's exit signal.
     */
    fun tick(frameNanos: Long): Boolean {
        for (i in list.indices) if (list[i].started == UNSTARTED) list[i].started = frameNanos
        list.removeAll { frameNanos - it.started >= durationNanos }
        return hasChunks
    }

    /** 0..255 for chunk [c] at frame time [now]; an unstarted chunk is 0. */
    fun alphaInt(c: Chunk, now: Long): Int {
        if (c.started == UNSTARTED) return 0
        val t = ((now - c.started).toFloat() / durationNanos).coerceIn(0f, 1f)
        val a = Motion.EaseOut.transform(t)
        return (a * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Paints layer 0 of [model] with the live chunks veiled (the spec's
     * "Draw composition"): [RowModel.Pass.Chrome] (boxes, overflow fades
     * and their runs — never veiled), the settled text as [RowModel.Pass.Range]
     * passes over the gaps between the live chunks, then each live chunk as
     * a [RowModel.Pass.Range] pass inside its own bounded `saveLayerAlpha`
     * at the chunk's alpha (0 skips it, 255 draws it without a layer). No
     * live chunk — or a model that is not the last grown one, dropped
     * defensively rather than veiling the wrong text — falls back to the
     * plain [RowModel.Pass.All] draw, exactly ticket 65's call. Read in the
     * draw phase; every pass goes through [RowModel.draw], which syncs the
     * fonts' density first, so the chunk bounds below can read their paints.
     */
    fun draw(canvas: Canvas, model: RowModel, density: Float, palette: IntArray, fonts: StyleFonts, now: Long) {
        if (model !== last) grow(model, animate = false)

        // Live: unstarted (alpha 0, waiting for its clock) or not yet
        // 220 ms old. Expired-but-unticked chunks (the loop's last frame)
        // are settled text, drawn opaque.
        var anyLive = false
        for (i in list.indices) {
            if (isLive(list[i], now)) {
                anyLive = true
                break
            }
        }
        if (!anyLive) {
            model.draw(0, canvas, density, palette, fonts, RowModel.Pass.All)
            return
        }

        // 1. Boxes and fades (Chrome runs `fonts.useDensity`, so the layer
        //    bounds below can read the runs' paints).
        model.draw(0, canvas, density, palette, fonts, RowModel.Pass.Chrome)

        // 2. Settled text: the gaps before each live chunk, then the tail.
        var cursor = 0
        for (i in list.indices) {
            val c = list[i]
            if (!isLive(c, now)) continue
            if (c.from > cursor) model.draw(0, canvas, density, palette, fonts, RowModel.Pass.Range(cursor, c.from))
            if (c.to > cursor) cursor = c.to
        }
        model.draw(0, canvas, density, palette, fonts, RowModel.Pass.Range(cursor, Int.MAX_VALUE))

        // 3. Each live chunk at its own alpha, in its own bounded layer.
        val runs = model.runsByLayer[0]
        for (i in list.indices) {
            val c = list[i]
            if (!isLive(c, now)) continue
            val a = alphaInt(c, now)
            if (a == 0) continue // unstarted or the first eased frame: nothing visible
            if (a == 255) { // eased to full but not expired: plain, no layer
                model.draw(0, canvas, density, palette, fonts, RowModel.Pass.Range(c.from, c.to))
                continue
            }
            // The chunk's layer bounds (px, row-local): the union of the
            // layer-0 runs intersecting it, padded 4 dp horizontally and
            // 2 dp past the font metrics vertically (glyph overhang,
            // decoration bars). Faded runs and unknown styles contribute
            // nothing — their text is never veiled — and no intersecting
            // run means the chunk lies only in scroller text or chrome:
            // skip it. Four local floats, never a RectF.
            var have = false
            var left = 0f
            var top = 0f
            var right = 0f
            var bottom = 0f
            for (j in runs.indices) {
                val r = runs[j]
                if (model.isFaded(r)) continue
                val run = model.display.runs[r]
                val start = run.start.toInt()
                val end = start + run.len.toInt()
                if (!(end > c.from && start < c.to)) continue
                val entry = fonts[run.style] ?: continue
                entry.paint.getFontMetrics(fm)
                val l = run.x * density - 4f * density
                val rEdge = (run.x + run.width.coerceAtLeast(0f)) * density + 4f * density
                val t = run.baseline * density + fm.top - 2f * density
                val b = run.baseline * density + fm.bottom + 2f * density
                if (!have) {
                    have = true
                    left = l
                    top = t
                    right = rEdge
                    bottom = b
                } else {
                    if (l < left) left = l
                    if (t < top) top = t
                    if (rEdge > right) right = rEdge
                    if (b > bottom) bottom = b
                }
            }
            if (!have) continue
            val save = canvas.saveLayerAlpha(left, top, right, bottom, a)
            model.draw(0, canvas, density, palette, fonts, RowModel.Pass.Range(c.from, c.to))
            canvas.restoreToCount(save)
        }
    }

    companion object {
        /** [Chunk.started] while the chunk waits for its first frame tick. */
        const val UNSTARTED = Long.MIN_VALUE
    }

    /**
     * A chunk is live while it is unstarted (it paints at alpha 0 until
     * the frame loop stamps its clock) or less than [durationNanos] old
     * at [now]; an expired chunk that [tick] has not dropped yet is
     * settled text. Checked before `now - started` so an unstarted
     * chunk never overflows the subtraction.
     */
    private fun isLive(c: Chunk, now: Long): Boolean =
        c.started == UNSTARTED || now - c.started < durationNanos
}
