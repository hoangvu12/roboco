package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * What the sidebar's list shows (android-ui-parity #105) — the Android
 * shape of iOS `SessionsViewController.buildSections`'s query branch
 * (`ios:Threads/SessionListController.swift:335-357`):
 *
 * - [FrontPage] — the normal front page (Pinned, user sections, Recent /
 *   project groups) with all its sections;
 * - [Results] — one header-less `"results"` section of search hits in
 *   core order, replacing the sections while the trimmed query is
 *   non-empty.
 *
 * Derived by [sidebarListContent] on every edit (no debounce).
 */
@Immutable
sealed interface SidebarListContent {

    /** The full front page — also the sidebar's empty state, unlike the Search tab. */
    data object FrontPage : SidebarListContent

    /** The hits of one finished search; [results]`mode` is always [SearchResults.Mode.Hits]. */
    data class Results(val results: SearchResults) : SidebarListContent
}

/**
 * iOS `SessionsViewController.buildSections` (`:354-357`) with an async
 * source (49's [SearchModel]): the trimmed raw query decides.
 *
 * - trimmed **empty** (including whitespace-only) → [SidebarListContent.FrontPage]
 *   — the sidebar's empty state is the **full** front page, not the Search
 *   tab's "Recent only" rule (ticket 49);
 * - [SearchResults.Mode.Hits] available → [SidebarListContent.Results] —
 *   possibly hits of the **previous** query while the new one is still in
 *   flight, which is what keeps the previous content on screen with no
 *   blank frame;
 * - otherwise (first search still running, [SearchResults.Mode.Loading],
 *   [SearchResults.Mode.Recent]) → [SidebarListContent.FrontPage].
 *
 * Pure; trimmed with 49's [searchTrim] so the two screens agree on
 * whitespace.
 */
fun sidebarListContent(rawQuery: String, results: SearchResults?): SidebarListContent = when {
    rawQuery.searchTrim().isEmpty() -> SidebarListContent.FrontPage
    results != null && results.mode == SearchResults.Mode.Hits -> SidebarListContent.Results(results)
    else -> SidebarListContent.FrontPage
}

/**
 * Fixture override for the tablet sidebar (105) — the gallery, previews
 * and Robolectric tests provide it around
 * [sh.roboco.android.shell.tablet.Sidebar] so the whole sidebar subtree
 * renders fixture data: [sections]/[collapsedIds] feed 46's root screen
 * (instead of the app's `FrontPageModel`), [workspace] feeds 45's pushed
 * folder pages (`FolderRouteContent` reads it instead of the app's
 * workspace feed) and [search] replaces the production
 * `coreSessionSearch(app::clientOrNull)`. Production never provides it:
 * `null` is the production path, and the local is deliberately
 * module-internal so no shipped code can set it.
 */
@Immutable
internal data class SidebarFixtures(
    /** 46's front-page sections (the `1-off` fixture set). */
    val sections: List<ListSection>,
    /** The workspace 45's folder pages read (Pinned / section / Archived rows). */
    val workspace: WorkspaceSnapshot,
    /** The fake core search: trimmed query in, hits in core order out. */
    val search: SessionSearch,
    /** The fold state of the fixture sections (usually empty). */
    val collapsedIds: Set<String> = emptySet(),
)

/** null (the default) = the production path: the app's feed and core client. */
internal val LocalSidebarFixtures = staticCompositionLocalOf<SidebarFixtures?> { null }
