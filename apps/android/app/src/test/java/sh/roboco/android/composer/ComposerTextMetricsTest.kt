package sh.roboco.android.composer

import androidx.compose.ui.input.key.Key
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ComposerTextMetrics] — android-ui-parity #57's pure math, the
 * plain-JVM tier (no Robolectric runner): every cell of the ticket's
 * reference table at the iOS line height `L = 21.45` (= `1.3 × 16.5`,
 * Geist hhea 1005/−295, lineGap 0) to ±0.01:
 *
 * | Lines in text | Capsule (preview) | Card | Card, compact height |
 * |---|---|---|---|
 * | 0 (empty) / 1 | 50 | 46 | 46 |
 * | 2 | 70.9 | 62.9 | 62.9 |
 * | 3 | 70.9 (2 lines + `…`) | 84.35 | 84.35 |
 * | 4 | 70.9 | 105.8 | 84.35 (scrolls) |
 * | 8 | 70.9 | 191.6 | 84.35 (scrolls) |
 * | 12 | 70.9 | 191.6 (scrolls) | 84.35 (scrolls) |
 *
 * plus the linear font-scale-2.0 column (`bodyScaled(16.5) = 33`,
 * `L = 42.9`): capsule 1 line = 70.9, 2 lines = 113.8; card 1 line =
 * 62.9, 8 lines = 363.2 (exactly at the cap, so it does not scroll);
 * plus [ComposerTextMetrics.isCtrlEnter]'s modifier-mask table (iOS ⌘↩
 * matches ⌘ and nothing else) and the [ComposerTextMetrics.scrolls]
 * mirroring of `isScrollEnabled` (:545).
 */
class ComposerTextMetricsTest {

    /** The iOS line height (fractional `font.lineHeight`); rendered tests measure instead. */
    private val l = 21.45f

    /** The ticket's height table cell: [targetHeight] at one content height. */
    private fun capsule(content: Float): Float =
        ComposerTextMetrics.targetHeight(isCard = false, compactHeight = false, lineHeight = l, contentHeight = content)

    private fun card(content: Float): Float =
        ComposerTextMetrics.targetHeight(isCard = true, compactHeight = false, lineHeight = l, contentHeight = content)

    private fun compactCard(content: Float): Float =
        ComposerTextMetrics.targetHeight(isCard = true, compactHeight = true, lineHeight = l, contentHeight = content)

    // ------------------------------------------------- the L = 21.45 table

    /** The preview's content height for an n-line text: `min(n, 2) × L` (the caller caps). */
    private fun previewContent(lines: Int): Float = l * min(lines, 2)

    @Test
    fun theCapsuleColumnMatchesTheReferenceTable() {
        // 1 line: the 50 dp minimum (an empty text is also one line).
        assertEquals(50f, capsule(previewContent(1)), 0.01f)
        // #593: the preview is capped at two lines by the CALLER (it
        // passes `heightOfFirstLines(min(lineCount, 2))`), so a 2-, 3-
        // or 12-line draft all rest at the same two-line height — never a
        // scrolled view whose third line bleeds into the bottom inset.
        assertEquals(70.9f, capsule(previewContent(2)), 0.01f)
        assertEquals(70.9f, capsule(previewContent(3)), 0.01f)
        assertEquals(70.9f, capsule(previewContent(12)), 0.01f)
        // The formula itself applies no max on purpose — iOS's preview
        // branch has no maxHeight either — so an uncapped content height
        // would simply be taller (28 + 12L).
        assertEquals(285.4f, capsule(l * 12), 0.01f)
    }

    @Test
    fun theCardColumnMatchesTheReferenceTable() {
        assertEquals(46f, card(l * 1), 0.01f) // the 46 dp minimum
        assertEquals(62.9f, card(l * 2), 0.01f)
        assertEquals(84.35f, card(l * 3), 0.01f)
        assertEquals(105.8f, card(l * 4), 0.01f)
        assertEquals(191.6f, card(l * 8), 0.01f) // exactly at 20 + 8L: no scroll yet
        assertEquals(191.6f, card(l * 12), 0.01f) // capped: 277.4 fitting, 191.6 max
    }

    @Test
    fun theCompactCardColumnMatchesTheReferenceTable() {
        assertEquals(46f, compactCard(l * 1), 0.01f)
        assertEquals(62.9f, compactCard(l * 2), 0.01f)
        assertEquals(84.35f, compactCard(l * 3), 0.01f)
        // 4+ lines: capped at 20 + 3L and scrolling inside.
        assertEquals(84.35f, compactCard(l * 4), 0.01f)
        assertEquals(84.35f, compactCard(l * 12), 0.01f)
    }

    @Test
    fun theFontScale2ColumnMatchesTheReferenceTable() {
        // bodyScaled(16.5) = 33 → L = 42.9: the same arithmetic, scaled.
        val big = 42.9f
        // The card's cap is `L × 8 + insets`, so the scaled column must pass
        // the scaled L (the [card] helper is pinned to the 21.45 table).
        fun bigCard(content: Float): Float =
            ComposerTextMetrics.targetHeight(isCard = true, compactHeight = false, lineHeight = big, contentHeight = content)
        assertEquals(70.9f, capsule(big), 0.01f) // max(50, 28 + 42.9)
        assertEquals(113.8f, capsule(big * 2), 0.01f) // 28 + 85.8
        assertEquals(62.9f, bigCard(big), 0.01f) // max(46, 20 + 42.9)
        assertEquals(363.2f, bigCard(big * 8), 0.01f) // exactly 20 + 8 × 42.9
        // ...and at 9 lines it caps and scrolls.
        assertEquals(363.2f, bigCard(big * 9), 0.01f)
        assertTrue(ComposerTextMetrics.scrolls(isCard = true, compactHeight = false, lineHeight = big, contentHeight = big * 9))
    }

    // ------------------------------------------------------- the flags

    @Test
    fun maxLinesFollowsTheMode() {
        assertEquals(2, ComposerTextMetrics.maxLines(isCard = false, compactHeight = false))
        assertEquals(2, ComposerTextMetrics.maxLines(isCard = false, compactHeight = true))
        assertEquals(8, ComposerTextMetrics.maxLines(isCard = true, compactHeight = false))
        assertEquals(3, ComposerTextMetrics.maxLines(isCard = true, compactHeight = true))
    }

    @Test
    fun insetBottomSwitchesWithTheMode() {
        assertEquals(14f, ComposerTextMetrics.insetBottom(isCard = false), 0f)
        assertEquals(6f, ComposerTextMetrics.insetBottom(isCard = true), 0f)
    }

    @Test
    fun scrollsMirrorsIsScrollEnabled() {
        // Resting never scrolls (the 2-line preview has no cap to pass).
        assertFalse(ComposerTextMetrics.scrolls(isCard = false, compactHeight = false, lineHeight = l, contentHeight = l * 12))
        // Card: under the cap no, past it yes (:545).
        assertFalse(ComposerTextMetrics.scrolls(isCard = true, compactHeight = false, lineHeight = l, contentHeight = l * 8))
        assertTrue(ComposerTextMetrics.scrolls(isCard = true, compactHeight = false, lineHeight = l, contentHeight = l * 9))
        assertTrue(ComposerTextMetrics.scrolls(isCard = true, compactHeight = true, lineHeight = l, contentHeight = l * 4))
    }

    // ------------------------------------------------------ isCtrlEnter

    @Test
    fun isCtrlEnterMatchesEnterAndNumPadEnterWithCtrlOnly() {
        assertTrue(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = true, shift = false, alt = false, meta = false))
        assertTrue(ComposerTextMetrics.isCtrlEnter(Key.NumPadEnter, ctrl = true, shift = false, alt = false, meta = false))
    }

    @Test
    fun isCtrlEnterRejectsEveryOtherModifierSet() {
        // ⌘↩'s modifierFlags match is exact: Enter alone, Shift, Alt, Meta
        // or any combination with Ctrl does not match (and falls through
        // to a plain newline).
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = false, shift = false, alt = false, meta = false))
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = false, shift = true, alt = false, meta = false))
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = true, shift = true, alt = false, meta = false))
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = true, shift = false, alt = true, meta = false))
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.Enter, ctrl = true, shift = false, alt = false, meta = true))
        // Not an Enter key at all: Ctrl+A is a shortcut, not a send.
        assertFalse(ComposerTextMetrics.isCtrlEnter(Key.A, ctrl = true, shift = false, alt = false, meta = false))
    }
}
