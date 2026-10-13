package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.roundHalfAway
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A dp rectangle — one frame of the row's hand layout. All values are dp
 * measured from the row's top-left corner; [SessionRow] converts to px at
 * the current density.
 */
@Immutable
data class RectDp(val x: Float, val y: Float, val w: Float, val h: Float)

/** Every frame the title line (and ticket 38's meta slot) places into. */
@Immutable
data class SessionRowFrames(
    /** `R(62k)` — the row's fixed height. */
    val height: Float,
    /** The 20×20 harness mark. */
    val mark: RectDp,
    /** The fading title. */
    val title: RectDp,
    /** The corner word / time label, right-aligned. */
    val word: RectDp,
    /** The 12×12 status glyph; null when no glyph shows. */
    val glyph: RectDp?,
    /** Ticket 38's meta line: reserved, empty in this ticket. */
    val metaSlot: RectDp,
    /** The background rect: the row bounds inset 8 left/right, 1 top/bottom. */
    val highlight: RectDp,
)

/**
 * The port of `SessionCell.layoutSubviews`'s title line
 * (`SessionCell.swift:157-182`) as a pure function, so the geometry is
 * testable without a device.
 *
 * Only the y positions, heights and the title font size scale with
 * `factor` (ticket 03's `TypeScale.factor`); the margins (20), mark side
 * (20), gap (14), glyph (12), gaps (5, 10), the 140 dp cap and the
 * background insets are **not** scaled. Rounding is [roundHalfAway]
 * (Swift `.rounded()`, half away from zero) — never `kotlin.math.round`
 * (half to even).
 *
 * [wordWidth] is the corner text's single-line width in unrounded dp
 * (the iOS `sizeThatFits`), capped at [WORD_MAX_WIDTH]; [tw] is its ceil,
 * as Compose already ceils to whole px and iOS does in dp.
 */
object SessionRowGeometry {
    /** `left`/`right` (SessionCell.swift:158-159) — not scaled. */
    const val MARGIN = 20f

    /** `markSide` (SessionCell.swift:193-195) — not scaled. */
    const val MARK_SIDE = 20f

    /** The mark-to-title gap (`textX − left − mark`, :174). */
    const val MARK_GAP = 14f

    /** `textX` (SessionCell.swift:174): 20 + 20 + 14 = 54 — not scaled. */
    const val TEXT_X = MARGIN + MARK_SIDE + MARK_GAP

    /** The status glyph's side (a 12×12 view, `StatusGlyph.swift:14-49`). */
    const val GLYPH_SIDE = 12f

    /** The glyph-to-word gap (`time.frame.minX − 5 − 12`, :160). */
    const val GLYPH_GAP = 5f

    /** The title's trailing gap (`trailing = … − 10`, :161-164). */
    const val TITLE_GAP = 10f

    /** `sizeThatFits(CGSize(width: 140, height: 40))`'s cap (a safety bound). */
    const val WORD_MAX_WIDTH = 140f

    fun frames(
        width: Float,
        factor: Float,
        titleFace: Face,
        wordWidth: Float,
        hasGlyph: Boolean,
    ): SessionRowFrames {
        val r = ::roundHalfAway
        val titleY = r(10f * factor)
        val titleH = r(22f * factor)
        val metaY = r(35f * factor)
        val metaH = r(18f * factor)
        val height = r(62f * factor)
        val s = r(16.5f * factor) // title font size, dp
        // The mark centers on the title's x-height (font metrics, not the
        // line box): titles are mostly lowercase, and centering on the
        // capitals left the mark riding high (SessionCell.swift:167-170).
        val baseline = titleY + (titleH - GeistMetrics.LINE_HEIGHT * s) / 2f + GeistMetrics.ASCENT * s
        val markY = r(baseline - GeistMetrics.xHeight(titleFace.weight) * s / 2f - MARK_SIDE / 2f)
        val tw = ceil(min(wordWidth, WORD_MAX_WIDTH))
        val wordX = width - MARGIN - tw
        val glyph = if (hasGlyph) {
            RectDp(
                x = wordX - GLYPH_GAP - GLYPH_SIDE,
                y = titleY + titleH / 2f - GLYPH_SIDE / 2f,
                w = GLYPH_SIDE,
                h = GLYPH_SIDE,
            )
        } else {
            null
        }
        val trailing = (glyph?.x ?: wordX) - TITLE_GAP
        return SessionRowFrames(
            height = height,
            mark = RectDp(MARGIN, markY, MARK_SIDE, MARK_SIDE),
            title = RectDp(TEXT_X, titleY, max(0f, trailing - TEXT_X), titleH),
            word = RectDp(wordX, titleY, tw, titleH),
            glyph = glyph,
            metaSlot = RectDp(TEXT_X, metaY, max(0f, width - MARGIN - TEXT_X), metaH),
            highlight = RectDp(8f, 1f, max(0f, width - 16f), max(0f, height - 2f)),
        )
    }
}

/** The background fill's constants (`SessionCell.swift:82-83`). */
object SessionRowTokens {
    /** `bg.cornerRadius = 16`, continuous. */
    val CornerRadius = 16.dp

    /** `backgroundInsets` leading/trailing. */
    val HorizontalInset = 8.dp

    /** `backgroundInsets` top/bottom. */
    val VerticalInset = 1.dp
}
