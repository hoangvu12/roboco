package sh.roboco.android.composer

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.input.key.Key

/**
 * The composer text slot's constants and pure math (android-ui-parity #57)
 * — the port of `ios:Composer/ComposerBar.swift`'s font, line-limit and
 * height bookkeeping:
 *
 * - `:112-119` — `compactHeight = 50`, `control = 34`, and `maxLines`:
 *   2 while resting (the preview), else 8, or 3 in compact height;
 * - `:163-181` — `textContainerInset` (14 top; 14 bottom resting, 6 in the
 *   card) and `lineFragmentPadding = 0`;
 * - `:531-549` — [recomputeTextHeight][targetHeight] itself, the `0.5`
 *   animation threshold coming from `textChanged` (`:556`).
 *
 * Everything is dp floats (iOS pt 1:1); no Compose runtime, so the pure
 * height table runs on the plain JVM. The only Compose type is [Key], for
 * [isCtrlEnter] — the Android port of the ⌘↩ key command (`:667-674`).
 */
object ComposerTextMetrics {

    /** `textContainerInset.top` in both modes (`:180`). */
    const val InsetTopDp = 14f

    /** `textContainerInset.bottom` while resting (`:180`). */
    const val InsetBottomCapsuleDp = 14f

    /** `textContainerInset.bottom` in the card (`:326`). */
    const val InsetBottomCardDp = 6f

    /** `Self.compactHeight` — the capsule's minimum height (`:119`). */
    const val MinHeightCapsuleDp = 50f

    /** The card's minimum height (`:538`, `isCard ? 46 : …`). */
    const val MinHeightCardDp = 46f

    /** The resting preview's whole-line count (upstream #593). */
    const val PreviewLines = 2

    /** The card's line limit in regular height (`:116`). */
    const val MaxLinesCard = 8

    /** The card's line limit in compact height (`:117`, iPhone landscape). */
    const val MaxLinesCompactHeight = 3

    /**
     * `verticalSizeClass == .compact` → Android's compact-height class:
     * `screenHeightDp < 480`.
     */
    const val CompactHeightBelowDp = 480

    /** `abs(textHeight.constant - before) > 0.5` gates the 180 ms tween (`:556`). */
    const val AnimateThresholdDp = 0.5f

    /** `maxLines` (`:114-118`): 2 resting, 8 in the card, 3 in compact height. */
    fun maxLines(isCard: Boolean, compactHeight: Boolean): Int =
        if (!isCard) PreviewLines else if (compactHeight) MaxLinesCompactHeight else MaxLinesCard

    /** The bottom inset: 14 resting, 6 in the card (`:180` / `:326`). */
    fun insetBottom(isCard: Boolean): Float = if (isCard) InsetBottomCardDp else InsetBottomCapsuleDp

    /**
     * iOS `recomputeTextHeight` (ComposerBar.swift:531-549). All values in dp.
     * [contentHeight] is the laid-out text height: the **full** text in the
     * card, the first [PreviewLines] lines in the capsule (the #593
     * preview — whole lines ending in `…`, never a scrolled view).
     *
     * - capsule (preview): `max(50, fitting)` — no `maxHeight` cap, the
     *   2-line limit already caps it, and it never scrolls;
     * - card: `min(max(46, fitting), L × maxLines + insets)`, scrolling
     *   internally beyond that.
     *
     * [lineHeight] is the **measured** line height (`L`), not `1.3 × size`:
     * Android lines are whole pixels (22 dp at mdpi), and with the
     * fractional 21.45 the card at exactly 8 lines would be ~4 dp short,
     * scroll, and slice the 8th line.
     */
    fun targetHeight(isCard: Boolean, compactHeight: Boolean, lineHeight: Float, contentHeight: Float): Float {
        val insets = InsetTopDp + insetBottom(isCard)
        val fitting = insets + contentHeight
        val min = if (isCard) MinHeightCardDp else MinHeightCapsuleDp
        if (!isCard) return maxOf(min, fitting)
        val max = lineHeight * maxLines(isCard, compactHeight) + insets
        return minOf(maxOf(min, fitting), max)
    }

    /** True when the card's content passed its cap — `isScrollEnabled` (`:545`): `fitting > maxHeight`. */
    @VisibleForTesting
    fun scrolls(isCard: Boolean, compactHeight: Boolean, lineHeight: Float, contentHeight: Float): Boolean {
        if (!isCard) return false
        return contentHeight > lineHeight * maxLines(isCard, compactHeight)
    }

    /**
     * Ctrl+Enter / Ctrl+NumPadEnter with no other modifier — the port of
     * the ⌘↩ key command, whose `modifierFlags` match must be exact
     * (ComposerBar.swift:667-674): ⇧⌘↩ and ⌥⌘↩ do **not** match, so
     * Shift/Alt/Meta combos fall through to a plain newline.
     */
    fun isCtrlEnter(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Boolean =
        (key == Key.Enter || key == Key.NumPadEnter) && ctrl && !shift && !alt && !meta
}
