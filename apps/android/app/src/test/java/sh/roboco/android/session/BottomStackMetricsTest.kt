package sh.roboco.android.session

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BottomStackMetrics]' pure math (android-ui-parity #74), as a **plain
 * JUnit** class — no Robolectric runner, no composition, part of the merge
 * check. Every value is pinned by the ticket's worked-examples table
 * (dp at density 1), plus the degenerate cases the formulas must survive.
 */
class BottomStackMetricsTest {

    // ------------------------------------------------------- the A–E table

    /** Case A: phone, composer only. */
    @Test
    fun caseAComposerOnly() {
        val w = BottomStackMetrics.stackWidth(412f, 0f, 0f)
        assertEquals(388f, w, 0.01f)
        assertEquals(12f, BottomStackMetrics.stackX(412f, 0f, 0f, w), 0.01f)
        assertEquals(883f, BottomStackMetrics.stackBottom(915f, 0f, 24f), 0.01f)
        assertEquals(833f, 883f - 50f, 0.01f) // stackTop = bottom − composer 50
        assertEquals(90f, BottomStackMetrics.covered(915f, 833f), 0.01f)
        assertEquals(66f, BottomStackMetrics.contentInset(90f, 24f), 0.01f)
        assertEquals(789f, BottomStackMetrics.fadeTop(833f), 0.01f)
        val jump = BottomStackMetrics.jumpOrigin(412f, 0f, 833f, 40f, 40f)
        assertEquals(356f, jump.first, 0.01f)
        assertEquals(781f, jump.second, 0.01f)
    }

    /** Case B: pill + one-row queue + composer (queue 48 = 40 + 4 + 4). */
    @Test
    fun caseBPillQueueComposer() {
        val extent = BottomStackMetrics.slotTarget(BottomStackSlot.Pill, shown = true, h = 30f) +
            BottomStackMetrics.slotTarget(BottomStackSlot.Queue, shown = true, h = 48f) +
            BottomStackMetrics.slotTarget(BottomStackSlot.Composer, shown = true, h = 50f)
        assertEquals(144f, extent, 0.01f) // 38 + 56 + 50
        val stackTop = 883f - extent
        assertEquals(739f, stackTop, 0.01f)
        assertEquals(184f, BottomStackMetrics.covered(915f, stackTop), 0.01f)
        assertEquals(160f, BottomStackMetrics.contentInset(184f, 24f), 0.01f)
        assertEquals(695f, BottomStackMetrics.fadeTop(stackTop), 0.01f)
        val jump = BottomStackMetrics.jumpOrigin(412f, 0f, stackTop, 40f, 40f)
        assertEquals(356f, jump.first, 0.01f)
        assertEquals(687f, jump.second, 0.01f)
    }

    /** Case C: keyboard up (ime 300, nav 24). */
    @Test
    fun caseCKeyboard() {
        assertEquals(607f, BottomStackMetrics.stackBottom(915f, 300f, 24f), 0.01f)
        val stackTop = 607f - 50f
        assertEquals(557f, stackTop, 0.01f)
        assertEquals(366f, BottomStackMetrics.covered(915f, stackTop), 0.01f)
        assertEquals(342f, BottomStackMetrics.contentInset(366f, 24f), 0.01f)
        assertEquals(513f, BottomStackMetrics.fadeTop(stackTop), 0.01f)
        val jump = BottomStackMetrics.jumpOrigin(412f, 0f, stackTop, 40f, 40f)
        assertEquals(356f, jump.first, 0.01f)
        assertEquals(505f, jump.second, 0.01f)
    }

    /** Case D: tablet 1280 × 800, nav 48. */
    @Test
    fun caseDTablet() {
        val w = BottomStackMetrics.stackWidth(1280f, 0f, 0f)
        assertEquals(768f, w, 0.01f)
        assertEquals(256f, BottomStackMetrics.stackX(1280f, 0f, 0f, w), 0.01f)
        assertEquals(744f, BottomStackMetrics.stackBottom(800f, 0f, 48f), 0.01f)
        val stackTop = 744f - 50f
        assertEquals(694f, stackTop, 0.01f)
        assertEquals(114f, BottomStackMetrics.covered(800f, stackTop), 0.01f)
        assertEquals(66f, BottomStackMetrics.contentInset(114f, 48f), 0.01f)
        assertEquals(650f, BottomStackMetrics.fadeTop(stackTop), 0.01f)
        val jump = BottomStackMetrics.jumpOrigin(1280f, 0f, stackTop, 40f, 40f)
        assertEquals(1224f, jump.first, 0.01f)
        assertEquals(642f, jump.second, 0.01f)
    }

    /** Case E: landscape cutout — 915 × 412, safeL 48; the width caps at 768. */
    @Test
    fun caseELandscapeCutout() {
        val w = BottomStackMetrics.stackWidth(915f, 48f, 0f)
        assertEquals(768f, w, 0.01f)
        // x = 48 + (915 − 48 − 768) / 2 — the ticket's exact 97.5.
        assertEquals(97.5f, BottomStackMetrics.stackX(915f, 48f, 0f, w), 0.01f)
        assertEquals(404f, BottomStackMetrics.stackBottom(412f, 0f, 0f), 0.01f)
        val stackTop = 404f - 50f
        assertEquals(354f, stackTop, 0.01f)
        assertEquals(66f, BottomStackMetrics.covered(412f, stackTop), 0.01f)
        assertEquals(66f, BottomStackMetrics.contentInset(66f, 0f), 0.01f)
        assertEquals(310f, BottomStackMetrics.fadeTop(stackTop), 0.01f)
        val jump = BottomStackMetrics.jumpOrigin(915f, 0f, stackTop, 40f, 40f)
        assertEquals(859f, jump.first, 0.01f)
        assertEquals(302f, jump.second, 0.01f)
    }

    // ------------------------------------------------------ degenerate input

    /** The width never goes below 0 (W = 20 → 0). */
    @Test
    fun stackWidthNeverGoesNegative() {
        assertEquals(0f, BottomStackMetrics.stackWidth(20f, 0f, 0f), 0.01f)
        assertEquals(0f, BottomStackMetrics.stackWidth(0f, 0f, 0f), 0.01f)
        // Safe insets wider than the window still clamp to 0.
        assertEquals(0f, BottomStackMetrics.stackWidth(100f, 90f, 90f), 0.01f)
    }

    /** Wave-10 42 (#1014): level with the sidebar toolbar, until the keyboard lifts it. */
    @Test
    fun besideTheSidebarTheRestingComposerCentresOnItsToolbar() {
        // Wave-10 42 (#1014): a 1000 dp window, the toolbar's middle 56 up
        // (nav 22 + gap 12 + half of 44): the 50 dp composer's bottom sits
        // 25 below that middle — 969 — and the keyboard lifts it once its
        // top passes the rest.
        assertEquals(969f, BottomStackMetrics.stackBottomBesideToolbar(1000f, ime = 0f, toolbarMid = 56f), 0.001f)
        assertEquals(969f, BottomStackMetrics.stackBottomBesideToolbar(1000f, ime = 20f, toolbarMid = 56f), 0.001f)
        assertEquals(692f, BottomStackMetrics.stackBottomBesideToolbar(1000f, ime = 300f, toolbarMid = 56f), 0.001f)
    }

    /** The IME wins over the nav bar; with neither, the gap is 8. */
    @Test
    fun stackBottomTakesTheMaxOfImeAndNav() {
        assertEquals(907f, BottomStackMetrics.stackBottom(915f, 0f, 0f), 0.01f)
        assertEquals(607f, BottomStackMetrics.stackBottom(915f, 300f, 24f), 0.01f)
        // The nav bar wins the same way: 915 − max(24, 300) − 8 = 607 (the
        // literal was mis-computed as 611).
        assertEquals(607f, BottomStackMetrics.stackBottom(915f, 24f, 300f), 0.01f)
    }

    /** The content inset floors at 0 when the stack covers nothing. */
    @Test
    fun contentInsetFloorsAtZero() {
        assertEquals(0f, BottomStackMetrics.contentInset(0f, 24f), 0.01f)
        assertEquals(0f, BottomStackMetrics.contentInset(10f, 24f), 0.01f)
        assertEquals(2f, BottomStackMetrics.contentInset(26f, 24f), 0.01f)
    }

    /** Hidden slots take no space; the pill and queue carry their 8 dp gap. */
    @Test
    fun slotTargets() {
        assertEquals(38f, BottomStackMetrics.slotTarget(BottomStackSlot.Pill, shown = true, h = 30f), 0.01f)
        assertEquals(56f, BottomStackMetrics.slotTarget(BottomStackSlot.Queue, shown = true, h = 48f), 0.01f)
        assertEquals(220f, BottomStackMetrics.slotTarget(BottomStackSlot.Questions, shown = true, h = 220f), 0.01f)
        assertEquals(50f, BottomStackMetrics.slotTarget(BottomStackSlot.Composer, shown = true, h = 50f), 0.01f)
        assertEquals(0f, BottomStackMetrics.slotTarget(BottomStackSlot.Pill, shown = false, h = 30f), 0.01f)
        assertEquals(0f, BottomStackMetrics.slotTarget(BottomStackSlot.Composer, shown = false, h = 50f), 0.01f)
    }

    /**
     * The collapse clip: `extent × h / (h + gap)` — at the settled extent
     * the full height shows; mid-collapse half of it; a gapless slot clips
     * to its extent; a degenerate slot clips to nothing.
     */
    @Test
    fun visibleHeights() {
        assertEquals(30f, BottomStackMetrics.visibleHeight(38f, 30f, 8f), 0.01f)
        assertEquals(15f, BottomStackMetrics.visibleHeight(19f, 30f, 8f), 0.01f)
        assertEquals(48f, BottomStackMetrics.visibleHeight(48f, 48f, 0f), 0.01f)
        assertEquals(24f, BottomStackMetrics.visibleHeight(24f, 48f, 0f), 0.01f)
        assertEquals(0f, BottomStackMetrics.visibleHeight(10f, 0f, 0f), 0.01f)
        assertEquals(0f, BottomStackMetrics.visibleHeight(10f, -5f, 0f), 0.01f)
    }

    /** The dp tokens pin the UIKit constants verbatim. */
    @Test
    fun constantsPinTheUIKitValues() {
        assertEquals(12.dp, BottomStackMetrics.SideInset)
        assertEquals(768.dp, BottomStackMetrics.MaxWidth)
        assertEquals(8.dp, BottomStackMetrics.Spacing)
        assertEquals(8.dp, BottomStackMetrics.KeyboardGap)
        assertEquals(8.dp, BottomStackMetrics.InsetPad)
        assertEquals(44.dp, BottomStackMetrics.FadeAbove)
        assertEquals(16.dp, BottomStackMetrics.JumpTrailing)
        assertEquals(12.dp, BottomStackMetrics.JumpAbove)
        assertEquals(30.dp, BottomStackMetrics.PillHeight)
    }
}
