package sh.roboco.android.composer

import androidx.compose.ui.platform.SoftwareKeyboardController
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.session.StatusBanner

/**
 * The UI seam of the queued-message edit (android-ui-parity #83): the
 * composer, the placeholder, the attach gate, the focus/keyboard pair,
 * the "can't edit" alert and the two failure-slot calls into ticket 73's
 * [StatusBanner]. An interface so the state machine is fully testable
 * with a plain fake.
 */
interface QueueEditHost {
    /** The composer's text: a set replaces it with the caret at the end, mention tokens untouched (ticket 54). */
    var text: String

    /** The composer's staged images: a direct set has no cap (the stash restore). */
    var images: List<StagedImage>

    /** The composer's placeholder (`Edit queued message` while editing). */
    var placeholder: String

    /** The `+` attach gate: `false` while an edit is open (the zeron #277 rule). */
    var attachEnabled: Boolean

    /** `chrome.placeholder` right now (`Message <harness>`). */
    val normalPlaceholder: String

    /** Focuses the composer and raises the IME (`becomeFirstResponder`, `SessionViewController.swift:416`). */
    fun focusComposer()

    /** The `Can't edit right now` alert (`:410-413`). */
    fun showCantEditAlert()

    /** Recovery copy into the sticky send-failure slot (the pill shows `Banner.Failed`). */
    fun showFailure(message: String)

    /** Clears the slot only if it still holds exactly [message]. */
    fun clearFailure(message: String)
}

/**
 * [QueueEditHost] over ticket 54's [ComposerController] — the production
 * adapter, constructed next to 78's `SessionComposerBinding` and held
 * with the same nav-entry lifetime as the controller.
 *
 * The four var members and [focusComposer] delegate straight to the
 * controller: `text` keeps the mention tokens alive (`Replaced` relies
 * on them resolving), `images` is a direct set (no cap — the stash
 * restore), and [attachEnabled] is the flag 55/87 grey out.
 *
 * [morph], [keyboard], [alerts] and [chromePlaceholder] are
 * composition-scoped: the host outlives the composition (it is
 * entry-retained with the controller), so 83's wiring calls
 * [refresh] on every recomposition — the same mechanism 78's binding
 * uses for its own `morph` and `dismissKeyboard`.
 */
class ComposerEditHost(
    private val composer: ComposerController,
    morph: ComposerMorphState,
    keyboard: () -> SoftwareKeyboardController?,
    alerts: () -> Alerts,
    private val banner: StatusBanner,
    chromePlaceholder: () -> String,
) : QueueEditHost {

    internal var morph: ComposerMorphState = morph
        private set

    internal var keyboard: () -> SoftwareKeyboardController? = keyboard
        private set

    internal var alerts: () -> Alerts = alerts
        private set

    internal var chromePlaceholder: () -> String = chromePlaceholder
        private set

    /** The wiring's per-recomposition refresh of the composition-scoped half. */
    internal fun refresh(
        morph: ComposerMorphState,
        keyboard: () -> SoftwareKeyboardController?,
        alerts: () -> Alerts,
        chromePlaceholder: () -> String,
    ) {
        this.morph = morph
        this.keyboard = keyboard
        this.alerts = alerts
        this.chromePlaceholder = chromePlaceholder
    }

    override var text: String
        get() = composer.text
        set(value) {
            composer.text = value
        }

    override var images: List<StagedImage>
        get() = composer.images
        set(value) {
            composer.images = value
        }

    override var placeholder: String
        get() = composer.placeholder
        set(value) {
            composer.placeholder = value
        }

    override var attachEnabled: Boolean
        get() = composer.attachEnabled
        set(value) {
            composer.attachEnabled = value
        }

    override val normalPlaceholder: String
        get() = chromePlaceholder()

    /**
     * `composer.becomeFirstResponder()`: 56's `morph.focus()` (a no-op if
     * the field already has focus, and while the question panel hides the
     * composer), then the IME — the field may already be focused with the
     * keyboard down.
     */
    override fun focusComposer() {
        morph.focus()
        keyboard()?.show()
    }

    /** The `Can't edit right now` alert through ticket 18's host (never a Material dialog). */
    override fun showCantEditAlert() {
        alerts().show(
            RobocoAlert(
                title = QueueEditCopy.AlertTitle,
                message = QueueEditCopy.AlertMessage,
                buttons = listOf(AlertButton(QueueEditCopy.AlertOk)),
            ),
        )
    }

    /** Recovery copy into 73's sticky send-failure slot — verbatim, no `Couldn't send: ` prefix. */
    override fun showFailure(message: String) = banner.noteFailureMessage(message)

    /** Clears the slot only if it still holds exactly [message] — a later genuine send failure is never wiped. */
    override fun clearFailure(message: String) = banner.clearFailureMessage(message)
}
