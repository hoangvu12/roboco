package sh.roboco.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState

/**
 * The phone shell (android-ui-parity #26): the app root on phones, the
 * port of `MainTabController`'s `UITabBarController` — one [NavStack] per
 * tab over a `palette.background` view, the [GlassTabBar] floating over
 * the content as a bottom overlay, and tab switching that swaps the
 * content instantly (UIKit has no cross-fade here).
 *
 * - Only the **selected** tab is composed, inside its own
 *   `SaveableStateProvider` keyed by the stable `identifier` (never an
 *   ordinal — a reorder would scramble saved state) and a `key(tab)` so
 *   one `NavStackHost` slot never leaks `remember`s between tabs. A tab
 *   never selected is never composed (the lazy UIKit providers); a
 *   previously visited tab restores its back stack (the [MainTabState]'s
 *   stacks live outside composition) and its saveable state (scroll
 *   positions) from the holder. `removeState` is never called.
 * - The shell's `BackHandler` is composed **before** the tab content and
 *   enabled only at a tab root of a non-Sessions tab, so the host's own
 *   handler (pushed screens, predictive back) always wins while it can
 *   pop, and system back at Settings returns to Sessions, at Search to
 *   `previousTab` — Android's stand-in for the missing iOS back gesture.
 * - The tab content is the **only** `hazeSource` of the bar's `HazeState`
 *   (ticket 07's per-screen convention); the bar is the source's sibling
 *   through [LocalHazeState]. Screens that need their own top glass (09,
 *   25) nest a source inside this one, like ticket 14's root toast source.
 * - The unselected tab's host is **disposed**, not set inactive: a tab
 *   switch looks like disposal / first composition to a screen (24's
 *   tab-switch appear/disappear pair never fires here; use
 *   `DisposableEffect` `onDispose` for a draft save).
 *
 * Not this ticket's: the "New session" accessory capsule ([accessory],
 * ticket 27 — it owns its own 8 dp gap to the pill), the bottom content
 * insets / hiding the bar over a Session (30), minimize-on-scroll (28),
 * the Search morph (29) and the app root that shows this shell (35).
 *
 * @param state the tab state; defaults to [rememberMainTabState].
 * @param accessory an optional composable above the bar (ticket 27's
 *   capsule); it is invoked in a column above the bar, centred.
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    accessory: (@Composable () -> Unit)? = null,
) {
    val palette = RobocoTheme.palette
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val barHaze = rememberHazeState()

    // Composed BEFORE the tab content, so NavStackHost's own back handler
    // (enabled while canPop, predictive back) wins over this one; this one
    // only answers at a non-Sessions tab root.
    BackHandler(
        enabled = !state.stack(state.selected).canPop && state.selected != TabId.Sessions,
    ) { state.backFromRoot() }

    Box(modifier.fillMaxSize().background(palette.background)) {
        // The scrolling tab content: the only haze source the bar samples.
        Box(Modifier.fillMaxSize().hazeSource(barHaze)) {
            val tab = state.selected
            // key(tab) is mandatory: without it one NavStackHost slot serves
            // different NavStacks and its remembers leak between tabs.
            key(tab) {
                holder.SaveableStateProvider(tab.identifier) {
                    CompositionLocalProvider(
                        LocalTabId provides tab,
                        LocalTabScrollToTop provides state.scrollToTop,
                    ) {
                        NavStackHost(
                            stack = state.stack(tab),
                            active = true,
                            modifier = Modifier.fillMaxSize(),
                        ) { entry -> ShellRouteContent(entry.route) }
                    }
                }
            }
        }
        // The bar (and later the accessory) above the content, sampling it.
        CompositionLocalProvider(LocalHazeState provides barHaze) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Ticket 27 sizes and spaces the capsule; 30 animates its
                // visibility. It owns its 8 dp gap to the pill.
                accessory?.invoke()
                GlassTabBar(
                    selected = state.selected,
                    onTabTap = { state.onTabTap(it, feedback, scope) },
                )
            }
        }
    }
}
