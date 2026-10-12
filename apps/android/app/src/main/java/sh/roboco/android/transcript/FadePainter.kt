package sh.roboco.android.transcript

import android.graphics.Canvas
import uniffi.roboco_core.Fade

/**
 * Paints the runs an overflow fade masks, through the fade's alpha ramp.
 * This file fixes the seam in ticket 62; ticket 64 finishes the body
 * (`saveLayer` + `DST_OUT`, the trailing clip at the fade's right edge and
 * the bottom ramp 0 → 0.92). The seed draws the masked runs unmasked,
 * which keeps this ticket's draw-order tests and goldens meaningful.
 */
internal object FadePainter {

    /**
     * Paints [drawRuns] (the fade's masked runs, in assignment order —
     * one lambda per non-empty fade per draw) through the fade's ramp.
     * Layer coordinates in px, like every painter here.
     */
    fun draw(canvas: Canvas, fade: Fade, d: Float, drawRuns: () -> Unit) {
        // Seed: no ramp yet — ticket 64 replaces this body.
        drawRuns()
    }
}
