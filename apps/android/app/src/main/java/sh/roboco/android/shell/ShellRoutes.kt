package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import sh.roboco.android.settings.LicensesRoute
import sh.roboco.android.settings.LicensesScreen
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.sessions.FolderRouteContent
import sh.roboco.android.sessions.SearchScreen

/**
 * A tab's root route (android-ui-parity #26): the first entry of the
 * tab's [NavStack], what `popToRoot` returns to and what the placeholder
 * roots render until the real screens land. Ticket 49 (Search) has
 * replaced its branch; 46 (Sessions) and 103 (Settings) replace theirs.
 */
data class TabRootRoute(val tab: TabId) : NavRoute

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
 * Settings "About" row pushes.
 */
@Composable
fun ShellRouteContent(route: NavRoute) {
    when (route) {
        is TabRootRoute -> when (route.tab) {
            // Ticket 49: the Search tab's root — the model + list state
            // live on the entry, so the query, results and scroll survive
            // tab switches (29 refocuses the field on re-entry).
            TabId.Search -> SearchScreen()
            else -> PlaceholderTabRoot(route.tab)
        }
        is PlaceholderDetailRoute -> PlaceholderDetail(route)
        is LicensesRoute -> LicensesScreen()
        // Ticket 45: one folder's sessions (Pinned, a user section,
        // Archived). The tablet sidebar (105) renders pushed routes
        // through this same table, so one branch serves both hosts.
        is FolderRoute -> FolderRouteContent(route)
        else -> Unit
    }
}
