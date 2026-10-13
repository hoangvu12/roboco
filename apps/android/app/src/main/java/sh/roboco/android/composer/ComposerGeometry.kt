package sh.roboco.android.composer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max

/**
 * The composer's dp constants (android-ui-parity #55), every one a UIKit
 * anchor from `ios:Composer/ComposerBar.swift`:
 *
 * - [Control] `:118` — the size of every control (attach, action height,
 *   chip strip height);
 * - [CapsuleRadius] `:91` — `Glass.surface(radius: 25)`; 25 at height 50 is
 *   an exact capsule (SmoothRoundedShape clamps to `min(w, h) / 2`);
 * - [CardRadius] `:327` — the card's continuous 26;
 * - [ToolbarHeight] `:324` — the card's toolbar band;
 * - [CapsuleEdge] `:252-255` — attach leading, action trailing and both
 *   bottom gaps in the capsule;
 * - [CardEdge] `:261, :263` — the card's attach/action edges;
 * - [CapsuleTextGapAfterAttach] `:256` and [CapsuleTextGapBeforeAction]
 *   `:257` — the capsule's text gaps;
 * - [CardTextInset] `:265-266` — the card's full-width text inset;
 * - [ChipGap] `:240-241` — chips → attach/action gaps;
 * - [ThumbsInset] `:214-215` — the thumbnail strip's side inset;
 * - [PlaceholderTop] `:233` — the placeholder's offset from the text top.
 */
object ComposerMetrics {
    /** `ComposerBar.swift:118` — every control's size. */
    const val Control = 34f

    /** `ComposerBar.swift:91` — the capsule's glass radius. */
    const val CapsuleRadius = 25f

    /** `ComposerBar.swift:327` — the card's glass radius. */
    const val CardRadius = 26f

    /** `ComposerBar.swift:324` — the card's toolbar band height. */
    const val ToolbarHeight = 50f

    /** `ComposerBar.swift:252-255` — the capsule's control edge gaps. */
    const val CapsuleEdge = 8f

    /** `ComposerBar.swift:261, :263` — the card's control edge gaps. */
    const val CardEdge = 10f

    /** `ComposerBar.swift:256` — capsule text leading = 8 + 34 + 10 = 52. */
    const val CapsuleTextGapAfterAttach = 10f

    /** `ComposerBar.swift:257` — capsule text trailing = 8 before the action. */
    const val CapsuleTextGapBeforeAction = 8f

    /** `ComposerBar.swift:265-266` — the card's text inset, both sides. */
    const val CardTextInset = 16f

    /** `ComposerBar.swift:240-241` — the chip strip's gaps to attach/action. */
    const val ChipGap = 8f

    /** `ComposerBar.swift:214-215` — the thumbnail strip's side inset. */
    const val ThumbsInset = 12f

    /** `ComposerBar.swift:233` — placeholder top = text top + 14. */
    const val PlaceholderTop = 14f
}

/**
 * One morph frame of the composer surface (android-ui-parity #55): every
 * slot's rect, the toolbar band (a clip rect, not a slot), the surface
 * size, the lerped corner radius and the chip alpha — all **surface-local
 * px** except [cornerRadiusDp] and [chipAlpha].
 *
 * Pure data, produced by [ComposerGeometry.frames] as a pure function of
 * `(W, thumbsH, textH, aw, p)`; `ComposerGeometryTest` pins the ticket's
 * worked fixture. Leading/trailing follow the surface's own layout
 * direction; the `Layout` mirrors them with `placeRelative`.
 */
@Immutable
data class ComposerFrames(
    /** The surface's width/height (px): `thumbsH + textH + tbH`. */
    val width: Float,
    val height: Float,
    /** The thumbnail strip slot (`12, 0, W − 24, thumbsH`). */
    val thumbs: Rect,
    /** The text slot: lerped frame (px), the slot's target-width content is clipped to it. */
    val text: Rect,
    /** The placeholder's origin (the text's leading x, `thumbsH + 14`). */
    val placeholderOrigin: Offset,
    /** The attach `+` (34 × 34, 8 dp above the bottom edge in both states). */
    val attach: Rect,
    /** The action button (`aw` × 34, 8 dp above the bottom edge in both states). */
    val action: Rect,
    /** The toolbar band: full width, height `lerp(0, 50, p)` at the bottom. A clip rect, not a slot. */
    val toolbar: Rect,
    /** The chip strip slot: from `attach.x + 42` to `action.x − 8`, 34 high, centred on the band. */
    val chips: Rect,
    /** The lerped corner radius in **dp**: `lerp(25, 26, pc)`. */
    val cornerRadiusDp: Float,
    /** The chip strip's alpha: `pc`. */
    val chipAlpha: Float,
)

/**
 * The composer surface's pure geometry (android-ui-parity #55): the static
 * half of the capsule ⇄ card morph. Every quantity is the UIKit constraint
 * anchor from `ios:Composer/ComposerBar.swift:203-334`, lerped by one
 * progress `p` (0 = capsule, 1 = card, unclamped — the 0.86 spring
 * overshoots ≈ 0.5 %), exactly as UIKit interpolates every frame inside
 * one `UIView.animate(…usingSpringWithDamping: 0.86)` (`:333`).
 *
 * - **positions** use the raw `p` (UIKit extrapolates frames on overshoot);
 * - **algebraic quantities** clamp: `pc = p.coerceIn(0, 1)` (radius, alpha);
 * - `tbH` is clamped at ≥ 0 (a negative overshoot must not fold the band up).
 *
 * All inputs and outputs are px except [ComposerFrames.cornerRadiusDp] and
 * [ComposerFrames.chipAlpha]; [density] converts the dp constants. No
 * Compose runtime — only the plain `Rect`/`Offset` value classes, so the
 * worked-fixture test runs on the plain JVM.
 */
object ComposerGeometry {

    /**
     * Every frame of the morph for one measurement. `d = density`:
     *
     * ```
     * tb    = max(0, 50·d·p)                    toolbar band height
     * h     = thumbsH + textH + tb              the vertical chain (:271)
     * attach.x = lerp(8, 10, p)·d               :252 / :261
     * action.x = W − lerp(8, 10, p)·d − aw      :253 / :263
     * controlY = h − 8·d − 34·d                 8 above the bottom in BOTH
     *                                          states (the card centres the
     *                                          34 dp controls on the 50 dp
     *                                          toolbar: (50 − 34)/2 = 8)
     * textX = lerp(52, 16, p)·d                 :256 / :265 (52 = 8+34+10)
     * tr    = lerp(16·d + aw, 16·d, p)          :257 / :266
     * textW = max(0, W − textX − tr)
     * band  = (0, thumbsH + textH, W, … + tb)
     * chipX = attach.x + 42·d                   attach + 34 + 8 (:240-241)
     * chipW = max(0, action.x − 8·d − chipX)
     * chipY = band.top + tb/2 − 17·d            centred on the band
     * ```
     */
    fun frames(
        width: Float,
        thumbsHeight: Float,
        textHeight: Float,
        actionWidth: Float,
        progress: Float,
        density: Float,
    ): ComposerFrames {
        val d = density
        val p = progress
        val pc = p.coerceIn(0f, 1f)
        val m = ComposerMetrics

        // The toolbar band height, clamped at >= 0 (negative overshoot).
        val tb = max(0f, m.ToolbarHeight * d * p)

        // The vertical chain (:271): the bar's height is thumbs + text + tb.
        val h = thumbsHeight + textHeight + tb

        // The controls' x: 8 -> 10 from the edges. Their bottom gap is 8 in
        // BOTH states — lerp(8, 8, p) — so only x moves and they ride the
        // bottom edge as the card grows.
        val attachX = lerp(m.CapsuleEdge, m.CardEdge, p) * d
        val actionX = width - lerp(m.CapsuleEdge, m.CardEdge, p) * d - actionWidth
        val controlY = h - m.CapsuleEdge * d - m.Control * d

        // The text slot: leading 52 -> 16, trailing 16 + aw -> 16.
        val textX = lerp(capsuleTextLeading(d), m.CardTextInset * d, p)
        val textW = textWidthPx(width, actionWidth, p, d)

        val bandTop = thumbsHeight + textHeight
        val chipX = attachX + (m.Control + m.ChipGap) * d
        val chipW = max(0f, actionX - m.ChipGap * d - chipX)
        val chipY = bandTop + tb / 2f - m.Control * d / 2f

        return ComposerFrames(
            width = width,
            height = h,
            thumbs = Rect(
                left = m.ThumbsInset * d,
                top = 0f,
                right = m.ThumbsInset * d + max(0f, width - 2f * m.ThumbsInset * d),
                bottom = thumbsHeight,
            ),
            text = Rect(textX, thumbsHeight, textX + textW, thumbsHeight + textHeight),
            placeholderOrigin = Offset(textX, thumbsHeight + m.PlaceholderTop * d),
            attach = Rect(attachX, controlY, attachX + m.Control * d, controlY + m.Control * d),
            action = Rect(actionX, controlY, actionX + actionWidth, controlY + m.Control * d),
            toolbar = Rect(0f, bandTop, width, bandTop + tb),
            chips = Rect(chipX, chipY, chipX + chipW, chipY + m.Control * d),
            cornerRadiusDp = radiusDp(p),
            chipAlpha = pc,
        )
    }

    /**
     * The text slot's width at [progress] — the width the frame gives the
     * text at `p`. Shares [textWidthPx] with [frames], so the two can never
     * disagree.
     *
     * The `Layout` measures the text slot at the **target** mode's width
     * (decision COMPOSER-D1): `textWidth(W, aw, if (chipsInteractive) 1f
     * else 0f)` — the mode being animated **to**, so a draft wraps once, at
     * its final width, exactly as UIKit lays text out at the model frame.
     */
    fun textWidth(width: Float, actionWidth: Float, progress: Float, density: Float): Float =
        textWidthPx(width, actionWidth, progress, density)

    /**
     * The corner radius in dp: `lerp(25, 26, pc)` — clamped, unlike the
     * positions (`ComposerBar.swift:327`).
     */
    fun radiusDp(progress: Float): Float =
        lerp(ComposerMetrics.CapsuleRadius, ComposerMetrics.CardRadius, progress.coerceIn(0f, 1f))

    /** The text leading in the capsule: 8 + 34 + 10 = 52 (`:256`). */
    private fun capsuleTextLeading(d: Float): Float =
        (ComposerMetrics.CapsuleEdge + ComposerMetrics.Control + ComposerMetrics.CapsuleTextGapAfterAttach) * d

    /** The one width formula, shared by [frames] and [textWidth]. */
    private fun textWidthPx(width: Float, actionWidth: Float, progress: Float, density: Float): Float {
        val m = ComposerMetrics
        val x = lerp(capsuleTextLeading(density), m.CardTextInset * density, progress)
        val trailing = lerp(m.CardTextInset * density + actionWidth, m.CardTextInset * density, progress)
        return max(0f, width - x - trailing)
    }

    /** `a + (b − a)·t` — local, never Material's `lerp`. */
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
