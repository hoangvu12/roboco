package sh.roboco.android.design

import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure alert rules (ticket 18): the derived roles, the display order,
 * the stacked decision, the `requiresText` enable rule, the UIKit field
 * options, the test-tag mapping and this ticket's Motion tokens. Plain
 * JUnit — no Robolectric runner.
 */
class AlertRulesTest {

    private val ok = AlertButton("OK")
    private val cancel = AlertButton("Cancel", role = AlertButtonRole.Cancel)
    private val create = AlertButton("Create")

    // ------------------------------------------------------------- stacked

    @Test
    fun isStackedIsFalseForTwoFittingTitles() {
        // The capture: "Cancel" 51.7 dp and "Create" 50.3 dp at 320 — fits.
        assertFalse(isStacked(2, listOf(51.7f, 50.3f), 320f))
    }

    @Test
    fun isStackedIsTrueForATitleWiderThanTheHalfCapsule() {
        // 108.5 > 108 (the label budget at 320: (320 − 2·16 − 8)/2 − 2·16).
        assertTrue(isStacked(2, listOf(108.5f, 40f), 320f))
    }

    @Test
    fun isStackedIsTrueForThreeButtonsWhateverTheWidths() {
        assertTrue(isStacked(3, listOf(10f, 10f, 10f), 320f))
    }

    @Test
    fun isStackedIsFalseForASingleButton() {
        assertFalse(isStacked(1, listOf(200f), 320f))
    }

    @Test
    fun theLabelBudgetIs108DpAtCardWidth320() {
        assertEquals(108f, sideBySideLabelMaxDp(320f), 1e-4f)
    }

    // -------------------------------------------------------- display order

    @Test
    fun sideBySidePutsTheCancelButtonLeadingWhateverTheOrder() {
        // [Rename(Default), Cancel] → Cancel first.
        assertEquals(
            listOf(1, 0),
            displayOrder(listOf(AlertButton("Rename"), cancel), stacked = false),
        )
        // [Cancel, Create] → already leading.
        assertEquals(
            listOf(0, 1),
            displayOrder(listOf(cancel, create), stacked = false),
        )
        // No cancel button: list order.
        assertEquals(
            listOf(0, 1),
            displayOrder(listOf(AlertButton("A"), AlertButton("B")), stacked = false),
        )
    }

    @Test
    fun stackedKeepsListOrderWithTheCancelButtonLast() {
        // [Cancel, A, B] → [A, B, Cancel].
        assertEquals(
            listOf(1, 2, 0),
            displayOrder(
                listOf(cancel, AlertButton("A"), AlertButton("B")),
                stacked = true,
            ),
        )
        // Cancel listed second: moved to the end.
        assertEquals(
            listOf(0, 2, 1),
            displayOrder(
                listOf(AlertButton("A"), cancel, AlertButton("B")),
                stacked = true,
            ),
        )
    }

    @Test
    fun aSingleButtonDisplaysAsItself() {
        assertEquals(listOf(0), displayOrder(listOf(ok), stacked = false))
        assertEquals(listOf(0), displayOrder(listOf(ok), stacked = true))
    }

    // ------------------------------------------------------------ the roles

    @Test
    fun primaryIsTheLastNonCancelButton() {
        assertEquals(1, primaryIndex(listOf(cancel, create)))
        assertEquals(0, primaryIndex(listOf(create, cancel)))
        assertNull(primaryIndex(listOf(cancel)))
        assertEquals(0, primaryIndex(listOf(ok)))
    }

    @Test
    fun theCancelActionIsTheCancelButtonElseASingleButtonElseNothing() {
        assertEquals(0, cancelActionIndex(listOf(ok)))
        assertEquals(1, cancelActionIndex(listOf(create, cancel)))
        assertNull(cancelActionIndex(listOf(AlertButton("A"), AlertButton("B"))))
        assertNull(cancelActionIndex(listOf()))
    }

    // ---------------------------------------------------------- requiresText

    @Test
    fun requiresTextDisablesOnEmptyAndWhitespaceOnlyText() {
        val button = AlertButton("Create", requiresText = true)
        assertFalse(isEnabled(button, hasField = true, fieldText = ""))
        assertFalse(isEnabled(button, hasField = true, fieldText = " \n"))
        assertFalse(isEnabled(button, hasField = true, fieldText = "   "))
        assertTrue(isEnabled(button, hasField = true, fieldText = "a"))
        assertTrue(isEnabled(button, hasField = true, fieldText = " ../outside "))
    }

    @Test
    fun requiresTextIsIgnoredWithoutAField() {
        val button = AlertButton("Create", requiresText = true)
        assertTrue(isEnabled(button, hasField = false, fieldText = ""))
    }

    @Test
    fun buttonsWithoutRequiresTextAreAlwaysEnabled() {
        assertTrue(isEnabled(AlertButton("OK"), hasField = true, fieldText = ""))
    }

    // ------------------------------------------------------ field options

    @Test
    fun theFieldOptionsDefaultToUitextField() {
        val options = AlertField().keyboardOptions()
        assertEquals(KeyboardCapitalization.Sentences, options.capitalization)
        assertEquals(true, options.autoCorrectEnabled)
        assertEquals(KeyboardType.Text, options.keyboardType)
        assertEquals(ImeAction.Done, options.imeAction)
    }

    @Test
    fun theFieldOptionsCarryTheCallersCapitalizationAndAutocorrect() {
        // The New Folder prompt (PR #685): no capitalization, no autocorrect.
        val options = AlertField(
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ).keyboardOptions()
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertEquals(false, options.autoCorrectEnabled)
    }

    // ---------------------------------------------------------- test tags

    @Test
    fun theTagsMarkPrimaryCancelAndIndexButtons() {
        val buttons = listOf(AlertButton("A"), AlertButton("B"), ok)
        assertEquals("alert-primary", alertButtonTag(2, buttons))
        assertEquals("alert-button-0", alertButtonTag(0, buttons))
        assertEquals("alert-button-1", alertButtonTag(1, buttons))
    }

    @Test
    fun aCancelButtonThatIsNotPrimaryCarriesTheCancelTag() {
        val buttons = listOf(cancel, create)
        assertEquals("alert-cancel", alertButtonTag(0, buttons))
        assertEquals("alert-primary", alertButtonTag(1, buttons))
    }

    @Test
    fun aSingleButtonCarriesOnlyThePrimaryTag() {
        // It is both the primary and the cancel action; primary wins.
        assertEquals("alert-primary", alertButtonTag(0, listOf(ok)))
        // Even a lone Cancel button: the a11y table says a single button
        // carries only alert-primary.
        assertEquals("alert-primary", alertButtonTag(0, listOf(cancel)))
    }

    // -------------------------------------------------- this ticket's motion

    @Test
    fun theAlertMotionTokensMatchTheTicket() {
        val fadeIn: TweenSpec<Float> = Motion.alertFadeIn()
        assertEquals(250, fadeIn.durationMillis)
        assertEquals(0, fadeIn.delay)
        assertEquals(Motion.EaseOut, fadeIn.easing)

        val fadeOut: TweenSpec<Float> = Motion.alertFadeOut()
        assertEquals(200, fadeOut.durationMillis)
        assertEquals(0, fadeOut.delay)
        assertEquals(Motion.EaseInOut, fadeOut.easing)

        // IosSpring("alertScaleIn", 0.40, 1f): critically damped, settle-matched
        // (9.2334/0.4)² = 532.85.
        assertEquals("alertScaleIn", Motion.AlertScaleIn.name)
        assertEquals(0.40, Motion.AlertScaleIn.durationSec, 1e-9)
        assertEquals(1f, Motion.AlertScaleIn.damping, 0f)
        assertEquals(532.85, Motion.AlertScaleIn.stiffness.toDouble(), 0.01)
    }
}
