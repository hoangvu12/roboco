package sh.roboco.android.newsession

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import sh.roboco.android.shell.LocalPageSheetScope
import sh.roboco.android.shell.LocalSheetHost

/**
 * The "New Project…" entry point (android-ui-parity #113) — the seam over
 * how the folder browser is presented, the port of the Swift closure that
 * builds a `NewProjectViewController` and presents it
 * (`ios:Session/NewSessionViewController.swift:311-316`).
 *
 * The production implementation is [rememberNewProjectPresenter]; the
 * gallery and the tests install their own (a fake that captures
 * `onCreated`), which is the only way to observe the callback the real
 * browser calls before dismissing itself (ticket 111 step 7).
 */
fun interface NewProjectPresenter {

    /**
     * Presents the folder browser; when (and only when) a project is
     * created, calls [onCreated] with the new space's id **before** the
     * browser dismisses itself. The caller keeps the page's liveness
     * guard — after the page is gone the callback is inert.
     */
    fun present(onCreated: (spaceId: String) -> Unit)
}

/**
 * The real presenter, from the New Session page's composition:
 *
 * - **phone** (the page is 31's sheet content): `LocalPageSheetScope` is
 *   the New Session entry's own scope, so ticket 110's
 *   [PageSheetScope.presentNewProject] stacks the browser on the New
 *   Session sheet — key `new-project`, a large page sheet with no
 *   grabber, exactly UIKit's stacked `UINavigationController` present;
 * - **tablet** (106 embeds the page in the detail column): no sheet
 *   scope, so the browser is presented from the root
 *   [LocalSheetHost] through 110's `SheetHostState` overload — same key,
 *   same style, centred by 31's tablet layout;
 * - before either, the composer is blurred and the IME hidden (UIKit's
 *   modal presentation takes the keyboard away; ticket 93 never
 *   re-focuses on return);
 * - a refused present (a covered sheet, a busy root) is a `Log.w`, not
 *   an error — nothing else changes.
 */
@Composable
fun rememberNewProjectPresenter(): NewProjectPresenter {
    val sheet = LocalPageSheetScope.current
    val host = LocalSheetHost.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(sheet, host, focus, keyboard) {
        NewProjectPresenter { onCreated ->
            focus.clearFocus(force = true)
            keyboard?.hide()
            val shown = sheet?.presentNewProject(onCreated)
                ?: host?.presentNewProject(onCreated)
                ?: false
            if (!shown) Log.w("NewSession", "new project sheet not presented")
        }
    }
}
