package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The action sheet's pure geometry (android-ui-parity #19, the values in
 * the ticket's Acceptance criteria): the 440 dp width cap with 8 dp side
 * margins (landscape keeps the portrait width, tablets cap at 424), the
 * `max(navBottom, 8)` bottom gap, and the action card's height cap.
 *
 * Plain JVM — no Robolectric; the merge check runs it.
 */
class ActionSheetLayoutTest {

    // --------------------------------------------------------------- width

    @Test
    fun aPortraitPhoneIs377Wide() {
        assertEquals(377f, actionSheetWidth(393f, 852f), 0.001f)
    }

    @Test
    fun landscapeKeepsThePortraitWidth() {
        assertEquals(377f, actionSheetWidth(852f, 393f), 0.001f)
    }

    @Test
    fun aTabletCapsAt424() {
        // min(1280, 800) = 800 → capped at 440 − 16.
        assertEquals(424f, actionSheetWidth(1280f, 800f), 0.001f)
        assertEquals(424f, actionSheetWidth(440f, 900f), 0.001f)
        // A wide-short window: the shorter side (400) is under the cap, so
        // it rules — `min(min(900, 400), 440) − 16` = 384 (ticket #19 width row).
        assertEquals(384f, actionSheetWidth(900f, 400f), 0.001f)
    }

    @Test
    fun aNarrowWindowShrinksBelowTheCap() {
        // The shorter side rules: 200 − 16 = 184.
        assertEquals(184f, actionSheetWidth(400f, 200f), 0.001f)
        assertEquals(84f, actionSheetWidth(100f, 200f), 0.001f)
    }

    // --------------------------------------------------------- bottom gap

    @Test
    fun theBottomGapFloorsAt8() {
        assertEquals(8f, actionSheetBottomGap(0f), 0.001f)
        assertEquals(8f, actionSheetBottomGap(-4f), 0.001f)
    }

    @Test
    fun theBottomGapFollowsTheNavInsetAbove8() {
        assertEquals(24f, actionSheetBottomGap(24f), 0.001f)
        assertEquals(44f, actionSheetBottomGap(44f), 0.001f)
        // Below the floor the 8 dp minimum wins: `max(4, 8)` = 8.
        assertEquals(8f, actionSheetBottomGap(4f), 0.001f)
    }

    // ------------------------------------------------------ height cap

    @Test
    fun theMaxCardHeightSubtractsTheChrome() {
        // 852 − 24 − 8 − 8 − 56 − 8.
        assertEquals(748f, actionSheetMaxCardHeight(852f, 24f, 0f), 0.001f)
        // Zero insets: 852 − 0 − 8 − 8 − 56 − 8.
        assertEquals(772f, actionSheetMaxCardHeight(852f, 0f, 0f), 0.001f)
        // The nav inset counts through the gap: 852 − 24 − 8 − 24 − 56 − 8.
        assertEquals(732f, actionSheetMaxCardHeight(852f, 24f, 24f), 0.001f)
    }

    @Test
    fun theMetricsFeedTheFormulas() {
        // The cap and margins come from ActionSheetMetrics, not literals.
        assertEquals(
            ActionSheetMetrics.WidthCapDp - 2f * ActionSheetMetrics.SideMarginDp,
            actionSheetWidth(1280f, 800f),
            0.001f,
        )
        assertEquals(
            ActionSheetMetrics.BottomMinGapDp,
            actionSheetBottomGap(0f),
            0.001f,
        )
        assertEquals(
            852f - 24f - ActionSheetMetrics.TopMarginDp - 8f -
                ActionSheetMetrics.CancelHeightDp - ActionSheetMetrics.CardGapDp,
            actionSheetMaxCardHeight(852f, 24f, 0f),
            0.001f,
        )
    }
}
