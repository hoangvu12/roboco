package sh.roboco.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.selectableGroup
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState

/**
 * The phone shell (android-ui-parity #26): the app root on phones, the
 * port of `MainTabController`'s `UITabBarController` — one [NavStack] per
 * tab over a `palette.background` view, the bottom chrome (26's pill +
 * search, 27's accessory, placed by #28's [TabChromeLayout]) floating over
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
 * - The tab content is the **only** `hazeSource` of the chrome's `HazeState`
 *   (ticket 07's per-screen convention); the chrome is the source's sibling
 *   through [LocalHazeState]. Screens that need their own top glass (09,
 *   25) nest a source inside this one, like ticket 14's root toast source.
 * - The unselected tab's host is **disposed**, not set inactive: a tab
 *   switch looks like disposal / first composition to a screen (24's
 *   tab-switch appear/disappear pair never fires here; use
 *   `DisposableEffect` `onDispose` for a draft save).
 *
 * **Minimize-on-scroll (#28):** the chrome shrinks while the user scrolls
 * the selected tab's content down (`tabBarMinimizeBehavior = .onScrollDown`,
 * `ios:Shell/MainTabController.swift:22`) and springs back on an upward
 * scroll, at the top, on a tap of the minimized circle, on a tab change and
 * on a committed push/pop. The detector ([Modifier.minimizeOnScroll]) wraps
 * the tab's whole `NavStack` host — the root list and pushed folder pages
 * both drive it — and is disabled while Search is selected or a
 * `hidesBottomChrome` route (a Session) is on top, read per event. The
 * morph itself is [TabChromeLayout] over [TabBarMinimizeState.progress],
 * read in the measure block only, so the spring never recomposes the shell.
 * The state is exposed as [LocalTabBarMinimize] for ticket 30's accessory
 * reveal.
 *
 * Not this ticket's: the bottom content insets / hiding the bar over a
 * Session (30), the Search bar-to-field morph (29), the live counts feed
 * (40) and the app root that shows this shell (35).
 *
 * @param state the tab state; defaults to [rememberMainTabState].
 * @param counts the accessory's live summary (ticket 40 wires the real
 *   feed; `LiveCounts()` draws no glyph and no summary).
 * @param onNewSession the accessory's tap (ticket 32 presents the sheet).
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    counts: LiveCounts = LiveCounts(),
    onNewSession: () -> Unit = {},
) {
    val palette = RobocoTheme.palette
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val barHaze = rememberHazeState()
    val minimize = rememberTabBarMinimizeState()

    // Composed BEFORE the tab content, so NavStackHost's own back handler
    // (enabled while canPop, predictive back) wins over this one; this one
    // only answers at a non-Sessions tab root.
    BackHandler(
        enabled = !state.stack(state.selected).canPop && state.selected != TabId.Sessions,
    ) { state.backFromRoot() }

    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current

        // The chrome geometry inputs (26's own reads): the bottom gap, and
        // the start/end insets that center the row. TabChromeLayout re-reads
        // the horizontal insets in its measure block; the pill's expanded
        // content width is the same pure geometry's p = 0 rect.
        val horizontalInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        val insetLeftPx = horizontalInsets.getLeft(density, layoutDirection)
        val insetRightPx = horizontalInsets.getRight(density, layoutDirection)
        val insetStart = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetRightPx else insetLeftPx).toDp()
        }
        val insetEnd = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetLeftPx else insetRightPx).toDp()
        }
        val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val tappableBottom = with(density) { WindowInsets.tappableElement.getBottom(density).toDp() }
        val bottomGap = tabBarBottomGap(navBottom, tappableBottom)
        val expandedPill = tabPillExpandedWidth(maxWidth.value, insetStart.value, insetEnd.value).dp

        CompositionLocalProvider(LocalTabBarMinimize provides minimize) {
            // The scrolling tab content: the only haze source the chrome
            // samples, wrapped in the minimize detector (#28).
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .minimizeOnScroll(minimize) {
                                        // Read per event, never captured: a
                                        // pushed Session's transcript must
                                        // not drive the bar, and Search has
                                        // no pill to minimize (29).
                                        state.selected != TabId.Search &&
                                            !state.stack(state.selected).top.route.hidesBottomChrome
                                    },
                            ) { entry -> ShellRouteContent(entry.route) }
                        }
                    }
                }
            }

            // A tab change or a committed push/pop restores the expanded bar
            // (a predictive back in progress does not change the committed
            // top). Snapped, not animated, when either end of the change
            // hides the bottom chrome (a Session): the reveal has a fixed,
            // expanded final layout for ticket 30.
            val top = state.stack(state.selected).top
            var lastTop by remember { mutableStateOf<Pair<TabId, NavEntry>?>(null) }
            LaunchedEffect(state.selected, top) {
                val previous = lastTop
                lastTop = state.selected to top
                if (previous == null) return@LaunchedEffect
                val hides = previous.second.route.hidesBottomChrome || top.route.hidesBottomChrome
                minimize.expand(animate = !hides)
            }

            // A bar tap also restores the expanded chrome — an unselected
            // tab, a re-select (whose scroll-to-top does not pass through
            // nested scroll) and a pop-to-root all land here (26/24).
            val onBarTap: (TabId) -> Unit = { tab ->
                minimize.expand()
                state.onTabTap(tab, feedback, scope)
            }

            // The chrome above the content, sampling it: pill + search +
            // accessory, placed by the geometry at the morph's position.
            // While Search is selected, ticket 29's layout replaces this.
            CompositionLocalProvider(LocalHazeState provides barHaze) {
                TabChromeLayout(
                    progress = { minimize.progress.value },
                    bottomInset = bottomGap,
                    hasAccessory = true,
                    modifier = Modifier.fillMaxSize().selectableGroup(),
                    pill = {
                        GlassTabPill(
                            selected = state.selected,
                            onTabTap = onBarTap,
                            minimizeProgress = { minimize.progress.value },
                            minimized = minimize.minimized,
                            onRestore = { minimize.expand() },
                            expandedWidth = expandedPill,
                        )
                    },
                    search = { GlassSearchButton(state.selected, onBarTap) },
                    accessory = {
                        NewSessionAccessory(
                            counts = counts,
                            onTap = onNewSession,
                            inline = minimize.minimized,
                            labelAlpha = { 1f - minimize.progress.value.coerceIn(0f, 1f) },
                        )
                    },
                )
            }
        }
    }
}
