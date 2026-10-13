package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LargeTitleDefaults
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalSearchTabState
import sh.roboco.android.shell.RegisterScrollToTop
import sh.roboco.android.shell.SearchMorphGeometry
import sh.roboco.android.shell.TabChromeMetrics
import sh.roboco.android.shell.TitleMode

/**
 * The Search tab's root screen (android-ui-parity #49) — the port of
 * `SearchViewController` (`ios:Threads/SessionListController.swift:516-540`)
 * behind ticket 29's field: the always-large **"Search"** title (25), one
 * **header-less** `"results"` section of [SearchResults] rows (41) and
 * the no-results view. No field of its own (the bottom bar owns it, 29),
 * no section headers, no folder rows, no "Recent" label, no result count,
 * no keyboard dismiss on scroll, no dimming over the results.
 *
 * The stateful half ([SearchScreen]) builds and retains the model, the
 * list state and 44's row hookup on the nav entry, so the query, the
 * results and the scroll position survive tab switches; the stateless
 * half ([SearchScreenContent]) is what the tests, goldens, previews and
 * the gallery host.
 */

// ---------------------------------------------------------------------------
// The stateful screen
// ---------------------------------------------------------------------------

/**
 * The Search tab's root (the `TabRootRoute(TabId.Search)` branch of 26's
 * `ShellRouteContent`). Everything screen-lifetime lives on the nav entry
 * (`MainTabController.swift:30-32`: the view controller lives as long as
 * the tab):
 *
 * - the [SearchModel], retained under `"search-model"`, reading 29's raw
 *   field text through `queryFlow()` and the app's single workspace feed
 *   (`RobocoApplication.workspaceFeed` — the same source tickets 40/42
 *   read, never a second listener), with the search FFI on
 *   `Dispatchers.Default` through [coreSessionSearch];
 * - the [LazyListState], retained under `"search-list"`, so the scroll
 *   position survives the host disposing a covered entry or a tab switch;
 * - 44's row hookup (`rememberSessionRowActions`) with
 *   [Modifier.sessionRowActions] on the scaffold modifier, and 26's
 *   scroll-to-top registration for a re-select of the Search tab.
 *
 * Tapping a result hides the IME and clears the field's focus **before**
 * 41's default open (`rememberSessionListOpener`) pushes the Session on
 * **this** tab's stack; the IME stays down when the user comes back (29
 * refocuses only on tab entry).
 */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    val entry = LocalNavEntry.current
    val tabState = LocalSearchTabState.current
    val app = LocalContext.current.applicationContext as RobocoApplication
    val model = entry.retain("search-model") {
        SearchModel(
            query = tabState.queryFlow(),
            source = app.workspaceFeed.snapshot.map { it?.toSearchSource() },
            search = coreSessionSearch(app::clientOrNull),
            scope = entry.scope,
        )
    }
    val listState = entry.retain("search-list") { LazyListState() }
    val results by model.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // 41's default open: the tablet override when provided, otherwise the
    // push on this stack through 32/76 with the failed-open toast.
    val opener = rememberSessionListOpener()
    // 44: swipes + the long-press menu, per-row archived actions.
    val hookup = rememberSessionRowActions(listState)
    RegisterScrollToTop(listState)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    SearchScreenContent(
        results = results,
        listState = listState,
        onOpenSession = { id ->
            // The IME goes down before the push; the field keeps its text.
            keyboard?.hide()
            focus.clearFocus(force = true)
            opener(id)
        },
        modifier = modifier
            .testTag("search-screen")
            .sessionRowActions(
                hookup,
                // Taps above the nav row (the back-slot area) are never
                // swallowed by the swipe-close layer.
                swallowTopPx = { with(density) { (topInset + LargeTitleDefaults.NavRowHeight).toPx() } },
            ),
        topInset = topInset,
        rowWrapper = hookup.rowWrapper,
    )
}

// ---------------------------------------------------------------------------
// The stateless screen
// ---------------------------------------------------------------------------

/**
 * The Search screen's content: 25's scaffold with the always-large
 * "Search" title, one header-less `ListSection(id = "results")` of
 * [results]'s rows through 41's list machinery, 41's loading overlay in
 * the scaffold's backdrop slot, the no-results view when
 * [SearchResults.showsNoResults], and 48's pull-to-refresh (Search never
 * opts out — iOS's list has it too).
 *
 * The bottom padding follows the IME: with the IME down it is
 * `chrome.contentPadding` (30); with the IME up, the last row clears the
 * raised search row by 8 dp — `max(chrome.contentPadding,
 * barBottom + lift + PillHeight + Gap)` ([searchBottomPadding]), the
 * `lift` being 29's `SearchMorphGeometry.rowBottom(B, imeBottom,
 * active = true) − B`. The IME inset is read **in layout only** (the
 * [SearchAreaPadding] object's `calculateBottomPadding` is called from
 * the measure phase — 25's scaffold delegates per side at measure time),
 * so the ~250 ms IME animation re-lays out the screen without recomposing
 * it. No `imePadding`, no `imeNestedScroll`, no dismiss-on-drag: scrolling
 * the results never dismisses the keyboard (UIKit default
 * `keyboardDismissMode`).
 *
 * @param rowWrapper 41's row wrapper; the stateful screen passes 44's
 *   hookup wrapper (swipes + the context menu with per-row archived
 *   actions). Tests, previews and the gallery keep the pass-through.
 * @param topInset 25's convention (the goldens and previews fix 24 dp).
 */
@Composable
fun SearchScreenContent(
    results: SearchResults,
    listState: LazyListState,
    onOpenSession: (chatId: String) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
) {
    val chrome = LocalBottomChrome.current
    // The insets object is obtained in composition (@Composable getter);
    // the *values* are snapshot reads taken in the measure block (29's
    // TabChromeLayout does the same), so the IME ride re-lays out without
    // recomposition.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // The list's content padding (top 0: the scaffold adds its own nav
    // row) and the free-area padding the backdrop overlay and the
    // no-results view share: top = topInset + 44 + 52 (below the expanded
    // large title), bottom = the same IME-following padding. Both are
    // remembered on the stable keys, so the recomposition per keystroke
    // never hands the list a new padding instance to re-measure.
    val listPadding = remember(chrome, ime, density) {
        SearchAreaPadding(ime, density, chrome.contentPadding, chrome.barBottom, top = 0.dp)
    }
    val freeAreaPadding = remember(chrome, ime, density, topInset) {
        SearchAreaPadding(
            ime,
            density,
            chrome.contentPadding,
            chrome.barBottom,
            top = topInset + LargeTitleDefaults.NavRowHeight + LargeTitleDefaults.LargeRowHeight,
        )
    }
    val model = rememberSessionListModel(
        sections = listOf(ListSection(id = "results", header = null, rows = results.rows)),
        collapsible = false,
        loading = results.mode == SearchResults.Mode.Loading,
        empty = SessionListEmpty.None,
        onScreen = rememberNavEntryOnScreen(),
    )
    LargeTitleScaffold(
        title = "Search",
        mode = TitleMode.Always,
        listState = listState,
        topInset = topInset,
        contentPadding = listPadding,
        // The root entry: no back circle (also safe outside a NavStackHost,
        // where 24's locals have no default).
        showBack = false,
        onBack = {},
        pullToRefresh = rememberSessionsPullToRefresh(),
        backdrop = {
            SessionListStatusOverlay(status = model.status, safePadding = freeAreaPadding)
            if (results.showsNoResults) {
                SearchNoResults(Modifier.fillMaxSize().padding(freeAreaPadding))
            }
        },
        modifier = modifier,
    ) {
        sessionListItems(
            model = model,
            onOpenSession = onOpenSession,
            // Per-row archived actions: a hit's FFI `archived` flag decides
            // Unarchive/Rename… (archived) vs the full active set — never a
            // list-wide flag, and never an FFI lookup during composition.
            archivedRow = { it in results.archivedIds },
            rowWrapper = rowWrapper,
        )
    }
}

// ---------------------------------------------------------------------------
// The insets
// ---------------------------------------------------------------------------

/**
 * The Search list's bottom padding, dp as `Float` (pure, plain-JVM
 * testable): `max(contentPadding, barBottom + lift + PillHeight + Gap)` —
 *
 * - with the IME down, `lift = 0`, so `contentPadding` (the chrome group
 *   plus 8, 30's value) wins;
 * - with the IME up, `barBottom + lift` is the raised search row's bottom
 *   (29's `rowBottom`), so the last row clears the 62 dp row by 8 dp
 *   (`PillHeight` + `Gap`, 28's constants — never retyped);
 * - once `lift ≥ 56` the accessory has faded out under the IME (29's
 *   `accessoryImeAlpha`) and only the search row's band is reserved; the
 *   `max` makes the two regimes meet without a jump.
 *
 * Worked values (gesture nav, `barBottom = 24`, `contentPadding = 150`):
 * IME down → 150; IME 300 dp → `lift = 284`, `24 + 284 + 70 = 378`.
 */
fun searchBottomPadding(
    contentPadding: Float,
    barBottom: Float,
    lift: Float,
    pillHeight: Float = TabChromeMetrics.PillHeight,
    gap: Float = TabChromeMetrics.Gap,
): Float = maxOf(contentPadding, barBottom + lift + pillHeight + gap)

/**
 * The screen's [PaddingValues] with an IME-following bottom: the top (and
 * sides) are fixed, the bottom is [searchBottomPadding] with the lift
 * read from `WindowInsets.ime` — **inside
 * [calculateBottomPadding][PaddingValues.calculateBottomPadding]**, which
 * the `LazyColumn` / `Modifier.padding` call from the measure phase, so
 * the IME animation never recomposes the screen (25's scaffold delegates
 * the caller's object per side at measure time, which is why this works
 * through it).
 */
@Immutable
private class SearchAreaPadding(
    private val ime: WindowInsets,
    private val density: Density,
    private val contentPadding: Dp,
    private val barBottom: Dp,
    private val top: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateTopPadding(): Dp = top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateBottomPadding(): Dp {
        // Snapshot read, taken during measure (29's rule: the insets object
        // is captured in composition, its values are read here).
        val imeBottom = with(density) { ime.getBottom(this).toDp().value }
        val lift = SearchMorphGeometry.rowBottom(barBottom.value, imeBottom, active = true) - barBottom.value
        return searchBottomPadding(contentPadding.value, barBottom.value, lift).dp
    }
}

// ---------------------------------------------------------------------------
// The no-results view
// ---------------------------------------------------------------------------

/** The magnifier's reserved box, dp (the system look's glyph box). ★ */
private val NoResultsGlyphBox = 48.dp

/** The glyph → title gap. ★ */
private val NoResultsTitleGap = 12.dp

/** The title → body gap. ★ */
private val NoResultsBodyGap = 4.dp

/** The column's side padding: the row margin (SessionRowGeometry.MARGIN). ★ */
private val NoResultsSideInset = 20.dp

/** "No Results" — iOS title2: Geist SemiBold, scaled 22. ★ */
private const val NoResultsTitlePt = 22f

/** The body line — Geist Regular, scaled 15. ★ */
private const val NoResultsBodyPt = 15f

/**
 * The no-results view (Roboco ADAPT of iOS's system
 * `UIContentUnavailableConfiguration.search()` — the iOS app itself shows
 * a blank list): a magnifier glyph in `secondary` (48 dp box ★,
 * decorative), **"No Results"** (Geist SemiBold, scaled 22 ★, `text`, one
 * line) and **"Check the spelling or try a new search."** (Geist Regular,
 * scaled 15 ★, `secondary`, centred, wrapping) — 12 dp ★ then 4 dp ★
 * apart, 20 dp ★ side padding, centred in whatever box the caller gives
 * (the screen passes the free area between the expanded large title and
 * the bottom padding, so the copy never sits under the raised field).
 *
 * Instant: no fade in or out, nothing animates. The column is one merged
 * polite live region (`search-no-results`): TalkBack reads
 * "No Results, Check the spelling or try a new search."
 */
@Composable
fun SearchNoResults(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = NoResultsSideInset)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag("search-no-results"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Decorative: no content description, no semantics node.
            IconInBox(
                icon = RobocoIcons.Magnifyingglass,
                tint = palette.secondary,
                box = NoResultsGlyphBox,
            )
            Spacer(Modifier.height(NoResultsTitleGap))
            BasicText(
                "No Results",
                style = uiStyle(Face.SansSemibold, ts.scaled(NoResultsTitlePt), palette.text)
                    .copy(textAlign = TextAlign.Center),
                maxLines = 1,
            )
            Spacer(Modifier.height(NoResultsBodyGap))
            BasicText(
                "Check the spelling or try a new search.",
                style = uiStyle(Face.Sans, ts.scaled(NoResultsBodyPt), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews: the Hits and No-results fixtures, light and dark, in the
// chrome the phone shell provides (24 dp bar bottom, 150 dp content
// padding) with a fixed 24 dp status bar.
// ---------------------------------------------------------------------------

@Composable
private fun SearchPreviewBody(appearance: Appearance, results: SearchResults) {
    RobocoTheme(appearance) {
        CompositionLocalProvider(LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp)) {
            // Frozen at 0 ns: the Loading fixture's spinner renders its
            // cycle-0 pattern and the preview never starts a frame loop.
            ProvideGlyphClock(frozenAtNanos = 0L) {
                SearchScreenContent(
                    results = results,
                    listState = rememberLazyListState(),
                    onOpenSession = {},
                    topInset = 24.dp,
                )
            }
        }
    }
}

@Preview(name = "light")
@Composable
private fun SearchHitsLightPreview() {
    SearchPreviewBody(Appearance.Light, SearchFixtures.hitsResults)
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchHitsDarkPreview() {
    SearchPreviewBody(Appearance.Dark, SearchFixtures.hitsResults)
}

@Preview(name = "no results light")
@Composable
private fun SearchNoResultsLightPreview() {
    SearchPreviewBody(Appearance.Light, SearchFixtures.noResults)
}

@Preview(name = "no results dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchNoResultsDarkPreview() {
    SearchPreviewBody(Appearance.Dark, SearchFixtures.noResults)
}
