package sh.roboco.android.transcript

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import uniffi.roboco_core.BoxPrim

/**
 * Draws one [BoxPrim]. This file fixes the seam in ticket 62; ticket 64
 * finishes the body (iOS-exact fill and the 1-device-pixel inside
 * hairline, which takes the row's density). The seed paints both styles
 * as a filled rounded rect, which is enough for the orchestration goldens.
 *
 * The scratch [Paint] and [RectF] live here, not in [RowModel]: all
 * drawing happens on one thread.
 */
internal object BoxPainter {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /**
     * Fills `box`'s rect in px (`(x, y, x + w, y + h) · d`) with [color].
     * The hairline distinction is ticket 64's body; the seed draws every
     * style filled.
     */
    fun draw(canvas: Canvas, box: BoxPrim, color: Int, d: Float) {
        rect.set(box.x * d, box.y * d, (box.x + box.w) * d, (box.y + box.h) * d)
        fill.color = color
        // Seed: hairlines drawn filled; ticket 64 replaces this body.
        canvas.drawRoundRect(rect, box.radius * d, box.radius * d, fill)
    }
}
