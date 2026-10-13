package sh.roboco.android.sessions

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The shared folder-screen fixtures (android-ui-parity #45) — the ticket's
 * fixture: the "P0" section (3 rows), Pinned (2) and Archived (2), plus a
 * 12-row variant with scroll range for the scroll-preservation test.
 *
 * Debug source set on purpose (the same visibility as ticket 41's
 * `SessionListFixtures`): the design gallery and the Robolectric /
 * Roborazzi tests (`testDebugUnitTest`) both compile against it; the
 * plain-JVM route tests build their own tiny snapshots through
 * `WorkspaceFixtures`. Everything here is a plain FFI record — no JNA.
 */
internal object FolderFixtures {

    /** The section's engine-scoped id (android-app#07) and its raw name. */
    const val SECTION_ID = "eng1:sec-p0"
    const val SECTION_NAME = "P0"

    // ------------------------------------------------------------ the rows

    private fun row(
        id: String,
        title: String,
        project: String? = "zeron",
        branch: String? = "main",
        deviceName: String? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        sectionId: String? = SECTION_ID,
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
        archived = archived,
        pinned = pinned,
        sectionId = sectionId,
        pullRequest = null,
        sendState = null,
        parentChatId = null,
    )

    /** "Streaming veil on transcript rows" (project "zeron", branch "main"). */
    val veil = row("chat-veil", "Streaming veil on transcript rows")

    /** "Model picker catalog sync" ("zeron", "feat/models"). */
    val models = row("chat-models", "Model picker catalog sync", branch = "feat/models")

    /** "Draft the launch post" (no project, device "MacBook Pro"). */
    val launchPost = row("chat-launch", "Draft the launch post", project = null, deviceName = "MacBook Pro")

    /** The Pinned fixture: 2 rows. */
    val pinnedRows = listOf(
        row("chat-hibernation", "Durable Object hibernation flush", pinned = true),
        row("chat-scroll-pinning", "Transcript scroll pinning", branch = "scroll-pinning", pinned = true),
    )

    /** The Archived fixture: 2 rows. */
    val archivedRows = listOf(
        row("chat-deploy", "Wrangler deploy hygiene", project = "edge", archived = true, sectionId = null),
        row("chat-downloads", "Clean up ~/Downloads", project = null, deviceName = "MacBook Pro", archived = true, sectionId = null),
    )

    /** 12 more rows so the section list has scroll range for the tests. */
    val scrollRows: List<SessionRow> = (0 until 12).map { i ->
        row("chat-scroll-$i", "Scroll fixture row ${i + 1}")
    }

    // ---------------------------------------------------------- the routes

    val sectionRoute = FolderRoute.section(SECTION_ID, SECTION_NAME)
    val pinnedRoute = FolderRoute.pinned()
    val archivedRoute = FolderRoute.archived()

    // ------------------------------------------------------- the workspaces

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

    /** The "P0" section with [rows] (the 3-row fixture by default) and [name]. */
    fun sectionWorkspace(
        rows: List<SessionRow> = listOf(veil, models, launchPost),
        name: String = SECTION_NAME,
    ): WorkspaceSnapshot = workspace(
        front = FrontPage(
            pinned = emptyList(),
            sections = listOf(
                SectionView(id = SECTION_ID, engineId = "eng1", name = name, collapsed = false, sessions = rows),
            ),
            recent = emptyList(),
            groups = emptyList(),
        ),
        archived = emptyList(),
    )

    /** The Pinned folder with [rows] (2 by default; `emptyList()` = empty Pinned). */
    fun pinnedWorkspace(rows: List<SessionRow> = pinnedRows): WorkspaceSnapshot = workspace(
        front = FrontPage(pinned = rows, sections = emptyList(), recent = emptyList(), groups = emptyList()),
        archived = emptyList(),
    )

    /** The Archived folder with [rows] (2 by default; `emptyList()` = the empty archive). */
    fun archivedWorkspace(rows: List<SessionRow> = archivedRows): WorkspaceSnapshot = workspace(
        front = FrontPage(pinned = emptyList(), sections = emptyList(), recent = emptyList(), groups = emptyList()),
        archived = rows,
    )

    /** The section gone entirely (deleted elsewhere): an empty list, the screen stays. */
    fun noSectionWorkspace(): WorkspaceSnapshot = workspace(
        front = FrontPage(pinned = emptyList(), sections = emptyList(), recent = listOf(veil, models, launchPost), groups = emptyList()),
        archived = emptyList(),
    )
}
