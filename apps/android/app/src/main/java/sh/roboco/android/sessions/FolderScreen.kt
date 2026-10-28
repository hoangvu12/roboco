package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassTextCapsule
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.shell.LargeTitleDefaults
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import sh.roboco.android.shell.TitleMode
import sh.roboco.android.shell.largeTitleDefaultTopInset
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * One folder's sessions (android-ui-parity #45) — the Android port of
 * `FolderViewController` (`ios:Threads/SessionListController.swift:437-514`)
 * on ticket 25's [LargeTitleScaffold]: the folder name as an always-on
 * large title, one header-less `ListSection(id = "sessions")` of the rows
 * [folderRows] derives from the live [workspace], and per kind:
 *
 * - **Pinned** — the nav-bar trailing **Edit ↔ Done** glass text capsule
 *   (ticket 47, tag `pinned-edit`, an instant label swap) and with it
 *   Edit mode: the list gets `reorderable = true, editing = editing` and
 *   ticket 47's reorder hookup — drag handles, the in-engine clamp and
 *   one `movePin(id, after, before)` per drop;
 * - **Section** — a 44 dp ⋯ [GlassCircleButton] ("More") that opens
 *   ticket 15's glass menu with "Rename Section…" and "Delete Section",
 *   wired to ticket 43's [SessionPrompts] launchers;
 * - **Archived** — no trailing item, the rows use the archived variants
 *   (`archivedRows = true`), and an empty archive shows 41's
 *   "No archived sessions" (a Roboco addition; iOS shows a blank list).
 *
 * This ticket makes **no core calls**: 43's launchers own the prompts, 42's
 * [RowActions] own the writes, and the title updates / the pop only happen
 * on `ActionOutcome.Ok` (delete also `Gone`) — 43 calls the callbacks, this
 * screen reacts. Rename and Delete pre-fill with the section's **live raw
 * name** from the latest workspace (iOS captures it at push — an upstream
 * bug), and do nothing when the section is no longer there.
 *
 * Ticket 48 later adds pull-to-refresh to the scaffold call.
 *
 * @param workspace null = not loaded yet (41's spinner through `loading`);
 *   the rows follow it live while the screen is open
 * @param loading `workspace == null || sessionsLoading(engines)` — 41's rule
 * @param topInset 25's convention ([largeTitleDefaultTopInset] — 0 inside
 *   104's Settings card); passed explicitly (24 dp) by the goldens
 *   and previews because Robolectric reports no insets
 * @param initialEditing previews and the Edit-mode goldens only: the Pinned
 *   Edit mode starts on, so they capture the settled Edit state without
 *   driving the capsule's 300 ms toggle; `false` in production (the capsule
 *   owns the state)
 * @param prompts ticket 43's launchers; tests pass a recording fake
 * @param navStack read in composition (never inside a callback — that does
 *   not compile); the delete's `onDeleted` pops it animated
 * @param rowWrapper `PassThroughRowWrapper` until ticket 44 lands its swipe
 *   and context-menu hookup; tests pass a capturing wrapper to observe the
 *   per-row flags
 */
@Composable
fun FolderScreen(
    route: FolderRoute,
    workspace: WorkspaceSnapshot?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    // 104: defaults to 25's resolved default, so the screen insets nothing
    // when hosted inside the tablet Settings card.
    topInset: Dp = largeTitleDefaultTopInset(),
    initialEditing: Boolean = false,
    prompts: SessionPrompts = rememberSessionPrompts(),
    navStack: NavStack = LocalNavStack.current,
    rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
) {
    val kind = folderKind(route.folderId)
    // The title is local, saveable state keyed by the folder: a successful
    // rename changes it in place (25 supports a changing title without
    // resetting the scroll), and a later workspace frame must never undo it.
    var title by rememberSaveable(route.folderId) { mutableStateOf(route.title) }
    val rows = workspace?.let { folderRows(it, route.folderId) }.orEmpty()
    val model = rememberSessionListModel(
        sections = listOf(ListSection(id = "sessions", header = null, rows = rows)),
        collapsible = false,
        loading = loading,
        empty = if (kind == FolderKind.Archived) SessionListEmpty.Archived else SessionListEmpty.None,
        onScreen = rememberNavEntryOnScreen(),
    )
    val listState = rememberLazyListState()
    val bottom = LocalBottomChrome.current.contentPadding
    // The tap opener is remembered in composition and read by the list
    // items' click lambdas at tap time.
    val opener = rememberSessionListOpener()

    // Ticket 47, Pinned only: the Edit/Done state (saveable — it survives a
    // configuration change) and the reorder hookup. Section and Archived
    // have neither. The keys and the id → engine map come from the same
    // live rows (ws.front.pinned, already in merged engine-block order);
    // the state's `order` then leads the list while a drag or its commit
    // is in flight, and every snapshot is adopted through it.
    var editing by rememberSaveable(route.folderId) { mutableStateOf(initialEditing) }
    val reorder: SessionListReorder? = if (kind == FolderKind.Pinned) {
        val engineById = remember(rows) { rows.associate { r -> r.id to r.engineId } }
        val keys = remember(rows) { rows.map { r -> PinKey(r.id, r.engineId) } }
        val state = remember { PinReorderState(keys) }
        LaunchedEffect(keys) { state.adopt(keys) }
        // The auto-scroll starts at the visible edges, not under the glass
        // bars: the same top/bottom padding the scaffold gives the list
        // (status-bar inset + 44 nav row + 52 large-title row, 25; the
        // bottom chrome, 30).
        val lazyState = rememberReorderableLazyListState(
            lazyListState = listState,
            scrollThresholdPadding = PaddingValues(
                top = topInset + LargeTitleDefaults.NavRowHeight + LargeTitleDefaults.LargeRowHeight,
                bottom = bottom,
            ),
        ) { from, to -> state.move(from.key, to.key) }
        val actions = LocalRowActions.current
        SessionListReorder(
            lazyState = lazyState,
            state = state,
            engineOf = { id -> engineById.getValue(id) },
            dispatch = { mv, onResult -> actions.movePin(mv.id, mv.after, mv.before, onResult) },
        )
    } else {
        null
    }

    // The live raw name for the Rename prefill and the Delete prompt title:
    // read at menu-action time from the latest workspace, never the
    // push-time route.title (which may carry 40's " · <engine>" suffix).
    val latestWorkspace = rememberUpdatedState(workspace)

    fun rawName(): String? =
        latestWorkspace.value?.front?.sections?.firstOrNull { it.id == route.folderId }?.name

    fun rename() {
        val name = rawName() ?: return // the section is gone: no prompt (43's rule)
        prompts.renameSection(route.folderId, name) { title = it }
    }

    fun confirmDelete() {
        val name = rawName() ?: return
        prompts.confirmDeleteSection(route.folderId, name) { navStack.pop(animated = true) }
    }

    LargeTitleScaffold(
        title = title,
        mode = TitleMode.Always,
        listState = listState,
        topInset = topInset,
        contentPadding = PaddingValues(bottom = bottom),
        modifier = modifier.testTag("folder-screen"),
        trailing = { FolderTrailing(kind, editing, { editing = !editing }, ::rename, ::confirmDelete) },
        backdrop = {
            SessionListStatusOverlay(
                status = model.status,
                safePadding = PaddingValues(
                    top = topInset + LargeTitleDefaults.NavRowHeight + LargeTitleDefaults.LargeRowHeight,
                    bottom = bottom,
                ),
            )
        },
    ) {
        sessionListItems(
            model = model,
            onOpenSession = opener,
            archivedRows = kind == FolderKind.Archived,
            reorderable = kind == FolderKind.Pinned,
            editing = editing,
            rowWrapper = rowWrapper,
            reorder = reorder,
        )
    }
}

/**
 * The nav row's trailing slot: the ⋯ glass circle for a user section (the
 * port of the `UIBarButtonItem(image: "ellipsis", menu:)` of
 * `SessionListController.swift:460-466`), the **Edit ↔ Done** glass text
 * capsule for Pinned (ticket 47 — UIKit's `editButtonItem`,
 * `SessionListController.swift:455`: "Edit" Medium 17, "Done" SemiBold
 * 17 (the `.done` style), an instant label swap), nothing for Archived
 * (iOS `:460`: no rightBarButtonItem at all).
 *
 * The menu opens on **tap** from the button's own `onClick` through
 * [rememberGlassMenuState] — never `Modifier.glassMenu`, which would give
 * the button two click handlers (ticket 15's rule). It is built on each
 * tap so the actions see the current workspace.
 */
@Composable
private fun RowScope.FolderTrailing(
    kind: FolderKind,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    when (kind) {
        FolderKind.Pinned -> GlassTextCapsule(
            text = if (editing) "Done" else "Edit",
            emphasized = editing,
            onClick = onToggleEditing,
            modifier = Modifier.testTag("pinned-edit"),
        )
        FolderKind.Archived -> Unit
        FolderKind.Section -> {
            val menuState = rememberGlassMenuState()
            var anchor by remember { mutableStateOf(Rect.Zero) }
            GlassCircleButton(
                icon = RobocoIcons.Ellipsis,
                contentDescription = "More",
                onClick = { menuState.open(anchor, sectionMenu(onRename, onDelete)) },
                modifier = Modifier
                    .testTag("folder-menu")
                    .onGloballyPositioned { anchor = it.boundsInWindow() },
            )
        }
    }
}

/**
 * The section ⋯ menu (`SessionListController.swift:461-465`): two rows,
 * no menu title, no separators — "Rename Section…" (pencil) and "Delete
 * Section" (trash, destructive: title and icon in `Palette.danger`). The
 * titles are verbatim; the U+2026 and the curly-quoted delete prompt live
 * in 43's `SessionPromptCopy`.
 */
private fun sectionMenu(onRename: () -> Unit, onDelete: () -> Unit): Menu = Menu(
    children = listOf(
        MenuItem(
            title = "Rename Section\u2026",
            icon = MenuIcon.Symbol(RobocoIcons.Pencil),
            testTag = "folder-menu-rename",
            action = onRename,
        ),
        MenuItem(
            title = "Delete Section",
            icon = MenuIcon.Symbol(RobocoIcons.Trash),
            destructive = true,
            testTag = "folder-menu-delete",
            action = onDelete,
        ),
    ),
)

// ---------------------------------------------------------------------------
// The route content: the workspace + engines of the app's single feed
// ---------------------------------------------------------------------------

/**
 * Wires [FolderScreen] into the shell's route table (ticket 26's
 * `ShellRouteContent`): the app's single [WorkspaceFeed] (the same source
 * tickets 40/46 read — never a second listener) provides the snapshot and
 * the engine list, and `loading` follows 41's rule
 * (`workspace == null || sessionsLoading(engines)`).
 *
 * The tablet sidebar (105) renders pushed routes through the same
 * `ShellRouteContent`, so this one branch serves both hosts — and reads
 * [LocalSidebarFixtures] first: the gallery and the Robolectric tests
 * provide a fixture workspace so a folder page pushed inside the sidebar
 * shows rows (and its bottom padding can be measured) without a core.
 * Production never provides it.
 */
@Composable
fun FolderRouteContent(route: FolderRoute) {
    val app = LocalContext.current.applicationContext as RobocoApplication
    val fixture = LocalSidebarFixtures.current
    val liveWorkspace by app.workspaceFeed.snapshot.collectAsState()
    val liveEngines by app.workspaceFeed.engines.collectAsState()
    FolderScreen(
        route = route,
        workspace = if (fixture != null) fixture.workspace else liveWorkspace,
        loading = fixture == null && (liveWorkspace == null || sessionsLoading(liveEngines)),
    )
}

// ---------------------------------------------------------------------------
// Previews: pushed onto a NavStackHost (the screen reads LocalNavStack for
// the opener and the pop) inside the GlassMenuHost the ⋯ menu needs, with a
// fake RowActions whose section writes answer Ok — a rename retitles live.
// ---------------------------------------------------------------------------

/** The previews' [RowActions]: the section writes answer `Ok`, the rest no-op. */
private object PreviewRowActions : RowActions {
    private fun ok(onResult: (ActionOutcome) -> Unit) = onResult(ActionOutcome.Ok)

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun createSection(
        name: String,
        engineId: String?,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) = ok(onResult)
    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) = ok(onResult)
    override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
    override fun report(error: Throwable) {}
}

/**
 * The previews' fixture (the ticket's): the "P0" section (3 rows), Pinned
 * (2), Archived (2). FFI records only — no JNA, like the debug fixture
 * `SessionListFixtures`; the list maps them with 37's `SessionRowVM.from`.
 */
private object FolderPreviewFixtures {

    private fun row(
        id: String,
        title: String,
        project: String? = "zeron",
        branch: String? = "main",
        deviceName: String? = null,
    ): SessionRow = SessionRow(
        id = id,
        engineId = "eng1",
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = project?.let { ProjectRef(id = "prj-$it", name = it, colorIndex = 7u) },
        deviceId = "device-eng1",
        deviceName = deviceName,
        deviceOnline = true,
        engineOnline = true,
        harness = "claude-code",
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = branch,
        cwd = null,
        indicator = ChatIndicator.IDLE,
        hostIndicator = ChatIndicator.IDLE,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = "2h",
        createdAtMs = 0L,
        unseen = false,
        archived = false,
        pinned = false,
        sectionId = "eng1:sec-p0",
        pullRequest = null,
        sendState = null,
        parentChatId = null,
    )

    private val veil = row("chat-veil", "Streaming veil on transcript rows")
    private val models = row("chat-models", "Model picker catalog sync", branch = "feat/models")
    private val launchPost = row("chat-launch", "Draft the launch post", project = null, deviceName = "MacBook Pro")
    private val hibernation = row("chat-hibernation", "Durable Object hibernation flush")
    private val scrollPinning = row("chat-scroll-pinning", "Transcript scroll pinning", branch = "scroll-pinning")
    private val deploy = row("chat-deploy", "Wrangler deploy hygiene", project = "edge")
    private val downloads = row("chat-downloads", "Clean up ~/Downloads", project = null, deviceName = "MacBook Pro")

    val sectionRoute = FolderRoute.section("eng1:sec-p0", "P0")
    val pinnedRoute = FolderRoute.pinned()
    val archivedRoute = FolderRoute.archived()

    private fun workspace(front: FrontPage, archived: List<SessionRow>): WorkspaceSnapshot = WorkspaceSnapshot(
        revision = 1uL,
        computedAtMs = 0L,
        pinsReady = false,
        synced = true,
        front = front,
        projects = emptyList(),
        projectless = emptyList(),
        pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
        archived = archived,
        devices = emptyList(),
    )

    val sectionWorkspace: WorkspaceSnapshot = workspace(
        front = FrontPage(
            pinned = emptyList(),
            sections = listOf(
                SectionView(id = "eng1:sec-p0", engineId = "eng1", name = "P0", collapsed = false, sessions = listOf(veil, models, launchPost)),
            ),
            recent = emptyList(),
            groups = emptyList(),
        ),
        archived = emptyList(),
    )

    val pinnedWorkspace: WorkspaceSnapshot = workspace(
        front = FrontPage(
            pinned = listOf(hibernation.copy(pinned = true), scrollPinning.copy(pinned = true)),
            sections = emptyList(),
            recent = emptyList(),
            groups = emptyList(),
        ),
        archived = emptyList(),
    )

    val archivedWorkspace: WorkspaceSnapshot = workspace(
        front = FrontPage(pinned = emptyList(), sections = emptyList(), recent = emptyList(), groups = emptyList()),
        archived = listOf(deploy, downloads),
    )
}

@Composable
private fun FolderScreenPreview(
    appearance: Appearance,
    route: FolderRoute,
    workspace: WorkspaceSnapshot?,
    initialEditing: Boolean = false,
) {
    RobocoTheme(appearance) {
        CompositionLocalProvider(LocalRowActions provides PreviewRowActions) {
            GlassMenuHost {
                val stack = remember(route) {
                    NavStack(TabRootRoute(TabId.Sessions)).apply { push(route, animated = false) }
                }
                NavStackHost(stack = stack, active = true) { entry ->
                    val r = entry.route
                    if (r is FolderRoute) {
                        FolderScreen(
                            route = r,
                            workspace = workspace,
                            loading = false,
                            topInset = 24.dp,
                            initialEditing = initialEditing,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "section light")
@Composable
private fun FolderSectionLightPreview() {
    FolderScreenPreview(Appearance.Light, FolderPreviewFixtures.sectionRoute, FolderPreviewFixtures.sectionWorkspace)
}

@Preview(name = "section dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderSectionDarkPreview() {
    FolderScreenPreview(Appearance.Dark, FolderPreviewFixtures.sectionRoute, FolderPreviewFixtures.sectionWorkspace)
}

@Preview(name = "pinned light")
@Composable
private fun FolderPinnedLightPreview() {
    FolderScreenPreview(Appearance.Light, FolderPreviewFixtures.pinnedRoute, FolderPreviewFixtures.pinnedWorkspace)
}

@Preview(name = "pinned dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderPinnedDarkPreview() {
    FolderScreenPreview(Appearance.Dark, FolderPreviewFixtures.pinnedRoute, FolderPreviewFixtures.pinnedWorkspace)
}

/** Preview (a): the Pinned screen in Edit mode (ticket 47) — handles, no tap. */
@Preview(name = "pinned edit light")
@Composable
private fun FolderPinnedEditLightPreview() {
    FolderScreenPreview(
        Appearance.Light,
        FolderPreviewFixtures.pinnedRoute,
        FolderPreviewFixtures.pinnedWorkspace,
        initialEditing = true,
    )
}

/** Preview (a), dark. */
@Preview(name = "pinned edit dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderPinnedEditDarkPreview() {
    FolderScreenPreview(
        Appearance.Dark,
        FolderPreviewFixtures.pinnedRoute,
        FolderPreviewFixtures.pinnedWorkspace,
        initialEditing = true,
    )
}

@Preview(name = "archived light")
@Composable
private fun FolderArchivedLightPreview() {
    FolderScreenPreview(Appearance.Light, FolderPreviewFixtures.archivedRoute, FolderPreviewFixtures.archivedWorkspace)
}

@Preview(name = "archived dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderArchivedDarkPreview() {
    FolderScreenPreview(Appearance.Dark, FolderPreviewFixtures.archivedRoute, FolderPreviewFixtures.archivedWorkspace)
}
