package sh.roboco.android.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * One open text sheet: the payload, the title (the sheet's nav bar, "Select
 * Text" or the tool's name) and the variant (`mono` = the tool detail).
 * Plain strings — see [TextSheetController] for why a request is never
 * saved into a `Bundle`.
 */
@Immutable
data class TextSheetRequest(val text: String, val title: String, val mono: Boolean)

/**
 * Holds the one text sheet that may be showing at a time (ticket 127).
 *
 * **One at a time.** iOS's `present` on a view controller that is already
 * presenting does nothing, so while a request is current — including its
 * dismiss animation, until [onDismissed] clears it — every new
 * [selectText]/[showDetail] returns `false` and is ignored. In practice
 * the scrim already blocks the transcript.
 *
 * **Not saved across activity recreation**: the text can be a whole tool
 * output, and a saved-state `Bundle` would risk
 * `TransactionTooLargeException`. After a recreation the sheet is simply
 * gone (the ticket's Comments record this; iOS keeps presented sheets
 * across rotation, and that is the known gap).
 */
@Stable
class TextSheetController {

    /** The showing request, or null when idle. */
    var current: TextSheetRequest? by mutableStateOf(null)
        private set

    /**
     * The row menu's "Select Text" (ticket 128 calls this). Ignored while
     * a sheet is showing.
     *
     * @return `true` when the request was taken, `false` when ignored.
     */
    fun selectText(text: String): Boolean =
        show(TextSheetRequest(text, SelectTextDefaults.Title, mono = false))

    /**
     * The `Detail` widget's mono tool-detail sheet. Ignored while a sheet
     * is showing.
     *
     * @return `true` when the request was taken, `false` when ignored.
     */
    fun showDetail(title: String, text: String): Boolean =
        show(TextSheetRequest(text, title, mono = true))

    /** Presents [request]; `false` (ignored) while one is already showing. */
    fun show(request: TextSheetRequest): Boolean {
        if (current != null) return false
        current = request
        return true
    }

    /**
     * GlassSheet's `onDismiss` — the sheet reached `Hidden` and its
     * animation is done, so the slot is free again.
     */
    internal fun onDismissed() {
        current = null
    }
}

/** The session screen's one controller (remembered, never saved). */
@Composable
fun rememberTextSheetController(): TextSheetController = remember { TextSheetController() }

/**
 * Mounts [TextSheetController]'s current request as a [SelectTextSheet] —
 * the Android port of `findViewController()?.present(…)` over the whole
 * session screen. Host it as the **last child of the screen root** so it
 * covers the header, the transcript and the bottom stack.
 *
 * `key(request)` starts every request with a fresh sheet and field state.
 * The focus clear + IME hide runs first (UIKit's presentation resigns the
 * composer's first responder implicitly — this is the explicit ADAPT), and
 * it beats the sheet's own first-frame focus request because the sheet's
 * effect waits a frame.
 */
@Composable
fun TextSheetOverlay(controller: TextSheetController) {
    val request = controller.current ?: return
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    key(request) {
        LaunchedEffect(Unit) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        SelectTextSheet(
            text = request.text,
            onDismiss = { controller.onDismissed() },
            title = request.title,
            mono = request.mono,
        )
    }
}
