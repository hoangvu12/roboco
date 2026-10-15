package sh.roboco.android.shell

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionGroup
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The live-counts data and formatters (android-ui-parity #27): the summary
 * rule (`MainTabController.swift:322-330`), the dedup loop
 * (`AppModel.swift:367-376`), the revision gate, and every pitfall the
 * ticket names (archived, groups, host_indicator, collapsed sections,
 * first-occurrence dedupe).
 *
 * Plain JUnit 4, no Robolectric: the uniffi records and enums are plain
 * Kotlin data classes whose class initialisation touches no JNA (only the
 * FFI call functions do — `TranscriptPaletteTest` relies on the same).
 *
 * Expected strings are explicit literals from the ticket's Spec table —
 * the middle-dot assertions use `\\u00B7` escapes so no editor can mangle
 * the separator along with the code under test.
 */
class LiveCountsTest {

    // ------------------------------------------------------------ fixtures

    /** A row with only the fields the derivation reads set apart. */
    private fun row(
        id: String,
        indicator: ChatIndicator,
        hostIndicator: ChatIndicator = indicator,
    ): SessionRow = SessionRow(
        id = id,
        engineId = "engine:v1:alpha",
        revision = 1uL,
        title = "Session",
        hasTitle = true,
        preview = null,
        project = null,
        deviceId = "device",
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
        indicator = indicator,
        hostIndicator = hostIndicator,
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

    private fun section(id: String, vararg rows: SessionRow, collapsed: Boolean = false): SectionView =
        SectionView(
            id = id,
            engineId = "engine:v1:alpha",
            name = id,
            collapsed = collapsed,
            sessions = rows.toList(),
        )

    private fun front(
        recent: List<SessionRow> = emptyList(),
        pinned: List<SessionRow> = emptyList(),
        sections: List<SectionView> = emptyList(),
        groups: List<SessionGroup> = emptyList(),
    ): FrontPage = FrontPage(
        pinned = pinned,
        sections = sections,
        recent = recent,
        groups = groups,
    )

    private fun snapshot(
        revision: ULong,
        front: FrontPage = front(),
        archived: List<SessionRow> = emptyList(),
    ): WorkspaceSnapshot = WorkspaceSnapshot(
        revision = revision,
        computedAtMs = 0L,
        pinsReady = false,
        synced = false,
        front = front,
        projects = emptyList(),
        projectless = emptyList(),
        pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
        archived = archived,
        devices = emptyList(),
    )

    // ------------------------------------------------------------- parts

    @Test
    fun theEmptyStateIsTheDefault() {
        assertEquals(LiveCounts(working = 0, awaiting = 0), LiveCounts())
    }

    @Test
    fun summaryPartsFollowTheIosRule() {
        assertEquals(emptyList<String>(), LiveCounts().summaryParts())
        assertEquals(listOf("2 working"), LiveCounts(working = 2).summaryParts())
        assertEquals(listOf("1 needs you"), LiveCounts(awaiting = 1).summaryParts())
        assertEquals(listOf("3 need you"), LiveCounts(awaiting = 3).summaryParts())
        assertEquals(
            listOf("2 working", "1 needs you"),
            LiveCounts(working = 2, awaiting = 1).summaryParts(),
        )
    }

    @Test
    fun theSeparatorIsSpaceMiddotSpace() {
        assertEquals("", LiveCounts().summaryText())
        assertEquals("2 working", LiveCounts(working = 2).summaryText())
        assertEquals("1 needs you", LiveCounts(awaiting = 1).summaryText())
        assertEquals("3 need you", LiveCounts(awaiting = 3).summaryText())
        // space, U+00B7 MIDDLE DOT, space — escaped, so the check itself
        // cannot be mangled with the source it pins.
        assertEquals("2 working \u00B7 1 needs you", LiveCounts(working = 2, awaiting = 1).summaryText())
    }

    @Test
    fun accessibilityLabelsJoinWithCommaSpace() {
        assertEquals("New session", LiveCounts().accessibilityLabel())
        assertEquals("New session, 2 working", LiveCounts(working = 2).accessibilityLabel())
        assertEquals("New session, 1 needs you", LiveCounts(awaiting = 1).accessibilityLabel())
        assertEquals("New session, 3 need you", LiveCounts(awaiting = 3).accessibilityLabel())
        assertEquals(
            "New session, 2 working, 1 needs you",
            LiveCounts(working = 2, awaiting = 1).accessibilityLabel(),
        )
    }

    @Test
    fun glyphKinds() {
        assertNull(LiveCounts().glyph())
        assertEquals(LiveGlyph.Spinner, LiveCounts(working = 2).glyph())
        assertEquals(LiveGlyph.InputDot, LiveCounts(awaiting = 1).glyph())
        assertEquals(LiveGlyph.InputDot, LiveCounts(awaiting = 3).glyph())
        assertEquals(LiveGlyph.Spinner, LiveCounts(working = 2, awaiting = 1).glyph())
    }

    // ------------------------------------------------------------ counting

    @Test
    fun duplicateIdsCountOnce() {
        // A WORKING id in pinned and again in a section; an AWAITING_INPUT
        // id in recent and again in a section: one each.
        val f = front(
            recent = listOf(row("a", ChatIndicator.AWAITING_INPUT)),
            pinned = listOf(row("w", ChatIndicator.WORKING)),
            sections = listOf(
                section("s", row("w", ChatIndicator.WORKING), row("a", ChatIndicator.AWAITING_INPUT)),
            ),
        )
        assertEquals(LiveCounts(working = 1, awaiting = 1), liveCountsOf(f))
    }

    @Test
    fun countLiveKeepsTheFirstOccurrenceOfAnId() {
        // The testable core, rows in any order: the first (id, indicator)
        // pair decides.
        assertEquals(
            LiveCounts(working = 1),
            countLive(sequenceOf("x" to ChatIndicator.WORKING, "x" to ChatIndicator.AWAITING_INPUT)),
        )
        assertEquals(
            LiveCounts(awaiting = 1),
            countLive(sequenceOf("x" to ChatIndicator.AWAITING_INPUT, "x" to ChatIndicator.WORKING)),
        )
    }

    @Test
    fun erroredCompletedAndIdleCountAsNeither() {
        assertEquals(
            LiveCounts(),
            liveCountsOf(
                front(
                    recent = listOf(
                        row("e", ChatIndicator.ERRORED),
                        row("c", ChatIndicator.COMPLETED),
                        row("i", ChatIndicator.IDLE),
                    ),
                ),
            ),
        )
        assertEquals(
            LiveCounts(),
            countLive(
                sequenceOf(
                    "e" to ChatIndicator.ERRORED,
                    "c" to ChatIndicator.COMPLETED,
                    "i" to ChatIndicator.IDLE,
                ),
            ),
        )
    }

    @Test
    fun collapsedSectionsStillCount() {
        // SectionView.sessions is filled whether `collapsed` is true or not.
        val f = front(sections = listOf(section("s", row("w", ChatIndicator.WORKING), collapsed = true)))
        assertEquals(LiveCounts(working = 1), liveCountsOf(f))
    }

    @Test
    fun groupsNeverAddToTheCounts() {
        // `front.groups` is a regrouping of `recent`: counting it would
        // double count. The derivation must not touch it.
        val f = FrontPage(
            pinned = emptyList(),
            sections = emptyList(),
            recent = listOf(row("a", ChatIndicator.WORKING)),
            groups = listOf(
                SessionGroup(id = "g", name = "G", sessions = listOf(row("a", ChatIndicator.WORKING))),
            ),
        )
        assertEquals(LiveCounts(working = 1), liveCountsOf(f))
    }

    @Test
    fun theStalenessGatedIndicatorCountsNotTheHostOne() {
        // The derivation reads `indicator`, never `host_indicator`.
        assertEquals(
            LiveCounts(working = 1),
            liveCountsOf(front(recent = listOf(row("x", ChatIndicator.WORKING, hostIndicator = ChatIndicator.IDLE)))),
        )
    }

    @Test
    fun archivedRowsNeverCount() = runTest {
        // A row only in archived (WORKING) is not counted — through the
        // front-page derivation and through the flow's snapshot.
        assertEquals(LiveCounts(), liveCountsOf(front()))
        assertEquals(
            listOf(LiveCounts()),
            liveCountsFlow(flowOf(snapshot(1uL, archived = listOf(row("z", ChatIndicator.WORKING))))).toList(),
        )
    }

    // --------------------------------------------------------------- flow

    @Test
    fun theRevisionGateCoalescesBursts() = runTest {
        // Two snapshots with the same revision but different rows: one
        // emission — the gate drops the second before the map.
        val s1 = snapshot(1uL, front(recent = listOf(row("a", ChatIndicator.WORKING))))
        val s2 = snapshot(
            1uL,
            front(recent = listOf(row("a", ChatIndicator.WORKING), row("b", ChatIndicator.AWAITING_INPUT))),
        )
        assertEquals(listOf(LiveCounts(working = 1)), liveCountsFlow(flowOf(s1, s2)).toList())
    }

    @Test
    fun sameCountsAtDifferentRevisionsEmitOnce() = runTest {
        val s1 = snapshot(1uL, front(recent = listOf(row("a", ChatIndicator.WORKING))))
        val s2 = snapshot(2uL, front(recent = listOf(row("a", ChatIndicator.WORKING))))
        assertEquals(listOf(LiveCounts(working = 1)), liveCountsFlow(flowOf(s1, s2)).toList())
    }

    @Test
    fun aNullSnapshotEmitsTheEmptyCounts() = runTest {
        assertEquals(listOf(LiveCounts()), liveCountsFlow(flowOf<WorkspaceSnapshot?>(null)).toList())
    }

    @Test
    fun countsFollowRevisionsWhenTheyChange() = runTest {
        val s1 = snapshot(1uL, front(recent = listOf(row("a", ChatIndicator.WORKING))))
        val s2 = snapshot(
            2uL,
            front(recent = listOf(row("a", ChatIndicator.WORKING), row("b", ChatIndicator.AWAITING_INPUT))),
        )
        val gone: WorkspaceSnapshot? = null
        assertEquals(
            listOf(
                LiveCounts(),
                LiveCounts(working = 1),
                LiveCounts(working = 1, awaiting = 1),
                LiveCounts(),
            ),
            liveCountsFlow(flowOf(gone, s1, s2, gone)).toList(),
        )
    }
}
