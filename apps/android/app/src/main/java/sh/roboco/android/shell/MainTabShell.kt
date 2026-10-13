package sh.roboco.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalBottomChromeTop
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
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
 * **Chrome visibility (#30):** the whole bottom group — pill, search
 * field, accessory — is hidden while the selected stack's top route
 * `hidesBottomChrome` (a Session), rides and is covered like the UIKit
 * tab bar during push/pop, and the accessory content fades back in after
 * leaving Search. All of it is derived per frame by
 * [ChromeVisibilityController] and applied by [Modifier.chromeVisibility]
 * — the group is **never removed from composition** (upstream #577), so
 * its measured size, minimize state and press state survive every hide.
 * The shell also provides [LocalBottomChrome] (the list bottom insets,
 * from [rememberBottomChromeInsets]) and
 * [sh.roboco.android.design.LocalBottomChromeTop]'s live chrome top for
 * ticket 14's toast anchor, and wraps each entry with
 * [ProvideTransitionProgress] for ticket 85.
 *
 * Not this ticket's: the live counts feed (40) and the app root that shows
 * this shell (35).
 *
 * @param state the tab state; defaults to [rememberMainTabState].
 * @param counts the accessory's live summary (ticket 40 wires the real
 *   feed; `LiveCounts()` draws no glyph and no summary).
 * @param searchState the Search tab's query holder; defaults to
 *   [rememberSearchTabState] (the app-scoped instance).
 * @param onNewSession the accessory's tap (ticket 32 presents the sheet).
 * @param onChrome invoked once with the shell's
 *   [ChromeVisibilityController] (the gallery readout and the tests use
 *   it to observe the live frame / content alpha).
 * @param routeContent renders one stack entry; `null` (production) uses
 *   [ShellRouteContent]. The gallery and the goldens pass a demo screen
 *   for routes [ShellRouteContent] does not know yet.
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    counts: LiveCounts = LiveCounts(),
    searchState: SearchTabState = rememberSearchTabState(),
    onNewSession: () -> Unit = {},
    onChrome: (ChromeVisibilityController) -> Unit = {},
    routeContent: (@Composable (NavEntry) -> Unit)? = null,
) {
    val palette = RobocoTheme.palette
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val barHaze = rememberHazeState()
    val minimize = rememberTabBarMinimizeState()

    // #30: the chrome group's visibility, the list insets and the toast
    // anchor — all derived, never flipped, so a cancelled predictive back
    // re-derives from the real top screen.
    val chrome = rememberChromeVisibilityController(state, minimize)
    val insets = rememberBottomChromeInsets()
    val chromeTopState = LocalBottomChromeTop.current
    val reduceMotion = LocalReduceMotion.current
    val layoutDirection = LocalLayoutDirection.current
    // 29's IME lift, read only inside publishedChromeTop() (a snapshotFlow).
    chrome.searchImeLift = remember(state, searchState) {
        { if (state.selected == TabId.Search) searchState.searchImeLift() else 0f }
    }
    val onChromeLatest = rememberUpdatedState(onChrome)
    LaunchedEffect(chrome) { onChromeLatest.value(chrome) }
    SideEffect {
        chrome.reduceMotion = reduceMotion
        chrome.rtl = layoutDirection == LayoutDirection.Rtl
    }

    // #30: publish the chrome top for ticket 14's toast anchor — every
    // frame (the minimize spring, the IME ride, a reveal), written from a
    // collector, never from placement. NaN becomes Dp.Unspecified ("no
    // chrome on screen"), the reader's safe-area fallback.
    LaunchedEffect(chrome, chromeTopState) {
        snapshotFlow {
            chrome.publishedChromeTop().let { if (it.isNaN()) Dp.Unspecified else it.dp }
        }.collect { chromeTopState.top = it }
    }
    DisposableEffect(chromeTopState) {
        onDispose { chromeTopState.top = Dp.Unspecified }
    }

    // Composed BEFORE the tab content, so NavStackHost's own back handler
    // (enabled while canPop, predictive back) wins over this one; this one
    // only answers at a non-Sessions tab root.
    BackHandler(
        enabled = !state.stack(state.selected).canPop && state.selected != TabId.Sessions,
    ) { state.backFromRoot() }

    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val density = LocalDensity.current

        // The chrome geometry inputs (26's own reads): the start/end insets
        // that center the row. TabChromeLayout re-reads the horizontal
        // insets in its measure block; the pill's expanded content width and
        // the search slot's row geometry are the same pure geometry's p = 0
        // rects. The bottom gap is #30's [BottomChromeInsets.barBottom] —
        // the one value the bar layout and the list padding derive from.
        val horizontalInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        val insetLeftPx = horizontalInsets.getLeft(density, layoutDirection)
        val insetRightPx = horizontalInsets.getRight(density, layoutDirection)
        val insetStart = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetRightPx else insetLeftPx).toDp()
        }
        val insetEnd = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetLeftPx else insetRightPx).toDp()
        }
        val barBottom = insets.barBottom
        val expandedPill = tabPillExpandedWidth(maxWidth.value, insetStart.value, insetEnd.value).dp
        val rowW = tabBarRowWidth(maxWidth - insetStart - insetEnd).value

        // #29: every selection (taps, the router, deep links, back) routes
        // through MainTabState.select — the one hook that bumps the field's
        // activation token.
        SideEffect {
            state.onSelected = searchState::onTabSelected
        }

        // #29 + #30: the query holder, the minimize state and the list
        // bottom insets flow to the tab content (the placeholder roots and
        // the real screens read LocalBottomChrome.contentPadding).
        CompositionLocalProvider(
            LocalSearchTabState provides searchState,
            LocalTabBarMinimize provides minimize,
            LocalBottomChrome provides insets,
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
                            ) { entry ->
                                // #30: every entry gets its live transition
                                // progress (85's composer cross-fade).
                                ProvideTransitionProgress(state.stack(tab), entry) {
                                    val rc = routeContent
                                    if (rc != null) rc(entry) else ShellRouteContent(entry.route)
                                }
                            }
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
            // #30: the whole group is placed / covered / dimmed by
            // [Modifier.chromeVisibility] — never removed from composition.
            CompositionLocalProvider(LocalHazeState provides barHaze) {
                TabChromeLayout(
                    progress = { minimize.progress.value },
                    searchProgress = { morph.value },
                    searchActive = searchSelected,
                    bottomInset = barBottom,
                    hasAccessory = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup()
                        .onPlaced { chrome.layoutTopInRoot = it.positionInRoot().y }
                        .chromeVisibility(chrome),
                    onRects = { rects ->
                        searchState.publishImeLift(rects.imeLift)
                        chrome.updateChromeTop(rects.chromeTop, density)
                    },
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
                        // hit-test gate) in a derived flip. #30: the content
                        // also carries the after-Search / pop-reveal fade
                        // (chrome::accessoryContentAlpha); the two never
                        // multiply — 29's IME fade sits on the wrapping Box,
                        // 30's on the content row itself.
                        val ime = WindowInsets.ime
                        val b = barBottom.value
                        val shellState = state
                        val morphNow = morph
                        val hittable by remember(density, barBottom) {
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
                                contentAlpha = chrome::accessoryContentAlpha,
                                enabled = hittable,
                            )
                        }
                    },
                )
            }

            // #30: all pointer input over the shell is consumed while the
            // selected stack transitions (UIKit disables interaction during
            // transitions); back gestures are not pointer events, so a
            // predictive back drives the reveal underneath it.
            ChromeTransitionBlocker(chrome, Modifier.matchParentSize())
        }
    }
}
