package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SessionRow

/**
 * The sidebar's query → content rule (android-ui-parity #105) — plain
 * JUnit: [sidebarListContent] is the pure port of iOS
 * `SessionsViewController.buildSections`'s query branch
 * (`SessionListController.swift:335-357`) with 49's async source. The
 * rows stay FFI records (no mapping happens here — 41 maps them with 37's
 * `from`).
 */
class SidebarQueryTest {

    private fun row(id: String): SessionRow =
        SessionRow(
            id = id,
            engineId = "A",
            revision = 1uL,
            title = id,
            hasTitle = true,
            preview = null,
            project = null,
            deviceId = "device-A",
            deviceName = null,
            deviceOnline = true,
            engineOnline = true,
            harness = null,
            harnessLabel = null,
            model = null,
            modelLabel = null,
            reasoning = null,
            branch = null,
            cwd = null,
            indicator = ChatIndicator.IDLE,
            hostIndicator = ChatIndicator.IDLE,
            workingSinceMs = null,
            lastActivityMs = 0L,
            timeLabel = "now",
            createdAtMs = 0L,
            unseen = false,
            archived = false,
            pinned = false,
            sectionId = null,
            pullRequest = null,
            sendState = null,
            parentChatId = null,
        )

    private fun hits(query: String): SearchResults =
        SearchResults(SearchResults.Mode.Hits, query, listOf(row("chat-tool-colors")), emptySet())

    // ------------------------------------------------------------- the rule

    @Test
    fun anEmptyQueryIsTheFrontPageWithoutResults() {
        assertEquals(SidebarListContent.FrontPage, sidebarListContent("", null))
    }

    @Test
    fun aWhitespaceOnlyQueryIsTheFrontPageEvenWithHits() {
        // iOS: `query.trimmingCharacters(in: .whitespaces).isEmpty` gates
        // the results branch — "   " is the full front page, never
        // "No Results".
        assertEquals(SidebarListContent.FrontPage, sidebarListContent("   ", hits("tool")))
    }

    @Test
    fun aQueryWithoutResultsYetIsTheFrontPage() {
        // First search in flight: the previous content (here: the front
        // page) stays on screen — no blank frame.
        assertEquals(SidebarListContent.FrontPage, sidebarListContent("tool", null))
    }

    @Test
    fun loadingAndRecentAreTheFrontPage() {
        assertEquals(
            SidebarListContent.FrontPage,
            sidebarListContent("tool", SearchResults.Loading),
        )
        assertEquals(
            SidebarListContent.FrontPage,
            sidebarListContent(
                "tool",
                SearchResults(SearchResults.Mode.Recent, "", listOf(row("r1")), emptySet()),
            ),
        )
    }

    @Test
    fun hitsReplaceTheFrontPage() {
        assertEquals(SidebarListContent.Results(hits("tool")), sidebarListContent("tool", hits("tool")))
    }

    @Test
    fun staleHitsStayWhileTheNewerSearchRuns() {
        // "toolx" is being searched; the "tool" hits of the previous query
        // are still the latest StateFlow value — keep them on screen.
        assertEquals(SidebarListContent.Results(hits("tool")), sidebarListContent("toolx", hits("tool")))
    }

    @Test
    fun anEmptyQueryNeverShowsResults() {
        // Clearing the field drops the hits at once, whatever the model
        // still holds.
        assertEquals(SidebarListContent.FrontPage, sidebarListContent("", hits("tool")))
    }
}
