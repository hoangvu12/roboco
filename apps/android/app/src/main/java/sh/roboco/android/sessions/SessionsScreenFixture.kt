package sh.roboco.android.sessions

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SessionRow

/**
 * The Sessions tab root's fixture (android-ui-parity #46) — the ticket's
 * `1-off.png` set, shared by the previews (main), the design gallery
 * (debug) and the Robolectric / Roborazzi tests: Pinned (2), the user
 * sections "P0" (2) and "Mobile" (2) and Recent (4). The grouped variant
 * (the `3-on.png` set) replaces Recent with the four project groups.
 *
 * Main source set (like `wallpaperFixture()`, for the same reason): the
 * previews live in main and the debug / test source sets compile against
 * it. Everything is a plain FFI record — no JNA.
 *
 * The "Zeron iOS" / "zeron" project names are user data in the capture,
 * kept verbatim. Project colour indexes are the core's
 * `project_color_index` (FNV-1a of the name mod 8): zeron 7, edge 4,
 * "Zeron iOS" 6, blog 1; the project-less rows fall back to the home
 * index (6) via `SessionRowVM.from`.
 */
internal object SessionsScreenFixtures {

    /** The user sections' ids and raw names (the Rename prefill source). */
    val sectionNames: Map<String, String> = mapOf(
        "sec-p0" to "P0",
        "sec-mobile" to "Mobile",
    )

    // ------------------------------------------------------------ the rows

    /** "Streaming veil on transcript rows" (claude, Done) — a pinned row. */
    private val veil = row(
        id = "chat-veil",
        title = "Streaming veil on transcript rows",
        indicator = ChatIndicator.COMPLETED,
        project = "zeron",
        branch = "main",
        pinned = true,
        timeLabel = "2h",
    )

    /** "Model picker catalog sync" (zeron, `catalog-sync`) — a pinned row. */
    private val models = row(
        id = "chat-models",
        title = "Model picker catalog sync",
        project = "zeron",
        branch = "catalog-sync",
        pinned = true,
        timeLabel = "4h",
    )

    private val toolColors = row(
        id = "chat-tool-colors",
        title = "Tool group header colors",
        harness = "codex",
        indicator = ChatIndicator.COMPLETED,
        unseen = true,
        project = "zeron",
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
        project = "edge",
        branch = "main",
        timeLabel = "33m",
    )

    private val scrollPinning = row(
        id = "chat-scroll-pinning",
        title = "Transcript scroll pinning",
        harness = "codex",
        indicator = ChatIndicator.COMPLETED,
        unseen = true,
        project = "Zeron iOS",
        branch = "scroll-pinning",
        pullRequest = pr(102, PullRequestState.OPEN, "Transcript scroll pinning", "scroll-pinning"),
        timeLabel = "2h",
    )

    private val keyboard = row(
        id = "chat-keyboard",
        title = "Keyboard avoidance for composer",
        project = "Zeron iOS",
        branch = "main",
        timeLabel = "5h",
    )

    private val i18n = row(
        id = "chat-i18n",
        title = "多言語テキストのレイアウト",
        project = "zeron",
        branch = "main",
        timeLabel = "10m",
    )

    private val downloads = row(
        id = "chat-downloads",
        title = "Clean up ~/Downloads",
        project = null,
        deviceName = "MacBook Pro",
        timeLabel = "2h",
    )

    private val deploy = row(
        id = "chat-deploy",
        title = "Wrangler deploy hygiene",
        project = "edge",
        branch = "main",
        timeLabel = "1d",
    )

    private val launchPost = row(
        id = "chat-launch-post",
        title = "Draft the launch post",
        project = "blog",
        branch = "main",
        timeLabel = "5d",
    )

    // --------------------------------------------------------- the sections

    /**
     * The `1-off.png` set: Pinned, "P0", "Mobile", Recent (Group by
     * Project off).
     */
    fun sections(): List<ListSection> = listOf(
        ListSection(id = FrontPageSections.PINNED, header = FrontPageSections.PINNED_TITLE, rows = listOf(veil, models)),
        ListSection(id = "sec-p0", header = "P0", rows = listOf(toolColors, hibernation)),
        ListSection(id = "sec-mobile", header = "Mobile", rows = listOf(scrollPinning, keyboard)),
        ListSection(id = FrontPageSections.RECENT, header = FrontPageSections.RECENT_TITLE, rows = recentRows),
    )

    /**
     * The `3-on.png` set: Recent is replaced by the groups "zeron",
     * "Home · MacBook Pro", "edge" and "blog" (Group by Project on). The
     * group ids are the core's (`project:<key>` / `home:<device>`), so the
     * header-menu classification sees them as project groups → no menu.
     */
    fun groupedSections(): List<ListSection> = listOf(
        ListSection(id = FrontPageSections.PINNED, header = FrontPageSections.PINNED_TITLE, rows = listOf(veil, models)),
        ListSection(id = "sec-p0", header = "P0", rows = listOf(toolColors, hibernation)),
        ListSection(id = "sec-mobile", header = "Mobile", rows = listOf(scrollPinning, keyboard)),
        ListSection(id = "project:zeron", header = "zeron", rows = listOf(i18n)),
        ListSection(id = "home:device-A", header = "Home · MacBook Pro", rows = listOf(downloads)),
        ListSection(id = "project:edge", header = "edge", rows = listOf(deploy)),
        ListSection(id = "project:blog", header = "blog", rows = listOf(launchPost)),
    )

    /** Recent in reference order (the 1-off set's fourth section). */
    val recentRows: List<SessionRow> = listOf(i18n, downloads, deploy, launchPost)

    /**
     * The 1-off set with Recent repeated [repeat] times (distinct ids, so
     * the LazyColumn keys stay unique) — enough scroll range for the hero
     * fade tests and the `wallpaper_half` golden, whose 377.57 dp of
     * travel exceed the reference set's two dp.
     */
    fun longSections(times: Int = 4): List<ListSection> {
        val recent = buildList {
            repeat(times) { n ->
                recentRows.forEach { row -> add(row.copy(id = "${row.id}-$n")) }
            }
        }
        return sections().dropLast(1) +
            ListSection(id = FrontPageSections.RECENT, header = FrontPageSections.RECENT_TITLE, rows = recent)
    }

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
        harness: String? = "claude-code",
        indicator: ChatIndicator = ChatIndicator.IDLE,
        unseen: Boolean = false,
        project: String? = "zeron",
        deviceName: String? = null,
        branch: String? = "main",
        pullRequest: PullRequest? = null,
        pinned: Boolean = false,
        timeLabel: String = "now",
    ): SessionRow = SessionRow(
        id = id,
        engineId = "A",
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = project?.let { ProjectRef(id = "prj-$it", name = it, colorIndex = colorOf(it)) },
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
        pinned = pinned,
        sectionId = null,
        pullRequest = pullRequest,
        sendState = null,
        parentChatId = null,
    )

    /**
     * The core's `project_color_index` values for the fixture's four
     * projects (FNV-1a of the name mod 8 — the same map ticket 41's
     * fixture uses): zeron 7, edge 4, "Zeron iOS" 6, blog 1.
     */
    private fun colorOf(name: String): UInt = when (name) {
        "zeron" -> 7u
        "edge" -> 4u
        "Zeron iOS" -> 6u
        "blog" -> 1u
        else -> 0u
    }
}
