package sh.roboco.android.transcript

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import uniffi.roboco_core.BoxPrim
import uniffi.roboco_core.BoxStyle

/**
 * Draws one [BoxPrim] — the iOS box pass (`ios:Transcript/RowModel.swift:148-170`,
 * ticket 64): a plain circular-corner rounded rect for [BoxStyle.FILL] and a
 * 1-device-pixel stroke on the rect's inside edge for [BoxStyle.HAIRLINE]
 * (`rect.insetBy(dx: hairline/2)` with `radius − hairline/2`, radius floored
 * at 0). Circular corners only — the continuous shape of ticket 01 is for
 * chrome, not the transcript.
 *
 * Boxes keep the colour's own alpha (several roles are translucent) and
 * paint in list order, before any text of their layer; the caller
 * ([RowModel.draw]) resolves the role against the appearance and skips the
 * whole pass in [RowModel.Pass.Range].
 *
 * The scratch [Paint]s and [RectF] live in this object, so it is drawing
 * (main) thread only — never touched from [RowModel] construction.
 */
internal object BoxPainter {

    /** A hairline is exactly 1 physical pixel, independent of [android.util.DisplayMetrics.density]. */
    const val HAIRLINE_PX = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = false
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = HAIRLINE_PX
        isDither = false
    }

    private val rect = RectF()

    /**
     * Paints `box` at `(x·d, y·d, (x+w)·d, (y+h)·d)` in [color] — the
     * ARGB the caller resolved from the row's appearance (ticket 02's
     * `TranscriptPalette(dark).color(role)`); this object never looks at
     * roles itself. Skia shrinks oversize corner radii proportionally, so
     * no clamp of our own (the core keeps radii ≤ half the rect).
     */
    fun draw(canvas: Canvas, box: BoxPrim, color: Int, d: Float) {
        rect.set(box.x * d, box.y * d, (box.x + box.w) * d, (box.y + box.h) * d)
        val r = box.radius * d
        when (box.style) {
            BoxStyle.FILL -> {
                fill.color = color
                canvas.drawRoundRect(rect, r, r, fill)
            }
            BoxStyle.HAIRLINE -> {
                stroke.color = color
                rect.inset(HAIRLINE_PX / 2, HAIRLINE_PX / 2)
                val rr = maxOf(0f, r - HAIRLINE_PX / 2)
                canvas.drawRoundRect(rect, rr, rr, stroke)
            }
        }
    }
}
