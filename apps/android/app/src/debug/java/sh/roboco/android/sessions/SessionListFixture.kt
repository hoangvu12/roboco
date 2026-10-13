package sh.roboco.android.sessions

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SessionRow

/**
 * The shared session-list fixtures (android-ui-parity #41) —
 * android-app#20's iOS reference rows, as in `1-off.png` below the
 * (scrolled-away) Pinned section: the "P0" and "Mobile" project groups
 * and the Recent list. Built as the FFI [SessionRow] records (plain data
 * classes — no JNA), wrapped in ticket 40's [ListSection]s.
 *
 * Debug source set on purpose (the same visibility as ticket 22's
 * `LinearFontScaleDensity`): the design gallery and the Robolectric /
 * Roborazzi tests (`testDebugUnitTest`) both compile against it; the
 * plain-JVM item tests pass [toVm] so they never touch the native core.
 *
 * Project colour indexes are the core's `project_color_index` (FNV-1a of
 * the project name mod 8): zeron 7, edge 4, "Zeron iOS" 6, blog 1; the
 * project-less "Clean up ~/Downloads" row falls back to the home index
 * (6, teal) via `SessionRowVM.from`. Harness ids are the engine's
 * `HarnessId` slugs (ticket 13): `codex`, `claude-code`, or none (the
 * Claude mark).
 */
internal object SessionListFixtures {

    // ------------------------------------------------------------ the rows

    private val toolColors = row(
        id = "chat-tool-colors",
        title = "Tool group header colors",
        harness = "codex",
        indicator = ChatIndicator.COMPLETED,
        unseen = true,
        project = ProjectRef(id = "prj-zeron", name = "zeron", colorIndex = 7u),
        branch = "fix/tool-colors",
        pullRequest = pr(77, PullRequestState.CLOSED, "Tool group header colors", "fix/tool-colors"),
        timeLabel = "4h",
    )

    private val hibernation = row(
        id = "chat-hibernation",
        title = "Durable Object hibernation flush",
        harness = "claude-code",
        indicator = ChatIndicator.ERRORED,
        unseen = true,
        project = ProjectRef(id = "prj-edge", name = "edge", colorIndex = 4u),
        branch = "main",
        timeLabel = "33m",
    )

    /** The live variant: the same row working, so "P0" derives `Spinner`. */
    private val hibernationWorking = hibernation.copy(indicator = ChatIndicator.WORKING)

    private val scrollPinning = row(
        id = "chat-scroll-pinning",
        title = "Transcript scroll pinning",
        harness = "codex",
        indicator = ChatIndicator.COMPLETED,
        unseen = true,
        project = ProjectRef(id = "prj-zeron-ios", name = "Zeron iOS", colorIndex = 6u),
        branch = "scroll-pinning",
        pullRequest = pr(102, PullRequestState.OPEN, "Transcript scroll pinning", "scroll-pinning"),
        timeLabel = "2h",
    )

    private val keyboard = row(
        id = "chat-keyboard",
        title = "Keyboard avoidance for composer",
        harness = null,
        indicator = ChatIndicator.IDLE,
        project = ProjectRef(id = "prj-zeron-ios", name = "Zeron iOS", colorIndex = 6u),
        branch = "main",
        timeLabel = "5h",
    )

    /** The third session row overall — the gallery's "Current = row 3". */
    val currentRow: SessionRow = scrollPinning

    private val i18n = row(
        id = "chat-i18n",
        title = "多言語テキストのレイアウト",
        indicator = ChatIndicator.IDLE,
        project = ProjectRef(id = "prj-zeron", name = "zeron", colorIndex = 7u),
        branch = "main",
        timeLabel = "10m",
    )

    private val downloads = row(
        id = "chat-downloads",
        title = "Clean up ~/Downloads",
        indicator = ChatIndicator.IDLE,
        project = null,
        deviceName = "MacBook Pro",
        timeLabel = "2h",
    )

    private val deploy = row(
        id = "chat-deploy",
        title = "Wrangler deploy hygiene",
        indicator = ChatIndicator.IDLE,
        project = ProjectRef(id = "prj-edge", name = "edge", colorIndex = 4u),
        branch = "main",
        timeLabel = "1d",
    )

    private val launchPost = row(
        id = "chat-launch-post",
        title = "Draft the launch post",
        indicator = ChatIndicator.IDLE,
        project = ProjectRef(id = "prj-blog", name = "blog", colorIndex = 1u),
        timeLabel = "5d",
    )

    /** Recent in reference order; "Shuffle Recent" reshuffles a copy. */
    val recentRows: List<SessionRow> = listOf(i18n, downloads, deploy, launchPost)

    // --------------------------------------------------------- the sections

    /**
     * The three sections. [workingLive] swaps "Durable Object hibernation
     * flush" to working, so `p0`'s header derives `SectionLive.Spinner`
     * (`buildSessionListItems` computes it — nothing is pre-folded here).
     * [recent] lets the gallery shuffle the Recent rows.
     */
    fun sections(workingLive: Boolean = false, recent: List<SessionRow> = recentRows): List<ListSection> =
        listOf(
            ListSection(
                id = "p0",
                header = "P0",
                rows = listOf(toolColors, if (workingLive) hibernationWorking else hibernation),
            ),
            ListSection(id = "mobile", header = "Mobile", rows = listOf(scrollPinning, keyboard)),
            ListSection(id = "recent", header = "Recent", rows = recent),
        )

    /**
     * The folder + plain-header demo: non-collapsible, so every header is
     * a `PlainHeader` and the first section carries the one folder
     * ("Pinned 2", symbol `pin`) — the API slot no current caller emits.
     */
    fun folderPlainSections(): List<ListSection> =
        listOf(
            ListSection(
                id = "pinned",
                header = "Pinned",
                folders = listOf(FolderRowVM(id = "pinned-folder", name = "Pinned", count = 2, symbol = "pin")),
                rows = listOf(toolColors, hibernation),
            ),
            ListSection(id = "mobile", header = "Mobile", rows = listOf(scrollPinning, keyboard)),
            ListSection(id = "recent", header = "Recent", rows = recentRows),
        )

    /**
     * The plain-JVM `toVm`: 37's `SessionRowVM.from` with `homeColorIndex`
     * passed explicitly (the lazy default resolves through the native
     * core, which plain-JVM tests cannot load).
     */
    fun toVm(r: SessionRow): SessionRowVM = SessionRowVM.from(r, homeColorIndex = 6)

    // ------------------------------------------------------------ builders

    private fun pr(number: Long, state: PullRequestState, title: String, branch: String): PullRequest =
        PullRequest(
            provider = "github",
            number = number.toULong(),
            title = title,
            url = "https://github.com/zeronsh/zeron/pull/$number",
            state = state,
            baseRef = "main",
            headRef = branch,
        )

    private fun row(
        id: String,
        title: String,
        harness: String? = null,
        indicator: ChatIndicator = ChatIndicator.IDLE,
        unseen: Boolean = false,
        project: ProjectRef? = null,
        deviceName: String? = null,
        branch: String? = null,
        pullRequest: PullRequest? = null,
        timeLabel: String = "now",
    ): SessionRow = SessionRow(
        id = id,
        engineId = "A",
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = project,
        deviceId = "device-A",
        deviceName = deviceName,
        deviceOnline = true,
        engineOnline = true,
        harness = harness,
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = branch,
        cwd = null,
        indicator = indicator,
        hostIndicator = indicator,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = timeLabel,
        createdAtMs = 0L,
        unseen = unseen,
        archived = false,
        pinned = false,
        sectionId = null,
        pullRequest = pullRequest,
        sendState = null,
        parentChatId = null,
    )
}
