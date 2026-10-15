package sh.roboco.android.shell.tablet

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle
import sh.roboco.android.sessions.FrontPageSections
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.sessions.SearchFixtures
import sh.roboco.android.sessions.SessionSearch
import sh.roboco.android.sessions.SessionsScreenFixtures
import sh.roboco.android.sessions.SidebarFixtures
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.SidebarToolbar
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.SearchField
import uniffi.roboco_core.SearchHit
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The "Tablet sidebar" page of the debug design gallery
 * (android-ui-parity #105), registered in ticket 22's `Gallery.sections`
 * as `GallerySection("Tablet sidebar", GalleryLayout.Scroll,
 * screenshotHeightDp = 1000) { SidebarGallerySection() }`.
 *
 * The whole 360 × 800 dp sidebar ([Sidebar] — the real front page in its
 * own nav stack, the stacked glass search field, the current-row fill and
 * 107's toolbar slot) renders at its real size and is scaled down to the
 * section's width (`graphicsLayer`, origin top-start) inside a box of
 * `800 dp × scale`, like 33's split gallery. It runs over
 * [SidebarFixtures] — 46's `1-off` section set at the root, a workspace
 * whose Pinned folder is long enough to scroll, and a fake core search
 * that answers "tool" (49's hits) and "wrangler" and returns nothing
 * otherwise — so the query filter, the no-results state and the pushed
 * folder pages all work without an engine. No wallpaper (none is set, so
 * 46's hero emits no node).
 *
 * The controls under the frame drive the two entry points 106 owns:
 * "focusSearch()" / "showSessions()" call [SidebarState]'s methods, and
 * "Current: none / row 2" flips [Sidebar]'s `currentChatId` to the second
 * Pinned row. The log line lists every [AppRouter] call the fake router
 * recorded (tap a row: `openSession(chat-veil)`; tap the toolbar's gear or
 * `+`: `showSettings()` / `presentNewSession(null)`).
 */
@Composable
fun SidebarGallerySection(currentInitially: String? = null) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberSidebarState()
    var current by remember { mutableStateOf(currentInitially) }
    val log = remember { mutableStateListOf<String>() }
    val router = remember { GalleryRouter(log) }
    val fixtures = remember { sidebarGalleryFixtures(gallerySearch) }

    GlassMenuHost {
        Column(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val scale = minOf(1f, maxWidth / 360.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(800.dp * scale),
                ) {
                    CompositionLocalProvider(LocalSidebarFixtures provides fixtures) {
                        Sidebar(
                            state = state,
                            currentChatId = current,
                            shown = true,
                            router = router,
                            topInset = 24.dp, // the goldens' fixed status bar (Robolectric has none)
                            modifier = Modifier
                                .requiredSize(360.dp, 800.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                },
                            toolbar = { stack ->
                                // 107: the real toolbar (gear → the fake
                                // router's log, ⋯ → 46's menu on the
                                // sidebar's stack, + → the router).
                                SidebarToolbar(
                                    sidebarStack = stack,
                                    router = router,
                                )
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SidebarGalleryButton("focusSearch()") { state.focusSearch() }
                SidebarGalleryButton("showSessions()") { state.showSessions() }
                SidebarGalleryButton(
                    if (current == null) "Current: none" else "Current: row 2",
                ) { current = if (current == null) CurrentRowId else null }
            }
            BasicText(
                "${log.size} router calls · last: ${log.lastOrNull() ?: "—"}",
                style = uiStyle(Face.Sans, typeScale.fixed(11f), palette.secondary),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The second Pinned row of 46's fixture — the gallery's current-row toggle. */
private const val CurrentRowId = "chat-models"

/** The gallery's text button. */
@Composable
private fun SidebarGalleryButton(label: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        style = uiStyle(Face.Sans, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** The gallery's fake router: every call becomes one visible log line. */
private class GalleryRouter(private val log: MutableList<String>) : AppRouter {
    override fun openSession(chatId: String) {
        log.add("openSession($chatId)")
    }

    override fun openSession(chatId: String, handoff: DraftHandoff?) {
        log.add("openSession($chatId, handoff)")
    }

    override fun presentNewSession(prompt: String?) {
        log.add("presentNewSession(${prompt ?: "null"})")
    }

    override fun showSettings() {
        log.add("showSettings()")
    }

    override fun showSearch() {
        log.add("showSearch()")
    }

    override fun showSessionOpenError() {
        log.add("showSessionOpenError()")
    }

    override fun popToFrontPage() {
        log.add("popToFrontPage()")
    }
}

// ---------------------------------------------------------------------------
// The fixture (105 §Fixture): 46's 1-off sections, a long-Pinned workspace
// and the fake search.
// ---------------------------------------------------------------------------

/**
 * The sidebar fixture builder (105 §Fixture — public like 33's
 * `SplitRootFixture` so the Robolectric / Roborazzi tests reuse it; the
 * gallery passes its own [search]): the `1-off` sections for the root
 * screen, and a workspace whose `front.pinned` repeats the two fixture
 * pins (distinct ids) so a Pinned folder pushed inside the sidebar has
 * the scroll range the bottom-padding check needs; Recent feeds the
 * `SearchSource` the model requires.
 */
internal fun sidebarGalleryFixtures(search: SessionSearch): SidebarFixtures {
    val sections = SessionsScreenFixtures.sections()
    val pinned = sections.first { it.id == FrontPageSections.PINNED }.rows
    val userSections = sections
        .filter { it.id != FrontPageSections.PINNED && it.id != FrontPageSections.RECENT }
        .map { s ->
            SectionView(
                id = s.id,
                engineId = "A",
                name = s.header ?: s.id,
                collapsed = false,
                sessions = s.rows,
            )
        }
    val recent = sections.first { it.id == FrontPageSections.RECENT }.rows
    val longPinned = pinned + (1..6).flatMap { n -> pinned.map { it.copy(id = "${it.id}-$n") } }
    val workspace = WorkspaceSnapshot(
        revision = 1uL,
        computedAtMs = 0L,
        pinsReady = false,
        synced = true,
        front = FrontPage(
            pinned = longPinned,
            sections = userSections,
            recent = recent,
            groups = emptyList(),
        ),
        projects = emptyList(),
        projectless = emptyList(),
        pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
        archived = emptyList(),
        devices = emptyList(),
    )
    return SidebarFixtures(
        sections = sections,
        workspace = workspace,
        search = search,
    )
}

/**
 * The Sessions root at its settled, loaded and empty state ("No sessions
 * yet") for captures that mount the real shell (MainTabShell's Sessions tab
 * through `ShellRouteContent`). Without a fixture the screen reads the app's
 * `FrontPageModel`, whose first value lands from the core's background
 * startup on wall time. A capture then shows the loading spinner or the empty
 * copy depending on the race (record run 38607283018's determinism
 * re-verify). With it: no sections, `loading = false`, no connection line,
 * no search hits.
 */
internal fun settledEmptySessionsFixtures(): SidebarFixtures = SidebarFixtures(
    sections = emptyList(),
    workspace = WorkspaceSnapshot(
        revision = 1uL,
        computedAtMs = 0L,
        pinsReady = false,
        synced = true,
        front = FrontPage(
            pinned = emptyList(),
            sections = emptyList(),
            recent = emptyList(),
            groups = emptyList(),
        ),
        projects = emptyList(),
        projectless = emptyList(),
        pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
        archived = emptyList(),
        devices = emptyList(),
    ),
    search = { _, _ -> emptyList() },
)

/** The fake core search: "tool" → 49's hits, "wrangler" → the deploy row, else nothing. */
private val gallerySearch: SessionSearch = { q, _ ->
    when (q) {
        "tool" -> SearchFixtures.hits.map { SearchHit(it, 100u, SearchField.TITLE) }
        "wrangler" -> listOf(
            SearchHit(
                SessionsScreenFixtures.recentRows.first { it.id == "chat-deploy" },
                100u,
                SearchField.TITLE,
            ),
        )
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------
// Previews: the section at its real 360 × 800 dp, at rest, with the
// current row set to the second Pinned row (the hosts the menus need come
// with the section's GlassMenuHost; the previews add the gallery's own
// ContextMenuHost / AlertHost / ToastHost stack around it).
// ---------------------------------------------------------------------------

@Composable
private fun SidebarGalleryPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        ContextMenuHost {
            AlertHost {
                ToastHost {
                    SidebarGallerySection(currentInitially = CurrentRowId)
                }
            }
        }
    }
}

@Preview(name = "sidebar light", widthDp = 360, heightDp = 800)
@Composable
private fun SidebarGalleryLightPreview() {
    SidebarGalleryPreviewBody(Appearance.Light)
}

@Preview(name = "sidebar dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SidebarGalleryDarkPreview() {
    SidebarGalleryPreviewBody(Appearance.Dark)
}
