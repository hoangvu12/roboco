package sh.roboco.android.design

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * The pure alert rules (ticket 18): the derived roles, the display order,
 * the stacked decision, the `requiresText` enable rule, the UIKit field
 * options and the test-tag mapping — everything that needs no composition.
 * `AlertCard.kt` and `Alert.kt` call these; `AlertRulesTest` pins them.
 *
 * Indexes are **list order** (the order of [RobocoAlert.buttons]); only the
 * *placement* reorders (see [displayOrder]).
 */

/**
 * The measured card metrics (dp; `ios-name-error.png`, PR #685, 402 pt
 * screen). One place for the rules' math and the card's layout.
 */
internal object AlertMetrics {
    /** The card width at 320 dp (measured); `min(320, placementWidth)`. */
    const val CardWidthDp = 320f

    /** Continuous corner radius (measured ≈ 35.7 + tails). */
    const val CardRadiusDp = 36f

    /** The title/message block's start/end inset (measured 30). */
    const val TextInsetDp = 30f

    /** The title block's top padding (baseline lands ≈ 39 dp below the top). */
    const val TextTopDp = 22f

    /** Title line box → message line box (measured 5). */
    const val TitleToMessageGapDp = 5f

    /** Last text line → field (measured 15.9). */
    const val TextToFieldGapDp = 16f

    /** Field (or the last text line with no field) → button area. */
    const val TextToButtonsGapDp = 20f

    /** The field's and the button row's inset from the card edges. */
    const val ControlInsetDp = 16f

    /** The inner start/end padding of the field and of a button title. */
    const val ControlInnerDp = 16f

    /** Min height of the field and of every button capsule. */
    const val ControlMinHeightDp = 48f

    /** Gap between the two side-by-side capsules, and between stacked ones. */
    const val ControlGapDp = 8f

    /** The progress bar's height (android-next#04, the mockup's 6 px bar). */
    const val ProgressHeightDp = 6f

    /** The gap between the progress bar and its label. */
    const val ProgressLabelGapDp = 6f
}

/**
 * **primary**: the *last* button in list order whose role is not
 * [AlertButtonRole.Cancel]; `null` when every button is Cancel. IME Done
 * fires this one, and it carries the `alert-primary` test tag.
 */
internal fun primaryIndex(buttons: List<AlertButton>): Int? =
    buttons.indexOfLast { it.role != AlertButtonRole.Cancel }.takeIf { it >= 0 }

/**
 * **cancel action**: the Cancel button if present; else, with exactly one
 * button, that button (the acknowledgement "OK"); else `null`. Back and
 * scrim taps run this action's handler — "Not Now" reloads on every cancel
 * path (`ios:Shell/MoreViewController.swift:159-165`).
 */
internal fun cancelActionIndex(buttons: List<AlertButton>): Int? =
    buttons.indexOfFirst { it.role == AlertButtonRole.Cancel }.takeIf { it >= 0 }
        ?: if (buttons.size == 1) 0 else null

/**
 * The label budget of one side-by-side capsule: half the button area minus
 * the title padding — `(cardWidth − 2·16 − 8)/2 − 2·16` = **108 dp** at card
 * width 320. A title wider than this forces the stacked layout.
 */
internal fun sideBySideLabelMaxDp(cardWidthDp: Float): Float =
    (cardWidthDp - 2f * AlertMetrics.ControlInsetDp - AlertMetrics.ControlGapDp) / 2f -
        2f * AlertMetrics.ControlInnerDp

/**
 * **stacked**: three or more buttons, or two whose titles don't fit their
 * half-capsule. UIKit stacks full width when a title doesn't fit its half
 * (`preferredStyle: .alert` layout rule).
 *
 * @param titleWidthsDp each title's single-line width in **its own style** (dp)
 */
internal fun isStacked(count: Int, titleWidthsDp: List<Float>, cardWidthDp: Float): Boolean {
    if (count >= 3) return true
    if (count != 2) return false
    val max = sideBySideLabelMaxDp(cardWidthDp)
    return titleWidthsDp.any { it > max }
}

/**
 * **display order** (placement order, left to right / top to bottom): two
 * side-by-side buttons put the Cancel button first (leading) whatever the
 * add order; stacked buttons keep list order with the Cancel button moved
 * to the end; a single button is itself. UIKit reorders the same way.
 */
internal fun displayOrder(buttons: List<AlertButton>, stacked: Boolean): List<Int> {
    if (buttons.size <= 1) return buttons.indices.toList()
    if (stacked) {
        // Stable sort: list order with the Cancel button (at most one) last.
        return buttons.indices.sortedBy { buttons[it].role == AlertButtonRole.Cancel }
    }
    val cancel = buttons.indexOfFirst { it.role == AlertButtonRole.Cancel }
    return if (cancel >= 0) {
        listOf(cancel) + buttons.indices.filter { it != cancel }
    } else {
        buttons.indices.toList()
    }
}

/**
 * A button is disabled while it requires text and the trimmed field text is
 * empty (PR #685's `Create`). The flag is ignored with no field. Kotlin
 * `trim()` drops whitespace and newlines, like `.whitespacesAndNewlines`.
 */
internal fun isEnabled(button: AlertButton, hasField: Boolean, fieldText: String): Boolean =
    !(button.requiresText && hasField && fieldText.trim().isEmpty())

/**
 * The field's [KeyboardOptions] from the `UITextField` options the call
 * sites set: the capitalization and autocorrect the caller asked for, a
 * plain text keyboard, and Done (the host's IME rule fires primary).
 * Defaults match `UITextField`: `Sentences`, autocorrect on.
 */
internal fun AlertField.keyboardOptions(): KeyboardOptions = KeyboardOptions(
    capitalization = capitalization,
    autoCorrectEnabled = autocorrect,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Done,
)

/**
 * The test tag of the button at [index] (list order): the primary carries
 * `alert-primary`, a Cancel-role button `alert-cancel`, anything else
 * `alert-button-<index>`. A single button is both primary and cancel
 * action and carries only `alert-primary`.
 */
internal fun alertButtonTag(index: Int, buttons: List<AlertButton>): String {
    // A single button is both the primary and the cancel action, and carries
    // only the primary tag — even a lone Cancel button (primary is null when
    // every button is Cancel, so the check alone would miss it).
    if (buttons.size == 1) return "alert-primary"
    if (index == primaryIndex(buttons)) return "alert-primary"
    return when (buttons[index].role) {
        AlertButtonRole.Cancel -> "alert-cancel"
        else -> "alert-button-$index"
    }
}
