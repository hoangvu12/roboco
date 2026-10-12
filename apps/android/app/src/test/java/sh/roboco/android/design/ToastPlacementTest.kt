package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The toast placement math (ticket 14, `ToastPlacement.kt`): the anchor
 * rule (iOS `chromeTop`, `Toast.swift:29-45`), the rest-y formula
 * (`:19,22`) and the width cap (`:23`). Pure dp math — plain JVM, no
 * Robolectric.
 *
 * The capsule height 43.5 dp is the ticket's derived value
 * (12 + 19.5 + 12; Geist 15 line box: hhea ascent 1005, descent −295,
 * lineGap 0, UPM 1000 → 1.3 × 15 = 19.5).
 */
class ToastPlacementTest {

    @Test
    fun chromeTopWinsWhenItIsHigherThanTheSafeBottom() {
        // 734 − 12 − 43.5
        assertEquals(678.5f, toastRestY(safeBottom = 852f, chromeTop = 734f, capsuleHeight = 43.5f), 1e-4f)
    }

    @Test
    fun noChromeMeansTheSafeAreaBottom() {
        // 852 − 12 − 43.5
        assertEquals(796.5f, toastRestY(safeBottom = 852f, chromeTop = Float.NaN, capsuleHeight = 43.5f), 1e-4f)
    }

    @Test
    fun aSafeBottomAboveTheChromeWins() {
        // IME up (keyboard inset 252): safe bottom 600 above the chrome 734
        // — the keyboard counts as a bottom edge, so the min is 600.
        assertEquals(544.5f, toastRestY(safeBottom = 600f, chromeTop = 734f, capsuleHeight = 43.5f), 1e-4f)
    }

    @Test
    fun anchorTakesTheMinOfSafeBottomAndChrome() {
        assertEquals(734f, toastAnchor(852f, 734f), 1e-4f)
        assertEquals(600f, toastAnchor(600f, 734f), 1e-4f)
        assertEquals(852f, toastAnchor(852f, Float.NaN), 1e-4f)
    }

    @Test
    fun aChromeBelowTheSafeBottomDoesNotMoveTheAnchor() {
        // chromeTop 900 > safeBottom 852: the safe-area bottom wins.
        assertEquals(852f, toastAnchor(852f, 900f), 1e-4f)
    }

    @Test
    fun maxWidthCapsAtWindowMinus48() {
        assertEquals(345f, toastMaxWidth(393f), 1e-4f)
    }

    @Test
    fun maxWidthNeverGoesNegative() {
        assertEquals(0f, toastMaxWidth(40f), 1e-4f)
    }
}
