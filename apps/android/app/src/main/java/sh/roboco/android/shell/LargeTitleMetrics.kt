package sh.roboco.android.shell

import sh.roboco.android.core.GeistMetrics

/**
 * [LargeTitleScaffold]'s collapse math (android-ui-parity #25) — pure, dp as
 * `Float`, no Compose types, so it is unit-testable on the plain JVM
 * (`LargeTitleMetricsTest`).
 *
 * All values come from the ticket's geometry: the large-title row is 52 dp,
 * the inline row 44 dp, the large title is Geist SemiBold 30 with a natural
 * line height of 39 dp (1.3 em) bottom-inset 8 dp inside its row, and the
 * inline title fades in over 12 dp of scroll centred on the moment the
 * large title's baseline crosses the nav row's bottom.
 *
 * `L` (the row height) and `B` (the baseline) are the two anchors:
 *
 * ```
 * B = 52 − 8 − 1.3·30 + 1.005·30 = 35.15
 * ```
 *
 * `c` (the collapse distance) is how far the large-title row has scrolled up
 * under the nav row, clamped to `0 … L`. At `c = 0` the large title is fully
 * visible and the inline title invisible; at `c ≥ 41.15` only the inline
 * title shows; between the two the large title is clipped away from its top
 * while the inline title fades in.
 */
object LargeTitleMetrics {

    /**
     * Baseline of the large title below its row's top:
     * `52 − 8 − 1.3·30 + 1.005·30 = 35.15` — `rowH − bottomInset − line +
     * ascent`, the natural Geist line box bottom-aligned at the bottom inset.
     */
    fun largeBaseline(
        rowH: Float = 52f,
        bottomInset: Float = 8f,
        pt: Float = 30f,
    ): Float = rowH - bottomInset - GeistMetrics.LINE_HEIGHT * pt + GeistMetrics.ASCENT * pt

    /**
     * `c` clamped to `0 … rowH`: the first item's scroll offset while item 0
     * is visible, and the full row height once it has scrolled away
     * (`firstIndex > 0`) — the row is exactly hidden at that point, so the
     * clip never needs to go further.
     */
    fun collapse(firstIndex: Int, firstOffsetDp: Float, rowH: Float = 52f): Float =
        if (firstIndex == 0) firstOffsetDp.coerceIn(0f, rowH) else rowH

    /**
     * The inline title's alpha: a linear ramp of [fade] dp centred on the
     * baseline crossing (`c = baseline`), i.e. 0 at `c = B − fade/2`
     * (29.15) and 1 at `c = B + fade/2` (41.15). Purely scroll-driven; there
     * is no time-based tween.
     */
    fun inlineAlpha(c: Float, baseline: Float = largeBaseline(), fade: Float = 12f): Float =
        ((c - (baseline - fade / 2)) / fade).coerceIn(0f, 1f)

    /**
     * The end-of-scroll snap target: UIKit never rests a large title
     * half-collapsed, so a release with `0 < c < rowH` settles at 0 (below
     * the 50 % threshold) or `rowH` (at or above it); `null` = nothing to
     * snap. The snap itself is [Motion.LargeTitleSnap] in the scaffold.
     */
    fun snapTarget(c: Float, rowH: Float = 52f): Float? =
        if (c <= 0f || c >= rowH) null else if (c < rowH / 2) 0f else rowH

    /**
     * The inline title's max width: the scaffold width minus twice the
     * larger bar-side extent plus [gap] — UIKit keeps the centred title
     * clear of the bar items (the gap is an approximation; it only matters
     * for long titles beside wide trailing items). Never negative.
     */
    fun inlineMaxWidth(
        width: Float,
        leadingExtent: Float,
        trailingExtent: Float,
        gap: Float = 8f,
    ): Float = (width - 2 * (maxOf(leadingExtent, trailingExtent) + gap)).coerceAtLeast(0f)

    /**
     * The pinned accessory's top y: under the nav row once the large title
     * has collapsed (`c` clamped at `rowH`), riding up with it before that;
     * always pinned at `navBottom` in `InlineOnly` (no large title exists).
     */
    fun accessoryTop(navBottom: Float, c: Float, rowH: Float = 52f, inlineOnly: Boolean): Float =
        if (inlineOnly) navBottom else navBottom + (rowH - c).coerceAtLeast(0f)
}
