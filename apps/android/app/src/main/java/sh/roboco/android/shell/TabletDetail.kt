package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.roboco.android.newsession.NewSessionScreen

/**
 * The embedded new-session page in the detail column (android-ui-parity
 * #106) — `NewSessionViewController(embedded: true)` (ticket 93): no ✕, no
 * grabber, the inline title `New Session` on the page root.
 *
 * **Identity equality on purpose** (not a data class): `showDraft` always
 * rebuilds, even when a draft is already shown (iOS
 * `SplitRootController.swift:8`), and ticket 24's `setRoutes` reuse logic
 * must never recycle a draft entry.
 *
 * [paneTitle] stays null: 93's embedded page root already sets
 * `semantics { paneTitle = "New Session" }` (93 §Accessibility), so a
 * route-level pane title here would be a second, nested pane announcing
 * the same text.
 */
class DraftDetailRoute(
    val prompt: String?,
    /**
     * One-shot: 93 focuses the composer in its first `onDidAppear` only.
     * Launch and the open-error recovery pass `false`, `presentNewSession`
     * (⌘N) passes `true`.
     */
    val focusOnAppear: Boolean,
    /** 94's create + send: fires with the captured handoff (or null). */
    val onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit,
) : NavRoute {

    /** 93's page, embedded. */
    @Composable
    fun Content() {
        NewSessionScreen(
            prompt = prompt,
            embedded = true,
            onCreated = onCreated,
            focusOnAppear = focusOnAppear,
        )
    }
}

/**
 * The column let go of its session while collapsed (108's `clear()`; iOS
 * `setViewControllers([])`, `SplitRootController.swift:113`). Draws only
 * the pane background — the host paints `Palette.background`.
 */
data object EmptyDetailRoute : NavRoute

/**
 * Renders the split's detail column (android-ui-parity #106): ticket 24's
 * host over the router's **persistent** [NavStack]. Every detail change is
 * an unanimated `setRoutes`, so the old root gets
 * `onDidDisappear(removed = true)` and its entry `close()`s (a keyed
 * rebuild would skip the teardown and keep the replaced session attached
 * and streaming). Never rebuild the stack by `key`.
 */
@Composable
fun TabletDetailHost(stack: NavStack, modifier: Modifier = Modifier) {
    NavStackHost(stack = stack, active = true, modifier = modifier) { entry ->
        TabletDetailContent(entry.route)
    }
}

/**
 * The detail column's route table: the embedded draft (93), the empty
 * column (108), a session (76's [SessionRoute] `Content` — the NavEntry
 * close safety net and 75's SessionScreen live there), and everything the
 * shared shell table renders for the 108-era pushed routes.
 */
@Composable
internal fun TabletDetailContent(route: NavRoute) = when (route) {
    is DraftDetailRoute -> route.Content()
    EmptyDetailRoute -> Unit
    // 76's routes render through their own Content, exactly as
    // ShellRouteContent's `is SessionScreenRoute ->` branch does; the
    // interface branch also serves the test fakes' routes.
    is SessionRoute -> route.Content()
    else -> ShellRouteContent(route)
}
