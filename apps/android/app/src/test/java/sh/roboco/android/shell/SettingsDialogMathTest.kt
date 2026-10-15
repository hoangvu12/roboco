package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure half of the tablet Settings dialog (android-ui-parity #104):
 * the card size clamp (540 × 620 in a 1280 × 800 window, 512 × 552 in a
 * 560 × 600 one, floored at 0), the scale/alpha mapping of the present
 * motion, the predictive-back offset, and the form-sheet spring's
 * settle-matched stiffness (FOUNDATION-D1: (9.2334/0.4)² = 532.85).
 *
 * Plain JVM — no Robolectric, no Compose. Written per the test policy; the
 * batch/VPS run executes it.
 */
class SettingsDialogMathTest {

    @Test
    fun cardSizeAtTheReferenceWindowIs540x620() {
        assertEquals(540f to 620f, SettingsDialogMath.cardSize(1280f, 800f))
    }

    @Test
    fun cardSizeClampsToTheWindowMinus48() {
        assertEquals(512f to 552f, SettingsDialogMath.cardSize(560f, 600f))
    }

    @Test
    fun cardSizeFloorsAtZero() {
        assertEquals(0f to 0f, SettingsDialogMath.cardSize(40f, 40f))
    }

    @Test
    fun scaleRunsFrom0_94To1UnderFullMotion() {
        assertEquals(0.94f, SettingsDialogMath.scale(0f, false), 0f)
        assertEquals(1f, SettingsDialogMath.scale(1f, false), 0f)
        // The AC's midpoint reference: b = 0.5 → p = 0.875 → scale 0.9925.
        assertEquals(0.9925f, SettingsDialogMath.scale(0.875f, false), 1e-4f)
    }

    @Test
    fun scaleStays1UnderReducedMotion() {
        assertEquals(1f, SettingsDialogMath.scale(0.5f, true), 0f)
    }

    @Test
    fun alphaClampsTo01() {
        assertEquals(0f, SettingsDialogMath.alpha(-0.5f), 0f)
        assertEquals(1f, SettingsDialogMath.alpha(1.5f), 0f)
        assertEquals(0.5f, SettingsDialogMath.alpha(0.5f), 0f)
    }

    @Test
    fun backOffsetMapsGestureProgressToAQuarterOfTheHeight() {
        assertEquals(155f, SettingsDialogMath.backOffset(0.5f, 1240f), 1e-4f)
        assertEquals(0f, SettingsDialogMath.backOffset(0f, 1240f), 1e-4f)
        assertEquals(310f, SettingsDialogMath.backOffset(1f, 1240f), 1e-4f)
    }

    @Test
    fun theFormSheetSpringIsCriticallyDampedAndSettleMatched() {
        assertEquals(532.85f, SettingsDialogDefaults.Spring.stiffness, 1e-2f)
    }
}
