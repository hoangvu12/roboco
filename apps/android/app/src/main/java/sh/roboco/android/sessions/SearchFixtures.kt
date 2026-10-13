package sh.roboco.android.sessions

import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.SessionRow

/**
 * The Search screen's fixtures (android-ui-parity #49) — the ticket's
 * rows: the six-row Recent list, the three "tool" hits (one archived) and
 * the four [SearchResults] states the screen tests, goldens and the
 * gallery section switch between.
 *
 * Main source set on purpose (the ticket's Files table): the previews
 * (main), the Robolectric / Roborazzi tests (`testDebugUnitTest`) and the
 * debug gallery all reach it. Everything is a plain FFI record — no JNA —
 * built like ticket 37's `SessionListFixtures` rows: project "roboco"
 * (colour index 6), harness `claude-code`, branch `main`, no PR, not
 * unseen / pinned / send-failed; only the status and time label differ.
 */
internal object SearchFixtures {

    // ------------------------------------------------------------ the rows

    /**
     * `front.recent` — the flat Recent list the empty query shows, in
     * snapshot order (a Working row on top so the list shows the live
     * status glyph).
     */
    val recent: List<SessionRow> = listOf(
        row("chat-veil", "Streaming veil on transcript rows", ChatIndicator.WORKING, "now"),
        row("chat-models", "Model picker catalog sync", ChatIndicator.IDLE, "12m"),
        row("chat-tool-colors", "Tool group header colors", ChatIndicator.COMPLETED, "1h"),
        row("chat-hibernation", "Durable Object hibernation flush", ChatIndicator.IDLE, "3h"),
        row("chat-keyboard", "Keyboard avoidance for composer", ChatIndicator.IDLE, "1d"),
        row("chat-launch", "Draft the launch post", ChatIndicator.IDLE, "2d"),
    )

    /**
     * The "tool" hits, core order: two active rows then the archived
     * "Clean up tool output" (archived hits stay in the list — the core
     * halves their score, never filters them).
     */
    val hits: List<SessionRow> = listOf(
        row("chat-tool-colors", "Tool group header colors", ChatIndicator.COMPLETED, "1h"),
        row("chat-tool-rail", "Tool activity rail draw-in", ChatIndicator.IDLE, "2d"),
        row("chat-tool-output", "Clean up tool output", ChatIndicator.IDLE, "3w", archived = true),
    )

    /** The one archived hit of [hits] — the per-row archived-actions case. */
    val archivedIds: Set<String> = setOf("chat-tool-output")

    // ------------------------------------------------------ the states

    /** Snapshot present, query empty: the Recent rows. */
    val recentResults = SearchResults(SearchResults.Mode.Recent, "", recent, emptySet())

    /** Snapshot present, query "tool": the hits in core order. */
    val hitsResults = SearchResults(SearchResults.Mode.Hits, "tool", hits, archivedIds)

    /** A finished search with zero hits — the no-results view. */
    val noResults = SearchResults(SearchResults.Mode.Hits, "zzqx", emptyList(), emptySet())

    /** No snapshot yet — 41's loading spinner. */
    val loading = SearchResults.Loading

    // ------------------------------------------------------------ builder

    private fun row(
        id: String,
        title: String,
        indicator: ChatIndicator,
        timeLabel: String,
        archived: Boolean = false,
    ): SessionRow = SessionRow(
        id = id,
        engineId = "eng1",
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = ProjectRef(id = "prj-roboco", name = "roboco", colorIndex = 6u),
        deviceId = "device-eng1",
        deviceName = null,
        deviceOnline = true,
        engineOnline = true,
        harness = "claude-code",
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = "main",
        cwd = null,
        indicator = indicator,
        hostIndicator = indicator,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = timeLabel,
        createdAtMs = 0L,
        unseen = false,
        archived = archived,
        pinned = false,
        sectionId = null,
        pullRequest = null,
        sendState = null,
        parentChatId = null,
    )
}
