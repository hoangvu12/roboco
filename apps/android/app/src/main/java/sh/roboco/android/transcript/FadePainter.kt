package sh.roboco.android.transcript

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import uniffi.roboco_core.Fade
import uniffi.roboco_core.FadeEdge

/**
 * Paints the runs an overflow fade masks, through the fade's alpha ramp —
 * the iOS fade pass (`ios:Transcript/RowModel.swift:62-64, :106-137`,
 * ticket 64). The masked runs ([drawRuns], one lambda per non-empty fade
 * per draw, in assignment order — ticket 62 hands them over with their
 * normal colours and decorations) are painted into an offscreen layer and
 * **erased** along a linear ramp (`DST_OUT`), so the text itself fades and
 * works over any background: bubble fill, chip fill, wallpaper.
 *
 * - [FadeEdge.TRAILING]: the ramp runs left → right over `[f.x, f.x+f.w]`
 *   from alpha 0 to 1, and everything right of `f.x + f.w` is clipped
 *   before the layer starts (the outer clip must precede `saveLayer`, or
 *   the runs would not be cut at the fade's right edge).
 * - [FadeEdge.BOTTOM]: the ramp runs top → bottom over `[f.y, f.y+f.h]`
 *   from alpha 0 to 0.92 — folded blocks never fully erase (the last line
 *   keeps ≈ 8 % opacity), and the erase continues below the rect for the
 *   descenders and baseline slack (`CLAMP` stands in for iOS's
 *   `.drawsAfterEndLocation`).
 *
 * The eraser **must** stay inside the `saveLayer`: `DST_OUT` drawn straight
 * onto the row canvas would punch holes through everything below (the
 * bubble fill, chip fill and row background). Drawing (main) thread only —
 * the eraser [Paint] is shared scratch.
 */
internal object FadePainter {

    /** The iOS `40` constants, in points: the clip and erase slack around the fade rect. */
    const val PAD_PT = 40f

    /** round(0.92 × 255): the bottom ramp's end alpha, where the text keeps ≈ 8 %. */
    const val BOTTOM_END_ALPHA = 0xEB

    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = BlendMode.DST_OUT
        isDither = false
    }

    /**
     * Layer coordinates in px, like every painter here. One `LinearGradient`
     * per fade per draw: a row has at most a few fades and is only redrawn
     * when its model or pass changes, and never sharing a shader across
     * fades keeps the local-matrix mutation trap out of the code.
     */
    fun draw(canvas: Canvas, fade: Fade, d: Float, drawRuns: () -> Unit) {
        val x0 = fade.x * d
        val x1 = (fade.x + fade.w) * d
        val y0 = fade.y * d
        val y1 = (fade.y + fade.h) * d
        val pad = PAD_PT * d
        val save = canvas.save()
        if (fade.edge == FadeEdge.TRAILING) {
            // Cut the runs at the fade's right edge before the layer: the
            // clip intersects the layer bounds, and runs left of f.x stay
            // untouched by the eraser.
            canvas.clipRect(-1e5f, y0 - pad, x1, y1 + pad)
        }
        // Bounded to ±2·pad around the fade rect (intersected with the clip):
        // masked baselines lie within (f.y, f.y + f.h + 0.5], so every
        // masked glyph is inside.
        canvas.saveLayer(-1e5f, y0 - 2 * pad, 1e5f, y1 + 2 * pad, null)
        drawRuns()
        when (fade.edge) {
            FadeEdge.TRAILING -> {
                eraser.shader = LinearGradient(x0, 0f, x1, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRect(x0, y0 - pad, x1, y1 + pad, eraser)
            }
            FadeEdge.BOTTOM -> {
                eraser.shader = LinearGradient(0f, y0, 0f, y1, 0x00000000, BOTTOM_END_ALPHA shl 24, Shader.TileMode.CLAMP)
                canvas.drawRect(x0 - pad, y0, x1 + pad, y1 + pad, eraser)
            }
        }
        eraser.shader = null
        // Not two restore() calls: trailing and bottom save at different
        // depths (the trailing outer clip).
        canvas.restoreToCount(save)
    }
}
