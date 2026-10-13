package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LiftHighlight
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperArtwork
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.contextMenu
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.design.rememberContextMenuLiftState
import sh.roboco.android.design.wallpaperFixture
import sh.roboco.android.shell.LargeTitleDefaults
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.RegisterScrollToTop
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import sh.roboco.android.shell.TitleMode

/**
 * The Sessions tab root (android-ui-parity #46) — the Android port of
 * `SessionsViewController` (`ios:Threads/SessionListController.swift:332-435`)
 * on ticket 25's [LargeTitleScaffold]:
 *
 * - the always-on large title "Sessions" and the trailing ⋯ glass circle
 *   (08) whose tap opens the options menu (15) built by
 *   [sessionsOptionsMenu];
 * - the list of 41's items (`collapsible = true`) over the wallpaper hero
 *   (21) fixed behind it, fading with the scroll ([ScrollTravel]);
 * - the header long-press menus ([sessionHeaderMenu]) through 16's
 *   `Modifier.contextMenu`, plugged into 41's `headerWrapper` slot;
 * - 44's row wrapper (swipe + row long-press menu) through 41's
 *   `rowWrapper`, and the write/alert/toast flows of 42/43 behind
 *   [SessionsScreenActions].
 *
 * [SessionsScreenContent] is the injectable half (sections, actions, hero
 * and bottom padding as parameters); this wrapper reads the app's
 * singletons (40's `FrontPageModel`, the single [sh.roboco.android.core.WorkspaceFeed])
 * and 43's prompt backend, which the sessions tab provides
 * (`SessionPrompts`'s note: 35 provides only [LocalRowActions]).
 */
@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    showsNavOptions: Boolean = true,
    currentChatId: String? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val app = LocalContext.current.applicationContext as RobocoApplication
    // 43: the prompts' engine list comes from the live client; provided
    // here for everything under this screen (46's own actions pass it
    // explicitly; 44's row commands read the ambient).
    val backend = remember(app) { CoreSessionPromptBackend(app::clientOrNull) }
    CompositionLocalProvider(LocalSessionPromptBackend provides backend) {
        val actions = rememberSessionsActions(LocalNavStack.current)
        // 44: the swipe/menu hookup for every row, and the touch that
        // closes an open swipe anywhere on the screen (taps above the nav
        // row — the ⋯ — are never swallowed).
        val hookup = rememberSessionRowActions(listState)
        val ui by app.frontPage.ui.collectAsState()
        val engines by app.workspaceFeed.engines.collectAsState()
        val density = LocalDensity.current
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val swallowTopPx = remember(density, topInset) {
            { with(density) { (topInset + LargeTitleDefaults.NavRowHeight).toPx() } }
        }
        SessionsScreenContent(
            sections = ui?.sections.orEmpty(),
            collapsedIds = ui?.collapsedIds.orEmpty(),
            loading = ui == null || sessionsLoading(engines),
            actions = actions,
            showsNavOptions = showsNavOptions,
            currentChatId = currentChatId,
            listState = listState,
            bottomPadding = LocalBottomChrome.current.contentPadding,
            liveOverrides = ui?.liveOverrides.orEmpty(),
            modifier = modifier.sessionRowActions(hookup, swallowTopPx = swallowTopPx),
            rowWrapper = hookup.rowWrapper,
        )
    }
}

/**
 * The injectable screen — the composition the gallery, previews, tests and
 * goldens drive with fixture sections and a recorder. The real
 * [SessionsScreen] passes 40's `FrontPageUi` values through unchanged and
 * 44's row wrapper.
 *
 * The scroll fade is read **only** inside the hero's draw/layer phase (the
 * [hero] slot invokes its `scrollFade` lambda from `WallpaperArtwork`'s
 * `graphicsLayer` block), so scrolling never recomposes this screen; the
 * header menus are built only when a long press fires, so the
 * Group-by-Project check and the raw section names are never stale.
 */
@Composable
internal fun SessionsScreenContent(
    sections: List<ListSection>,
    collapsedIds: Set<String>,
    loading: Boolean,
    actions: SessionsScreenActions,
    showsNavOptions: Boolean,
    currentChatId: String?,
    listState: LazyListState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    hero: @Composable (Modifier, () -> Float) -> Unit = { m, f -> WallpaperHero(modifier = m, scrollFade = f) },
    rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
    liveOverrides: Map<String, SectionLive> = emptyMap(),
) {
    val density = LocalDensity.current
    val travel = remember { ScrollTravel() }
    val latestActions by rememberUpdatedState(actions)
    val model = rememberSessionListModel(
        sections = sections,
        collapsible = true,
        collapsedIds = collapsedIds,
        liveOverrides = liveOverrides,
        loading = loading,
        empty = SessionListEmpty.Sessions,
        onScreen = rememberNavEntryOnScreen(),
    )
    // The opener is @Composable and the LazyListScope content is not, so
    // it is built here and read at tap time.
    val openSession = rememberSessionListOpener()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // One wrapper instance for every header: remembered once, reading the
    // actions through [latestActions], so a recomposition of this screen
    // never recomposes every header item.
    val headerWrapper: SectionHeaderWrapper = remember {
        { state, header ->
            val lift = rememberContextMenuLiftState()
            CompositionLocalProvider(LocalRowLiftState provides lift) {
                // 41 places this modifier FIRST in 39's row chain, before
                // its `clickable`, so the fold tap, the "Show menu"
                // long-click, the label and the `section-<id>` tag share
                // one semantics node. The menu lambda runs when the long
                // press fires — the latest actions, the latest names.
                header(
                    Modifier.contextMenu(
                        highlight = LiftHighlight.SectionHeader,
                        liftState = lift,
                        menu = { sessionHeaderMenu(state.id, latestActions) },
                    )
                )
            }
        }
    }

    // 26: re-tap the tab → item 0 (the fade returns to 1).
    RegisterScrollToTop(listState)

    LargeTitleScaffold(
        title = "Sessions",
        mode = TitleMode.Always,
        listState = listState,
        contentPadding = PaddingValues(bottom = bottomPadding),
        modifier = modifier.testTag("tab-root-sessions"),
        trailing = { if (showsNavOptions) SessionsOptionsButton(actions) },
        backdrop = {
            // The hero frame: fixed behind the list, inside the haze
            // source, above the page background and below every cell —
            // `collectionView.backgroundView`'s z-order. y = 0 is the top
            // of the screen (under the status bar), and the height is
            // 72 % of the full screen, capped at 760 dp.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val heroH = sessionsHeroHeight(maxHeight)
                val heroHDp = heroH.value
                hero(Modifier.align(Alignment.TopStart).fillMaxWidth().height(heroH)) {
                    // State reads inside this lambda: the layer phase only.
                    val info = listState.layoutInfo
                    val px = travel.update(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                        info.totalItemsCount,
                        info.visibleItemsInfo,
                    )
                    sessionsScrollFade(px / density.density, heroHDp)
                }
            }
            // 41 step 7: the empty / loading overlay behind the clear
            // list, above the hero — the expanded bar's bottom is its top
            // safe padding.
            SessionListStatusOverlay(
                status = model.status,
                safePadding = PaddingValues(
                    top = topInset + LargeTitleDefaults.NavRowHeight + LargeTitleDefaults.LargeRowHeight,
                    bottom = bottomPadding,
                ),
            )
        },
    ) {
        sessionListItems(
            model = model,
            onOpenSession = openSession,
            onToggleSection = { id -> latestActions.toggleSection(id) },
            currentChatId = currentChatId,
            rowWrapper = rowWrapper,
            headerWrapper = headerWrapper,
        )
    }
}

/**
 * The ⋯ bar item (`navigationItem.rightBarButtonItem = UIBarButtonItem(image:
 * "ellipsis", menu: optionsMenu())`): a 44 dp glass circle (08's default
 * measured bar ink, SESSIONS-D1), "More" as its a11y label (UIKit's
 * default for the ellipsis glyph), tap-opened. The menu is built inside
 * the click handler — never during composition — and the button follows
 * 15's rule for buttons with their own `clickable`:
 * [sh.roboco.android.design.glass.rememberGlassMenuState] + `open`, not
 * `Modifier.glassMenu`.
 */
@Composable
private fun SessionsOptionsButton(actions: SessionsScreenActions) {
    val menu = rememberGlassMenuState()
    var anchor by remember { mutableStateOf(Rect.Zero) }
    GlassCircleButton(
        icon = RobocoIcons.Ellipsis,
        contentDescription = "More",
        onClick = { menu.open(anchor, sessionsOptionsMenu(actions)) },
        modifier = Modifier
            .onGloballyPositioned { anchor = it.boundsInWindow() }
            .testTag("sessions-options"),
    )
}

// ---------------------------------------------------------------------------
// Previews: 402 × 874 (the reference phone), the fixture sections, the
// fixture hero (21's WallpaperArtwork at opacity 1), 150 dp of bottom
// chrome padding (30's gesture-navigation value) and a no-op recorder,
// inside the hosts the ⋯ and header menus need (15 throws without a
// GlassMenuHost; 35's order has ContextMenuHost outermost).
// ---------------------------------------------------------------------------

/** The previews' recorder: the fixture's user-section names, nothing else. */
private object PreviewSessionsActions : SessionsScreenActions {
    override val groupByProject: Boolean get() = false
    override fun setGroupByProject(on: Boolean) {}
    override fun newSection() {}
    override fun collapseAll() {}
    override fun toggleSection(id: String) {}
    override fun openPinned() {}
    override fun openArchived() {}
    override fun openSection(id: String, name: String) {}
    override fun renameSection(id: String, currentName: String) {}
    override fun deleteSection(id: String) {}
    override fun userSectionName(id: String): String? = SessionsScreenFixtures.sectionNames[id]
}

@Composable
private fun SessionsScreenPreview(appearance: Appearance) {
    RobocoTheme(appearance) {
        ContextMenuHost {
            GlassMenuHost {
                val stack = remember { NavStack(TabRootRoute(TabId.Sessions)) }
                NavStackHost(stack = stack, active = true) { entry ->
                    if (entry.route is TabRootRoute) {
                        SessionsScreenContent(
                            sections = SessionsScreenFixtures.sections(),
                            collapsedIds = emptySet(),
                            loading = false,
                            actions = PreviewSessionsActions,
                            showsNavOptions = true,
                            currentChatId = null,
                            listState = rememberLazyListState(),
                            bottomPadding = 150.dp,
                            hero = { m, f ->
                                WallpaperArtwork(
                                    render = WallpaperRender(wallpaperFixture(), 1f),
                                    appear = { 1f },
                                    modifier = m,
                                    scrollFade = f,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "light", widthDp = 402, heightDp = 874)
@Composable
private fun SessionsScreenLightPreview() {
    SessionsScreenPreview(Appearance.Light)
}

@Preview(name = "dark", widthDp = 402, heightDp = 874, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionsScreenDarkPreview() {
    SessionsScreenPreview(Appearance.Dark)
}
