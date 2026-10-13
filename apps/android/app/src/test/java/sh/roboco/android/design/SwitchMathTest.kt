package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch's position math and tokens (android-ui-parity #98), at
 * density 1 (dp == px). The expected values are the ticket's acceptance
 * numbers — the 2-settings.png re-measurement, not values recomputed
 * through [SwitchMath].
 */
class SwitchMathTest {

    // ------------------------------------------------------------- geometry

    @Test
    fun thumbLeftAtDensity1() {
        // LTR: OFF 2, ON 24, halfway 13 (travel 22).
        assertEquals(2f, SwitchMath.thumbLeft(0f, 63f, 2f, 37f, rtl = false), 0f)
        assertEquals(24f, SwitchMath.thumbLeft(1f, 63f, 2f, 37f, rtl = false), 0f)
        assertEquals(13f, SwitchMath.thumbLeft(0.5f, 63f, 2f, 37f, rtl = false), 0f)
    }

    @Test
    fun thumbLeftIsMirroredInRtl() {
        // RTL mirrors UISwitch: ON is on the left, OFF on the right.
        assertEquals(24f, SwitchMath.thumbLeft(0f, 63f, 2f, 37f, rtl = true), 0f)
        assertEquals(2f, SwitchMath.thumbLeft(1f, 63f, 2f, 37f, rtl = true), 0f)
    }

    @Test
    fun thumbLeftClampsOutOfRangeFractions() {
        assertEquals(2f, SwitchMath.thumbLeft(-1f, 63f, 2f, 37f, rtl = false), 0f)
        assertEquals(24f, SwitchMath.thumbLeft(2f, 63f, 2f, 37f, rtl = false), 0f)
        assertEquals(24f, SwitchMath.thumbLeft(-0.5f, 63f, 2f, 37f, rtl = true), 0f)
    }

    @Test
    fun dragFractionAddsMovementOverTheTravel() {
        // Halfway after 11 px of a 22 px travel.
        assertEquals(0.5f, SwitchMath.dragFraction(0f, 11f, 22f, rtl = false), 0f)
        // RTL negates the movement: a leftward drag turns the switch on.
        assertEquals(0.5f, SwitchMath.dragFraction(0f, -11f, 22f, rtl = true), 0f)
    }

    @Test
    fun dragFractionClampsToTheTrack() {
        // RTL with a rightward drag clamps at OFF.
        assertEquals(0f, SwitchMath.dragFraction(0f, 11f, 22f, rtl = true), 0f)
        // A big drag clamps at ON.
        assertEquals(1f, SwitchMath.dragFraction(0.9f, 50f, 22f, rtl = false), 0f)
        assertEquals(0f, SwitchMath.dragFraction(0.1f, -50f, 22f, rtl = false), 0f)
    }

    @Test
    fun releaseValueCommitsAtAndPastHalfway() {
        assertTrue(SwitchMath.releaseValue(0.5f))
        assertFalse(SwitchMath.releaseValue(0.49f))
        assertFalse(SwitchMath.releaseValue(0f))
        assertTrue(SwitchMath.releaseValue(1f))
    }

    // --------------------------------------------------------------- tokens

    @Test
    fun travelIsWidthMinusTwoInsetsMinusThumb() {
        assertEquals(22f, RobocoSwitchTokens.Travel.value, 0f)
    }

    @Test
    fun slideSpringIsSettleMatchedAt539() {
        // 0.35 s, ζ 0.85: ω = ln(1000)/(ζ·T), stiffness = ω² (FOUNDATION-D1).
        assertEquals(539.14f, RobocoSwitchTokens.Slide.stiffness, 0.01f)
    }

    @Test
    fun liftSpringIsCriticallyDampedAt1364() {
        // 0.25 s, ζ 1: ω = 9.2334/T, stiffness = ω² (FOUNDATION-D1).
        assertEquals(1364.09f, RobocoSwitchTokens.Lift.stiffness, 0.01f)
    }

    @Test
    fun offTrackIsTheQuantisedSystemGrey() {
        // rgba(0x787880, 0.42): round(0.42·255) = 107 = 0x6B.
        assertEquals(0x6B787880, RobocoSwitchTokens.OffTrack.toArgb())
    }

    @Test
    fun toggleTagWrapsTheRowId() {
        // accessibilityIdentifier = "toggle-\(row.id)" (MoreViewController.swift:76).
        assertEquals("toggle-notify:enabled", toggleTag("notify:enabled"))
        assertEquals("toggle-groupByProject", toggleTag("groupByProject"))
    }
}
