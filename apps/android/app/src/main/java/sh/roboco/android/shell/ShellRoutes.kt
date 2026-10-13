package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.settings.EngineDetailRoute
import sh.roboco.android.settings.LicensesRoute
import sh.roboco.android.settings.LicensesScreen
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.sessions.FolderRouteContent

/**
 * A tab's root route (android-ui-parity #26): the first entry of the
 * tab's [NavStack], what `popToRoot` returns to and what the placeholder
 * roots render until the real screens land. Tickets 46 (Sessions), 103
 * (Settings) and 49 (Search) replace the content behind it.
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
 * Settings "About" row pushes; 102 registers the Engine detail
 * placeholder (140 replaces it).
 */
@Composable
fun ShellRouteContent(route: NavRoute) {
    when (route) {
        is TabRootRoute -> PlaceholderTabRoot(route.tab)
        is PlaceholderDetailRoute -> PlaceholderDetail(route)
        is LicensesRoute -> LicensesScreen()
        // Ticket 45: one folder's sessions (Pinned, a user section,
        // Archived). The tablet sidebar (105) renders pushed routes
        // through this same table, so one branch serves both hosts.
        is FolderRoute -> FolderRouteContent(route)
        // Ticket 102 (Devices rows): the Engine detail push's placeholder
        // until 140 builds the page — just enough for the push to be
        // testable and the tab bar to stay visible.
        // TODO(140): the real Engine detail screen.
        is EngineDetailRoute -> Box(
            Modifier
                .fillMaxSize()
                .background(RobocoTheme.palette.background),
        ) {
            BasicText("Engine ${route.engineId}")
        }
        else -> Unit
    }
}
