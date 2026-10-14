package sh.roboco.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NewSessionLayout] (android-ui-parity #93) — the pure dp arithmetic of
 * `ios:Session/NewSessionViewController.swift:101-237`, pinned to the
 * ticket's acceptance rows (all floats ±1e-3). Plain JUnit: no Compose,
 * no Robolectric.
 */
class NewSessionLayoutTest {

    private fun eq(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-3f)

    // ── the composer ─────────────────────────────────────────────────────

    @Test
    fun composerWidthIsSafeWidthMinus24CappedAt768() {
        eq(387f, NewSessionLayout.composerWidth(411f))
        eq(768f, NewSessionLayout.composerWidth(896f))
        // Below the side insets: 0, never negative.
        eq(0f, NewSessionLayout.composerWidth(10f))
    }

    @Test
    fun composerXIsCentredOnTheSafeArea() {
        // Landscape case F: safe 48…891, cw = 768.
        eq(85.5f, NewSessionLayout.composerX(48f, 843f, 768f))
        // Portrait case A: safe 0…411, cw = 387.
        eq(12f, NewSessionLayout.composerX(0f, 411f, 387f))
    }

    @Test
    fun composerBottomIsTheHeightMinusInsetMinus8() {
        eq(825f, NewSessionLayout.composerBottom(857f, 24f))
        eq(513f, NewSessionLayout.composerBottom(521f, 0f))
    }

    // ── the hero ─────────────────────────────────────────────────────────

    @Test
    fun headlineMaxWidthUsesTheLargerSafeSideOnBothSides() {
        eq(747f, NewSessionLayout.headlineMaxWidth(891f, 48f, 0f))
        // Symmetric safe insets: W − 48.
        eq(363f, NewSessionLayout.headlineMaxWidth(411f, 0f, 0f))
        // Never negative.
        eq(0f, NewSessionLayout.headlineMaxWidth(20f, 48f, 0f))
    }

    @Test
    fun heroTopIsCentredWhenItFits() {
        // Case A: free 52…671, one line (76.6).
        eq(323.2f, NewSessionLayout.heroTop(52f, 671f, 76.6f))
        // Case B: keyboard up, free 52…359.
        eq(167.2f, NewSessionLayout.heroTop(52f, 359f, 76.6f))
        // Case D1: squeezed but a one-line headline still fits.
        eq(56.7f, NewSessionLayout.heroTop(52f, 138f, 76.6f))
        // Case E: the embedded tablet column, free 44…638.
        eq(302.7f, NewSessionLayout.heroTop(44f, 638f, 76.6f))
    }

    @Test
    fun heroTopBottomWinsWhenItDoesNotFit() {
        // Case D2: a two-line headline (105.2) in free 52…138 — the hero
        // ends 20 dp above the composer and may slide under the nav bar.
        eq(32.8f, NewSessionLayout.heroTop(52f, 138f, 105.2f))
    }

    // ── the wallpaper ────────────────────────────────────────────────────

    @Test
    fun wallpaperHeightIs72PercentOfTheImeLessPageHeightCappedAt760() {
        eq(617.04f, NewSessionLayout.wallpaperHeight(857f))
        eq(760f, NewSessionLayout.wallpaperHeight(1200f))
        // The tablet column: 0.72 × 800.
        eq(576f, NewSessionLayout.wallpaperHeight(800f))
    }
}
