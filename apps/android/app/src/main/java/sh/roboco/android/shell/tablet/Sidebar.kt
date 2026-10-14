package sh.roboco.android.shell.tablet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.sessions.LocalSessionListOpener
import sh.roboco.android.sessions.SessionListOpener
import sh.roboco.android.sessions.SessionsScreen
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.NavEntryEffect
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.ShellRouteContent

/**
 * Ticket 33's sidebar slot content (android-ui-parity #105) — the port of
 * iOS `SidebarViewController` (`ios:Shell/SplitRootController.swift:194-298`)
 * with 107's bottom toolbar filling its slot:
 *
 * - the phone's Sessions front page (46) inside a **sidebar-owned
 *   [NavStack]** — folder pages (Pinned, a section, Archived) push inside
 *   it, with the large title "Sessions" on and **no** nav ⋯
 *   (`showsNavOptions = false`: iOS `list.navigationItem.rightBarButtonItem =
 *   nil`, `:253` — the options menu moves to 107's toolbar);
 * - the stacked, always-visible glass search field (this ticket's
 *   [SidebarSearchField]) whose raw text feeds 46's `query` filter;
 * - `currentChatId` (33's `TabletShellState`) down to the rows, so the
 *   session open in the detail column is drawn `rowActive`;
 * - row taps replace the detail column and never push here:
 *   [LocalSessionListOpener] is provided **around the host** (not just the
 *   root) so a row tapped on a sidebar folder page opens beside the
 *   sidebar too — it hides the IME and clears focus first, keeping the
 *   text (the Android ADAPT: the soft keyboard must not cover the session
 *   that just opened);
 * - [LocalBottomChrome] around the whole host gives the root **and** the
 *   pushed folder pages the toolbar-clearing bottom padding
 *   (`n + 12 + 44`, 107's `reservedBottom`).
 *
 * [toolbar] is 107's overlay slot: drawn **outside** the `NavStackHost`
 * (UIKit cross-fades `toolbarItems` in place; a toolbar inside the root
 * entry would slide away on push). It receives the sidebar's [NavStack]
 * instance — 107's options menu must bind the sidebar stack, and
 * `LocalNavStack` is not provided outside the host — composed inside a
 * full-width, [SidebarChromeMetrics.ToolbarHeight]-high placement whose
 * bottom sits `barBottom` above the window bottom, and sampling the host
 * through [LocalHazeState] (the slot's glass blurs the list scrolling
 * under it; the toolbar is a sibling **after** the `hazeSource`, never
 * inside the source it samples — 07's rule).
 *
 * `shown` is 33's target visibility: `active = shown` turns the stack's
 * predictive-back handler off while the sidebar is hidden, so system back
 * never pops a folder the user cannot see.
 *
 * @param state 108's `ShellStates.sidebar` in production — never
 *   `remember` one inside `SplitRoot`'s slot (35 swaps it out below
 *   840 dp). `rememberSidebarState()` serves previews, tests and the
 *   gallery.
 * @param currentChatId 33's `TabletShellState.currentChatId` — the row
 *   open in the detail column.
 * @param router the shell's router (the tablet implementation is 106);
 *   `openSession` replaces the detail column.
 * @param topInset the status-bar inset; tests pass 24 dp explicitly
 *   because Robolectric reports no insets.
 * @param toolbar 107's `SidebarToolbar` overlay — the bottom toolbar's
 *   `[gear ⋯] · +` row; the placeholder is gone since 107 landed.
 */
@Composable
fun Sidebar(
    state: SidebarState,
    currentChatId: String?,
    shown: Boolean,
    modifier: Modifier = Modifier,
    router: AppRouter = LocalAppRouter.current,
    topInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    toolbar: @Composable BoxScope.(stack: NavStack) -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A row tap (root, results or a folder page): hide the IME, clear the
    // focus (the text and results are kept), then replace the detail
    // column. The sidebar stack never changes.
    val opener = remember(router, keyboard, focus) {
        SessionListOpener { id ->
            keyboard?.hide()
            focus.clearFocus(force = true)
            router.openSession(id)
        }
    }
    val insets = rememberSidebarBottomInsets()
    // 107: the haze state the toolbar's capsule blurs — the host (the
    // list, the search field, the pushed folders) is its one source,
    // exactly like 26's tab bar around the tab content.
    val toolbarHaze = rememberHazeState()
    CompositionLocalProvider(
        LocalSessionListOpener provides opener,
        LocalBottomChrome provides insets,
    ) {
        Box(modifier.fillMaxSize().testTag("sidebar")) {
            // The host is the one hazeSource of the toolbar's state; the
            // toolbar itself composes after it, outside the source it
            // samples. The tablet sidebar passes no bottomChrome to the
            // host (24 §Bottom chrome).
            Box(Modifier.fillMaxSize().hazeSource(toolbarHaze)) {
                NavStackHost(
                    stack = state.stack,
                    active = shown,
                    modifier = Modifier.fillMaxSize(),
                ) { entry ->
                    when (entry.route) {
                        SidebarRootRoute -> SidebarRoot(state, currentChatId, topInset)
                        // 45's folder pages — nothing else is ever pushed here.
                        else -> ShellRouteContent(entry.route)
                    }
                }
            }
            // 107's toolbar slot: full-width, SidebarChromeMetrics.ToolbarHeight
            // high, its bottom barBottom above the window bottom — the
            // placement is this ticket's (105's single source), the toolbar
            // adds no offset of its own.
            CompositionLocalProvider(LocalHazeState provides toolbarHaze) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = insets.barBottom)
                        .height(SidebarChromeMetrics.ToolbarHeight),
                ) {
                    toolbar(state.stack)
                }
            }
        }
    }
}

/**
 * The sidebar stack's root: the front page with the search field wired in
 * — iOS's `SessionsViewController` inside `MainTabController.nav(list)`
 * (large titles on, unlike the detail column). `query` is a snapshot read
 * of [SidebarState.query], so every edit recomposes this screen and feeds
 * the filter.
 */
@Composable
private fun SidebarRoot(state: SidebarState, currentChatId: String?, topInset: Dp) {
    val focus = LocalFocusManager.current
    SessionsScreen(
        showsNavOptions = false,
        currentChatId = currentChatId,
        listState = state.listState,
        query = state.query,
        topInset = topInset,
        pinnedAccessory = { SidebarSearchField(state, Modifier.fillMaxSize()) },
        pinnedAccessoryHeight = SidebarSearchMetrics.BandHeight,
    )
    // A folder pushed over the root drops the field's focus (UIKit resigns
    // the field when its controller leaves the screen).
    NavEntryEffect(onWillDisappear = { _, _ -> focus.clearFocus(force = true) })
}
