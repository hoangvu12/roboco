package sh.roboco.android.shell.tablet

import androidx.annotation.MainThread
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack

/**
 * Root of the sidebar stack (android-ui-parity #105): the front page —
 * iOS's `SessionsViewController` inside the sidebar's own
 * `UINavigationController` (`SplitRootController.swift:194-205`). Folder
 * routes (45's Pinned / section / Archived) push **on top of it**, inside
 * the sidebar, never in the detail column.
 */
data object SidebarRootRoute : NavRoute

/**
 * The sidebar's bottom-chrome numbers (107 §Geometry): a mirror of the
 * toolbar geometry, held here **only** so the list's bottom padding is
 * right before 107 lands: the toolbar row is 44 dp tall and its bottom
 * sits 12 dp above the navigation-bar inset, so the list reserves
 * `n + 12 + 44` (UIKit's `adjustedContentInset.bottom`, no extra gap).
 * 107 landed with these as the single source: its toolbar fills the slot
 * and adds no offset of its own — a retune edits this object and
 * [rememberSidebarBottomInsets], never a second number in 107's files.
 */
object SidebarChromeMetrics {

    /** 107's toolbar row height (44). */
    val ToolbarHeight = 44.dp

    /** 107's gap between the toolbar's bottom and the navigation bar (12 ★). */
    val ToolbarGap = 12.dp
}

/**
 * iOS `SidebarViewController`'s long-lived state (105) — the Android port
 * of `SplitRootController.swift:206-298`: the sidebar's own [NavStack], the
 * search field's [TextFieldState] and the front page's [LazyListState].
 *
 * Production creates exactly one instance — ticket 108's
 * `ShellStates.sidebar`, `remember`ed **above** 35's Phone ↔ Split switch,
 * so the stack, the query and the scroll position survive the 840 dp
 * collapse/expand round trip (35's `configChanges` keeps the activity).
 * Nothing here is saveable: process death resets everything, as iOS's
 * `SidebarViewController` does (no restoration code). Until 108 lands,
 * [rememberSidebarState] serves the gallery, previews and tests.
 *
 * `query` is the **raw** field text (`searchBar.text ?? ""`): trimming
 * happens once, in 49's `searchTrim`, read by [sh.roboco.android.sessions.sidebarListContent]
 * and the search model. `currentChatId` is deliberately **not** here —
 * 33's `TabletShellState.currentChatId` is the single source and the
 * sidebar just reads it.
 */
@Stable
class SidebarState(
    val stack: NavStack = NavStack(SidebarRootRoute),
    val search: TextFieldState = TextFieldState(),
    val listState: LazyListState = LazyListState(),
) {

    /** Raw field text (iOS `searchBar.text ?? ""`). Snapshot-backed. */
    val query: String get() = search.text.toString()

    /**
     * One-shot focus request consumed by [SidebarSearchField]: a flag, not
     * a counter — a freshly composed field (every 840 dp round trip, since
     * this state outlives `SplitRoot`) must not re-run an old request, and
     * a request made just before the field was composed still runs once.
     */
    internal var pendingFocus by mutableStateOf(false)

    /** One-shot drop-focus request consumed by [SidebarSearchField]. */
    internal var pendingDropFocus by mutableStateOf(false)

    /**
     * iOS `focusSearch()` (`SplitRootController.swift:289-292`): pop to the
     * root **without** animation, then focus the field with the IME — the
     * caret at the end of any surviving text. Ticket 106 calls this at
     * once (no settle wait), right as `show(.primary)` starts revealing a
     * hidden sidebar; the field's effect covers the placement timing.
     */
    @MainThread
    fun focusSearch() {
        stack.popToRoot(animated = false)
        pendingDropFocus = false
        pendingFocus = true
    }

    /**
     * iOS `showSessions()` (`SplitRootController.swift:294-298`): end the
     * search (IME down, focus dropped), empty the field (`query = ""`) and
     * pop to the front page — all without animation. Called by 106's
     * `showSessionOpenError()`.
     */
    @MainThread
    fun showSessions() {
        pendingFocus = false
        pendingDropFocus = true
        search.clearText()
        stack.popToRoot(animated = false)
    }
}

/**
 * One [SidebarState] for previews, the gallery and tests. Production uses
 * 108's `ShellStates.sidebar` instead — never `remember` a state inside
 * `SplitRoot`'s slot, which 35 swaps out below 840 dp.
 */
@Composable
fun rememberSidebarState(): SidebarState = remember { SidebarState() }

/**
 * The sidebar's [BottomChromeInsets] (105): `barBottom` = the
 * navigation-bar inset + [SidebarChromeMetrics.ToolbarGap] (107's toolbar
 * bottom), `contentPadding` = that + [SidebarChromeMetrics.ToolbarHeight]
 * (107's `reservedBottom` = `n + 12 + 44`). The navigation bar only — the
 * IME never counts (107's pitfall); screens that need IME clearance add
 * their own, like 49 does.
 *
 * Provided as [sh.roboco.android.design.LocalBottomChrome] around the
 * whole sidebar stack host in [Sidebar], so the root **and** 45's pushed
 * folder pages pad their lists past the toolbar area.
 */
@Composable
fun rememberSidebarBottomInsets(): BottomChromeInsets {
    val d = LocalDensity.current
    val navBars = WindowInsets.navigationBars
    val n = with(d) { navBars.getBottom(this).toDp() }
    val bar = n + SidebarChromeMetrics.ToolbarGap
    return remember(bar) {
        BottomChromeInsets(
            barBottom = bar,
            contentPadding = bar + SidebarChromeMetrics.ToolbarHeight,
        )
    }
}
