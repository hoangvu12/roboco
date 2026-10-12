package sh.roboco.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * **The search morph (#29):** selecting the Search tab keeps the bottom
 * row and morphs it — the pill collapses into the return circle (the
 * previous tab's glyph, "Close"), the search circle stretches into the
 * field, and the field focuses with the IME rising in the same motion
 * (`UISearchTab.automaticallyActivatesSearch`). The morph runs in
 * [TabChromeLayout] over [rememberSearchMorphProgress]; the slots swap
 * through [SearchReturnCircle] / [SearchTabField] while the search chrome
 * is on screen; the row rides on the IME (`WindowInsets.ime`, read in the
 * layout's measure block) and the accessory fades with the lift — never
 * leaving composition (upstream #577) — with its taps disabled once the
 * alpha is under 0.01, so they reach the results list (49). The query
 * lives in [searchState] (app-scoped, survives rotation, not process
 * death), provided as [LocalSearchTabState] for ticket 49's screen, and
 * [MainTabState.select] bumps its activation token through `onSelected`.
 *
 * Not this ticket's: the bottom content insets / hiding the bar over a
 * Session (30), the accessory's content fade-in after leaving Search
 * (30), the live counts feed (40) and the app root that shows this shell
 * (35).
 *
 * @param state the tab state; defaults to [rememberMainTabState].
 * @param counts the accessory's live summary (ticket 40 wires the real
 *   feed; `LiveCounts()` draws no glyph and no summary).
 * @param searchState the Search tab's query holder; defaults to
 *   [rememberSearchTabState] (the app-scoped instance).
 * @param onNewSession the accessory's tap (ticket 32 presents the sheet).
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    counts: LiveCounts = LiveCounts(),
    searchState: SearchTabState = rememberSearchTabState(),
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
        // content width and the search slot's row geometry are the same pure
        // geometry's p = 0 rects.
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
        val rowW = tabBarRowWidth(maxWidth - insetStart - insetEnd).value

        // #29: every selection (taps, the router, deep links, back) routes
        // through MainTabState.select — the one hook that bumps the field's
        // activation token.
        SideEffect {
            state.onSelected = searchState::onTabSelected
        }

        CompositionLocalProvider(
            LocalSearchTabState provides searchState,
            LocalTabBarMinimize provides minimize,
        ) {
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

            // #29: the search morph — one spring from the selection (the
            // same `searchSelected` read the tab content already uses),
            // read only through lambdas below.
            val searchSelected = state.selected == TabId.Search
            val morph = rememberSearchMorphProgress(searchSelected)
            val searchPill0 = BarRect(0f, 0f, expandedPill.value, SearchMorphGeometry.RowHeight)

            // The chrome above the content, sampling it: pill + search +
            // accessory, placed by the geometry at the morphs' positions.
            // While Search is selected, ticket 29's slots replace 26's.
            CompositionLocalProvider(LocalHazeState provides barHaze) {
                TabChromeLayout(
                    progress = { minimize.progress.value },
                    searchProgress = { morph.value },
                    searchActive = searchSelected,
                    bottomInset = bottomGap,
                    hasAccessory = true,
                    modifier = Modifier.fillMaxSize().selectableGroup(),
                    onRects = { rects -> searchState.publishImeLift(rects.imeLift) },
                    pill = {
                        // The composition gate flips once per morph, not per
                        // frame (a derived read over the spring's state).
                        val showSearchChrome by remember(searchSelected) {
                            derivedStateOf {
                                SearchMorph.chromeShowing(searchSelected, morph.value)
                            }
                        }
                        if (showSearchChrome) {
                            SearchReturnCircle(
                                previousTab = state.previousTab,
                                progress = { morph.value },
                                minimizeProgress = { minimize.progress.value },
                                pill0 = searchPill0,
                                active = searchSelected,
                                // 26's programmatic select: no haptic, like
                                // the system Close; 28's expand and 30's
                                // accessory fade-in run as for any switch.
                                onReturn = { state.select(state.previousTab) },
                                onTabTap = onBarTap,
                            )
                        } else {
                            GlassTabPill(
                                selected = state.selected,
                                onTabTap = onBarTap,
                                minimizeProgress = { minimize.progress.value },
                                minimized = minimize.minimized,
                                onRestore = { minimize.expand() },
                                expandedWidth = expandedPill,
                            )
                        }
                    },
                    search = {
                        SearchTabField(
                            state = searchState,
                            progress = { morph.value },
                            rowWidth = rowW,
                            active = searchSelected,
                            onSearchTabTap = { onBarTap(TabId.Search) },
                        )
                    },
                    accessory = {
                        // #29: the accessory never leaves composition (#577).
                        // While the row rides on the IME it fades with the
                        // lift — the same `rowBottom` computation the layout
                        // made, re-read here in the draw phase and (for the
                        // hit-test gate) in a derived flip.
                        val ime = WindowInsets.ime
                        val b = bottomGap.value
                        val shellState = state
                        val morphNow = morph
                        val hittable by remember(density, bottomGap) {
                            derivedStateOf {
                                val imeBottom = with(density) {
                                    ime.getBottom(density).toDp().value
                                }
                                accessoryImeAlpha(
                                    SearchMorphGeometry.rowBottom(
                                        b,
                                        imeBottom,
                                        SearchMorph.chromeShowing(shellState.selected == TabId.Search, morphNow.value),
                                    ) - b,
                                ) >= 0.01f
                            }
                        }
                        Box(
                            Modifier.graphicsLayer {
                                val imeBottom = ime.getBottom(this).toDp().value
                                alpha = accessoryImeAlpha(
                                    SearchMorphGeometry.rowBottom(
                                        b,
                                        imeBottom,
                                        SearchMorph.chromeShowing(shellState.selected == TabId.Search, morphNow.value),
                                    ) - b,
                                )
                            },
                        ) {
                            NewSessionAccessory(
                                counts = counts,
                                onTap = onNewSession,
                                inline = minimize.minimized,
                                labelAlpha = { 1f - minimize.progress.value.coerceIn(0f, 1f) },
                                enabled = hittable,
                            )
                        }
                    },
                )
            }
        }
    }
}
