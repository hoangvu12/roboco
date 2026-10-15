package sh.roboco.android.design

/**
 * The action sheet's pure geometry (android-ui-parity #19), the Android port
 * of the system-drawn `UIAlertController(preferredStyle: .actionSheet)` chrome
 * on iPhone: a bottom-anchored stack of two cards — the action card (optional
 * title/message header + one row per action) over a separate Cancel card.
 * UIKit draws all of this itself, so every number here is the parent epic's
 * approximation of the system chrome
 * (`.scratch/android-app/issues/20-sessions-list.md:277-284` plus this
 * ticket's additions), tunable against a real device; nothing comes from the
 * iOS app source.
 *
 * All functions take and return **dp floats** so they stay unit-testable on
 * the plain JVM; the composables in `ActionSheet.kt` convert with `dp`/`toPx`.
 */
internal object ActionSheetMetrics {

    /**
     * The stack never gets wider than the widest iPhone portrait width, so
     * tablets and landscape phones keep the phone look (ADAPT in the ticket:
     * no popover on wide windows).
     */
    const val WidthCapDp = 440f

    /** 8 dp each side of the stack; the width rule subtracts both. */
    const val SideMarginDp = 8f

    /** Minimum gap under the Cancel card (`max(navBottom, 8)`). */
    const val BottomMinGapDp = 8f

    /** The gap's floor above the gesture handle / button bar. */
    const val TopMarginDp = 8f

    /** The Cancel card's fixed height (one row, no header). */
    const val CancelHeightDp = 56f

    /** Gap between the action card and the Cancel card. */
    const val CardGapDp = 8f

    /** Continuous-corner radius of both cards (ticket 01's shape). */
    const val CardRadiusDp = 26f

    /** Header padding: top/bottom 14, start/end 16. */
    const val HeaderPaddingVerticalDp = 14f
    const val HeaderPaddingHorizontalDp = 16f

    /** Title ↔ message spacing, only when both exist. */
    const val HeaderTitleToMessageGapDp = 2f

    /** Action row minimum height (exactly 56 at font factor 1.0). */
    const val RowMinHeightDp = 56f

    /** Row vertical padding; only matters above factor 1.0. */
    const val RowVerticalPaddingDp = 12f

    /** Row title padding start/end. */
    const val RowTitlePaddingDp = 16f

    /** Row title padding when any row of the sheet is checked (keeps the centred title clear of the checkmark). */
    const val RowCheckedTitlePaddingDp = 44f

    /** The checkmark box's start inset from the card edge (06's `17 pt` box ends at 36.4 < 44). */
    const val CheckmarkStartInsetDp = 16f

    /** The checkmark's SF point size (ticket 06 `Icon`). */
    const val CheckmarkSizePt = 17f
}

/**
 * The stack width: `min(min(windowWidth, windowHeight), 440) − 16`, centred
 * horizontally by the caller. 393-wide portrait phone → 377; landscape and
 * tablets cap at 424 (the ticket's ADAPT for wide windows).
 */
fun actionSheetWidth(windowWidth: Float, windowHeight: Float): Float =
    minOf(minOf(windowWidth, windowHeight), ActionSheetMetrics.WidthCapDp) -
        2f * ActionSheetMetrics.SideMarginDp

/**
 * The gap under the Cancel card: `max(navBottom, 8)`, where `navBottom` is
 * `WindowInsets.navigationBars ∪ displayCutout` bottom — the sheet clears the
 * gesture handle and 3-button bar, and never sits closer than 8 dp to the edge.
 */
fun actionSheetBottomGap(navBottom: Float): Float =
    maxOf(navBottom, ActionSheetMetrics.BottomMinGapDp)

/**
 * The action card's height cap: the window minus the status top, an 8 dp top
 * margin, the bottom gap, the Cancel card and the inter-card gap. Header plus
 * rows beyond it keep the header fixed and scroll the rows.
 */
fun actionSheetMaxCardHeight(windowHeight: Float, statusTop: Float, navBottom: Float): Float =
    windowHeight -
        statusTop -
        ActionSheetMetrics.TopMarginDp -
        actionSheetBottomGap(navBottom) -
        ActionSheetMetrics.CancelHeightDp -
        ActionSheetMetrics.CardGapDp
