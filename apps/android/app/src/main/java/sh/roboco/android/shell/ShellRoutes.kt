package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import sh.roboco.android.accounts.AgentAccountsRoute
import sh.roboco.android.accounts.AgentAccountsScreen
import sh.roboco.android.browser.BrowserRoute
import sh.roboco.android.browser.BrowserScreen
import sh.roboco.android.browser.PreviewPanelRoute
import sh.roboco.android.browser.PreviewPanelScreen
import sh.roboco.android.settings.EngineDetailRoute
import sh.roboco.android.settings.EngineDetailScreen
import sh.roboco.android.settings.LicensesRoute
import sh.roboco.android.settings.LicensesScreen
import sh.roboco.android.session.SessionScreenRoute
import sh.roboco.android.settings.SettingsScreen
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.sessions.FolderRouteContent
import sh.roboco.android.sessions.SearchScreen
import sh.roboco.android.sessions.SessionsScreen
import sh.roboco.android.sidechats.SideChatDraftRoute
import sh.roboco.android.sidechats.SubagentRoute

/**
 * A tab's root route (android-ui-parity #26): the first entry of the
 * tab's [NavStack], what `popToRoot` returns to and what the placeholder
 * roots render until the real screens land. Ticket 49 (Search) and 46
 * (Sessions) have replaced their branches; 103 (Settings) replaces the
 * last placeholder.
 */
data class TabRootRoute(val tab: TabId) : NavRoute

/**
 * Settings pushed over the Sessions list (wave-10 37, upstream #967's
 * iPhone `showSettings`): the one-row bar's gear. The bar steps aside
 * (`hidesBottomBarWhenPushed`), the page gets the back chevron.
 */
data object SettingsRoute : NavRoute {
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = "Settings"
}

/**
 * A placeholder pushed page (android-ui-parity #26): "row N" of tab
 * `tab`, pushed by the placeholder roots so per-tab back stacks can be
 * exercised before the real screens exist.
 */
data class PlaceholderDetailRoute(val tab: TabId, val row: Int) : NavRoute

/**
 * The shell's route table: `when (route)` over every route a tab's stack
 * can hold. Tickets 46/103/49 add their branches (replacing the
 * `TabRootRoute` branch per tab); 45 registers the folder route; 76
 * registers the Session route; 101 registers the Licenses screen the
 * Settings "About" row pushes; 102 registered the Engine detail
 * route and 140 fills in its page.
 */
@Composable
fun ShellRouteContent(route: NavRoute) {
    when (route) {
        is TabRootRoute -> when (route.tab) {
            // Ticket 46: the Sessions tab's real root.
            // Wave-10 37: the one-row bar's field filters this list in
            // place (the sidebar's query path); "" under the tab shell.
            TabId.Sessions -> LocalBottomBarSearch.current.let { search ->
                SessionsScreen(query = search?.query ?: "", filters = search != null)
            }
            // Ticket 49: the Search tab's root — the model + list state
            // live on the entry, so the query, results and scroll survive
            // tab switches (29 refocuses the field on re-entry).
            // The shell's `tab-root-<id>` tag (26's placeholder contract, kept
            // by 46's SessionsScreen) goes on a wrapper: SearchScreen tags
            // its own node `search-screen`, and two testTags on one node
            // would leave only one of them.
            TabId.Search -> Box(Modifier.fillMaxSize().testTag("tab-root-search")) {
                SearchScreen()
            }
            // Ticket 103: the Settings tab's real root — the Compose
            // port of MoreViewController (the six grouped sections under
            // the large title). SettingsScreen tags its own node
            // `settings-screen`, so the shell's `tab-root-<id>` tag (26's
            // placeholder contract, kept by 46's and 49's roots) goes on a
            // wrapper Box: two testTags on one node would leave only one.
            TabId.Settings -> Box(Modifier.fillMaxSize().testTag("tab-root-more")) {
                SettingsScreen()
            }
        }
        is SettingsRoute -> Box(Modifier.fillMaxSize().testTag("tab-root-more")) {
            SettingsScreen()
        }
        is PlaceholderDetailRoute -> PlaceholderDetail(route)
        is LicensesRoute -> LicensesScreen()
        // Ticket 45: one folder's sessions (Pinned, a user section,
        // Archived). The tablet sidebar (105) renders pushed routes
        // through this same table, so one branch serves both hosts.
        is FolderRoute -> FolderRouteContent(route)
        // Ticket 76: the Session screen — the route the factory built and
        // 32/41/106 pushed (its own Content: the NavEntry close safety
        // net + 75's SessionScreen). Ticket 106's detail column falls
        // through to this same table.
        is SessionScreenRoute -> route.Content()
        // android-next #06: a subagent's read-only transcript, pushed from
        // a spawn card or the Side Chats & Agents sheet.
        is SubagentRoute -> route.Content()
        // android-next #06: a new side chat's draft, pushed from the ⋯ or
        // the sheet's +; its first send swaps the chat in for it.
        is SideChatDraftRoute -> route.Content()
        // Ticket 140: the Engine detail page — the status card, the
        // actions card (Rename…, Pair again) and the Forget confirm the
        // Devices row's push lands on. 104's tablet Settings dialog routes
        // pushes through this same table.
        is EngineDetailRoute -> EngineDetailScreen(route.engineId)
        // android-next #05: the Accounts & Usage page — the Settings →
        // Agent Accounts row, the usage chip's "Accounts & Usage…" row
        // and the session ⋯ menu's "Account & Usage" push it.
        is AgentAccountsRoute -> AgentAccountsScreen(route)
        // agent-previews ticket 05: the preview panel (the phone's half of
        // the session relay) — the session ⋯ menu's "Previews" row pushes
        // it; its rows' Open pushes the in-app browser entry through this
        // same table.
        is PreviewPanelRoute -> PreviewPanelScreen(route)
        // agent-previews ticket 05: the in-app browser entry — the WebView
        // loading `http://{hostname}:7332` through the in-app client proxy.
        is BrowserRoute -> BrowserScreen(route)
        else -> Unit
    }
}
