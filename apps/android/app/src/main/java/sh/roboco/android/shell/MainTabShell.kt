package sh.roboco.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.clearText
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
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
 * **The router (#32):** the shell builds the phone [AppRouter] over its
 * own state (the adapter implements the [PhoneShell] port over this
 * shell's tickets) and provides it as [LocalAppRouter] around everything
 * below — every screen, deep link and notification calls it without
 * knowing which shell is on screen. The accessory's tap defaults to the
 * router's `presentNewSession()` (the iOS tab controller's own sheet),
 * and the sheet host is mounted here so the router can present over the
 * whole shell.
 *
 * The app root (35) shows this shell; its owner (`RobocoAppContent`) feeds
 * [liveCounts] from the workspace feed through [rememberLiveCounts].
 *
 * @param state the tab state; defaults to [rememberMainTabState].
 * @param counts a fixed accessory summary (galleries, previews, tests);
 *   `LiveCounts()` draws no glyph and no summary. Ignored when [liveCounts]
 *   is set.
 * @param liveCounts the live summary as a provider (production: the
 *   [rememberLiveCounts] state). Read only inside the accessory slot, so a
 *   counts change recomposes the accessory alone, never this shell or its
 *   measure-time `BoxWithConstraints` subcomposition (a counts change mid
 *   IME animation must not re-run the whole shell content inside a layout
 *   pass). Pass a stable instance (`remember`).
 * @param searchState the Search tab's query holder; defaults to
 *   [rememberSearchTabState] (the app-scoped instance).
 * @param hooks the app root's [RouterHooks] (ticket 35 creates the one
 *   instance; tickets 76 and 95 register their implementations on it).
 *   Defaults to [LocalRouterHooks] — a throwaway instance per shell.
 * @param sheetHost the host the phone shell presents its page sheets (New
 *   Session) on. `null` (production): the app root's host ([RootHosts],
 *   ticket 35, read through [LocalSheetHost]) — above the Phone ↔ Split
 *   switch, so a presented sheet survives it and the root's instant
 *   dismissal (forgetting the last engine) tears it down; 31's recession
 *   then scales the whole root. Without a root host (galleries, previews,
 *   tests mounting the shell alone) the shell mounts its own over itself.
 * @param presence forwarded [AppRouter.sessionDidAppear]/[Close] events
 *   (ticket 108's carry-over across the 840 dp breakpoint); `null` by
 *   default.
 * @param oneRowBar wave-10 37 (upstream #967 + #971): the iPhone's one-row
 *   bottom bar instead of the tab chrome — [SessionsBottomBar] (Settings,
 *   the search field filtering the list in place, New session) over the
 *   Sessions stack alone, shown on its root only
 *   ([NavStack.chromeOnRootOnly]); Settings is pushed ([SettingsRoute]) and
 *   Search is the field, so no tab can be selected and no tab bar comes
 *   back. The app root always passes `true`; `false` keeps the legacy tab
 *   chrome for its component tests and galleries only.
 * @param onNewSession the accessory's tap; `null` (production) presents the
 *   New Session sheet through the phone router (the iOS tab controller's
 *   own `presentNewSession`). The gallery demos override it.
 * @param onChrome invoked once with the shell's
 *   [ChromeVisibilityController] (the gallery readout and the tests use
 *   it to observe the live frame / content alpha).
 * @param onRouter invoked once with the shell's [AppRouter] — the
 *   equivalent of iOS `window.rootViewController as? AppRouter`, so
 *   tickets 35/36 can route deep links and launch extras into the shell
 *   on screen.
 * @param routeContent renders one stack entry; `null` (production) uses
 *   [ShellRouteContent]. The gallery and the goldens pass a demo screen
 *   for routes [ShellRouteContent] does not know yet.
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    counts: LiveCounts = LiveCounts(),
    liveCounts: (() -> LiveCounts)? = null,
    searchState: SearchTabState = rememberSearchTabState(),
    hooks: RouterHooks = LocalRouterHooks.current,
    sheetHost: SheetHostState? = null,
    presence: SessionPresenceListener? = null,
    oneRowBar: Boolean = false,
    onNewSession: (() -> Unit)? = null,
    onChrome: (ChromeVisibilityController) -> Unit = {},
    onRouter: (AppRouter) -> Unit = {},
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
    val insets = rememberBottomChromeInsets(oneRowBar)
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

    // Wave-10 37: the Sessions stack shows the bar on its root alone.
    remember(state, oneRowBar) {
        state.stack(TabId.Sessions).chromeOnRootOnly = oneRowBar
        oneRowBar
    }
    // Back on the front page with a query: clear it (the list unfilters)
    // before the activity's own back.
    val filtering by remember(searchState, oneRowBar) {
        derivedStateOf { oneRowBar && searchState.query.isNotEmpty() }
    }
    BackHandler(enabled = filtering && !state.stack(TabId.Sessions).canPop) {
        searchState.text.clearText()
    }

    // #33: the phone router (#32) built before the root modifier — the
    // hardware-shortcut modifier needs the real router, and reading
    // LocalAppRouter here would still see Noop (32 provides it inside this
    // composable, below the root). The sheet host is passed explicitly:
    // the root's (35) when the app root provides one, else this shell's own
    // (mounted further down, so LocalSheetHost below is that same instance
    // and the adapter sees an identical shell either way).
    val rootSheetHost = LocalSheetHost.current
    val ownSheetHost = rememberSheetHostState()
    val host = sheetHost ?: rootSheetHost ?: ownSheetHost
    val mountsOwnHost = host !== rootSheetHost
    val shell = rememberPhoneShell(
        state,
        host,
        oneRowBar = oneRowBar,
        search = if (oneRowBar) searchState else null,
    )
    val router = rememberPhoneAppRouter(shell, hooks, presence)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(palette.background)
            // #33: Ctrl+N / Ctrl+Shift+F from any focused view in the shell
            // (iOS ⌘N / ⇧⌘F). shell = null — Ctrl+B is not consumed on the
            // phone shell (no sidebar to toggle).
            .then(rememberShellShortcuts(router, shell = null)),
    ) {
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

        // #32/#35: the page sheets (New Session) present on the app root's
        // host (RootHosts, the innermost overlay host, around the whole
        // root); only a shell mounted without one hosts its own here, over
        // the whole tab shell, tab bar included.
        ShellSheetHost(host, mount = mountsOwnHost) {
            // #32: the phone router over this shell's real state,
            // provided to every screen below (the iOS window-root cast
            // `view.window?.rootViewController as? AppRouter`); constructed
            // above the root Box so #33's shortcut modifier can use it.
            val onRouterLatest = rememberUpdatedState(onRouter)
            LaunchedEffect(router) { onRouterLatest.value(router) }

            // #32: the accessory tap presents the New Session sheet
            // through the router (the iOS tab controller's own
            // presentNewSession) unless the caller overrode it (the
            // gallery demos).
            val accessoryTap = remember(onNewSession, router) {
                onNewSession ?: { router.presentNewSession() }
            }

            // #29 + #30: the query holder, the minimize state and the list
            // bottom insets flow to the tab content (the placeholder roots and
            // the real screens read LocalBottomChrome.contentPadding).
            CompositionLocalProvider(
                LocalAppRouter provides router,
                LocalSearchTabState provides searchState,
                LocalTabBarMinimize provides minimize,
                LocalBottomChrome provides insets,
                LocalBottomBarSearch provides if (oneRowBar) searchState else null,
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
                                            // no pill to minimize (29). The
                                            // one-row bar never minimizes.
                                            !oneRowBar &&
                                                state.selected != TabId.Search &&
                                                state.stack(state.selected).let { !it.hidesChrome(it.top) }
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
                    val stack = state.stack(state.selected)
                    val hides = stack.hidesChrome(previous.second) || stack.hidesChrome(top)
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
                    if (oneRowBar) {
                        // Wave-10 37: the one-row bar, placed / covered /
                        // dimmed by the same visibility controller (#30)
                        // and riding on the IME while the field is focused.
                        // Its top is the toasts' anchor (#967's Toast.swift).
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onPlaced { chrome.layoutTopInRoot = it.positionInRoot().y }
                                .chromeVisibility(chrome),
                        ) {
                            SessionsBottomBar(
                                searchState = searchState,
                                onSettings = { router.showSettings() },
                                onNewSession = accessoryTap,
                                onFrontPage = state.stack(TabId.Sessions).let { !it.hidesChrome(it.top) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
                                    .padding(
                                        start = insetStart + SessionsBottomBarMetrics.SideMargin.dp,
                                        end = insetEnd + SessionsBottomBarMetrics.SideMargin.dp,
                                        bottom = barBottom,
                                    )
                                    .onPlaced { coords ->
                                        chrome.updateChromeTop(
                                            with(density) { coords.positionInParent().y.toDp() }.value,
                                            density,
                                        )
                                    },
                            )
                        }
                    } else TabChromeLayout(
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
                                    // The provider is read here, in this
                                    // slot's own recompose scope.
                                    counts = liveCounts?.invoke() ?: counts,
                                    onTap = accessoryTap,
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
}

/**
 * Mounts [SheetHost] for [state] only when the shell owns it ([mount]);
 * on the app root's host the content composes as is (the root's
 * [SheetHost] already provides [LocalSheetHost] and presents above it).
 */
@Composable
private fun ShellSheetHost(state: SheetHostState, mount: Boolean, content: @Composable () -> Unit) {
    if (mount) SheetHost(state, content) else content()
}
