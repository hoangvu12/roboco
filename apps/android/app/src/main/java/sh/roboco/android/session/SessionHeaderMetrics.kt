package sh.roboco.android.session

import sh.roboco.android.core.GeistMetrics

/**
 * [SessionHeader]'s pure geometry (android-ui-parity #52) — dp as `Float`,
 * no Compose UI types, so it is unit-testable on the plain JVM
 * (`SessionHeaderMetricsTest`).
 *
 * The two anchors are the natural Geist line boxes (ticket 03's
 * [GeistMetrics], `includeFontPadding = false`, no `lineHeight`): the title
 * line is 16 × 1.3 = 20.8, the subtitle line 12 × 1.3 = 15.6, and the stack
 * keeps UIKit's 1 pt spacing (`ios:Session/SessionViewController.swift:563`),
 * so the two-line title view is 37.4 tall (the title line's 20.8 alone when
 * the subtitle is empty — a hidden arranged subview takes no space and no
 * spacing, `:577`), vertically centred in the 44 pt bar row (UIKit centres a
 * `titleView` in the bar).
 *
 * The title cap is `:571`'s
 * `widthAnchor.constraint(lessThanOrEqualToConstant: 240)`, squeezed on
 * narrow screens by the shared bar-item gap approximation (ticket 25's
 * `TitleItemGap`): `width − 2 × (max(lead, trail) + 8)`, where a side with a
 * 44 pt glass circle extends 16 + 44 = 60.
 */
object SessionHeaderMetrics {

    /**
     * The title view's max width: the 240 cap, or the width minus twice the
     * larger bar-side extent (16 + 44 where a 44 pt circle sits, 16 where
     * none does) plus the 8 dp item gap — whichever is smaller, never
     * negative. [showBack] only matters where the leading side would exceed
     * the trailing one, which it never does at the defaults (60 = 60): the
     * ⋯ circle always bounds the trailing side.
     *
     * Worked values: 402 → 240, 412 → 240, 360 → 224, 320 → 184, 100 → 0.
     *
     * [extraTrailing] widens the trailing side for a second bar item
     * (android-next #06's side chats button: [AccessoryExtent], one more
     * circle and the item gap) — 402 → 162, 360 → 120.
     */
    fun titleMaxWidth(
        width: Float,
        showBack: Boolean,
        side: Float = SessionHeaderDefaults.SideMargin.value,
        button: Float = SessionHeaderDefaults.ButtonSize.value,
        gap: Float = SessionHeaderDefaults.TitleItemGap.value,
        cap: Float = SessionHeaderDefaults.TitleMaxWidth.value,
        extraTrailing: Float = 0f,
    ): Float {
        val lead = if (showBack) side + button else side
        val trail = side + button + extraTrailing
        return minOf(cap, width - 2 * (maxOf(lead, trail) + gap)).coerceAtLeast(0f)
    }

    /** A second trailing bar item's extent: its 44 circle plus the 8 item gap. */
    val AccessoryExtent: Float = SessionHeaderDefaults.ButtonSize.value + SessionHeaderDefaults.TitleItemGap.value

    /**
     * The title view's height: 37.4 with a subtitle (20.8 + 1 + 15.6), the
     * title line's 20.8 without one.
     */
    fun titleViewHeight(hasSubtitle: Boolean): Float =
        if (hasSubtitle) {
            GeistMetrics.LINE_HEIGHT * SessionHeaderDefaults.TitlePt +
                SessionHeaderDefaults.TitleSpacing.value +
                GeistMetrics.LINE_HEIGHT * SessionHeaderDefaults.SubtitlePt
        } else {
            GeistMetrics.LINE_HEIGHT * SessionHeaderDefaults.TitlePt
        }

    /**
     * The title view's top inside the 44 pt bar row: (44 − height) / 2 —
     * 3.3 with a subtitle, 11.6 without (UIKit centres a `titleView`
     * vertically in the bar row).
     */
    fun titleViewTop(
        hasSubtitle: Boolean,
        bar: Float = SessionHeaderDefaults.BarHeight.value,
    ): Float = (bar - titleViewHeight(hasSubtitle)) / 2f

    /**
     * The title's baseline below the bar row's top: 19.38 with a subtitle
     * (3.3 + 1.005 × 16), 27.68 without. The measured reference
     * (`ios-bubble-light.png`) has 19.5 (81.5 − 62 at @3x).
     */
    fun titleBaseline(hasSubtitle: Boolean): Float =
        titleViewTop(hasSubtitle) + GeistMetrics.ASCENT * SessionHeaderDefaults.TitlePt

    /**
     * The subtitle's baseline below the bar row's top: 37.16 (3.3 + 20.8 +
     * 1 + 1.005 × 12). The measured reference has 37.2 (99.2 − 62 at @3x).
     */
    fun subtitleBaseline(): Float =
        titleViewTop(true) +
            GeistMetrics.LINE_HEIGHT * SessionHeaderDefaults.TitlePt +
            SessionHeaderDefaults.TitleSpacing.value +
            GeistMetrics.ASCENT * SessionHeaderDefaults.SubtitlePt
}
