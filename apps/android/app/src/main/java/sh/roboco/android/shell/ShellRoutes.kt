package sh.roboco.android.shell

import androidx.compose.runtime.Composable

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
 * `TabRootRoute` branch per tab); 76 registers the Session route.
 */
@Composable
fun ShellRouteContent(route: NavRoute) {
    when (route) {
        is TabRootRoute -> PlaceholderTabRoot(route.tab)
        is PlaceholderDetailRoute -> PlaceholderDetail(route)
        else -> Unit
    }
}
