package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.PrState
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SendState
import uniffi.roboco_core.SessionRow

/**
 * `SessionRowVM.from` and `sessionRowA11yLabel` (android-ui-parity #37):
 * the FFI row → view-model mapper (`AppModel.vm`, `AppModel.swift:450-474`)
 * and the row label (`SessionCell.swift:152`).
 *
 * Plain JUnit 4, no Robolectric: the uniffi records and enums are plain
 * Kotlin data classes whose class initialisation touches no JNA (only the
 * FFI call functions do — `LiveCountsTest` relies on the same).
 * [HomeColorIndex] is therefore never touched: every `from` call passes
 * `homeColorIndex = 6` explicitly.
 */
class SessionRowVMTest {

    // ------------------------------------------------------------ fixtures

    private fun row(
        id: String = "abc",
        project: ProjectRef? = null,
        deviceName: String? = null,
        indicator: ChatIndicator = ChatIndicator.IDLE,
        pullRequest: PullRequest? = null,
        sendState: SendState? = null,
        title: String = "T",
        unseen: Boolean = false,
        pinned: Boolean = false,
        timeLabel: String = "now",
        harness: String? = null,
        branch: String? = null,
    ): SessionRow = SessionRow(
        id = id,
        engineId = "engine:v1:alpha",
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = project,
        deviceId = "device",
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
        hostIndicator = ChatIndicator.IDLE,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = timeLabel,
        createdAtMs = 0L,
        unseen = unseen,
        archived = false,
        pinned = pinned,
        sectionId = null,
        pullRequest = pullRequest,
        sendState = sendState,
        parentChatId = null,
    )

    private fun pr(number: ULong, state: PullRequestState): PullRequest = PullRequest(
        provider = "github",
        number = number,
        title = "PR",
        url = "https://example.invalid/1",
        state = state,
        baseRef = "main",
        headRef = "topic",
    )

    private fun vm(
        title: String = "T",
        projectName: String = "zeron",
        pr: PrState? = null,
        prNumber: ULong? = null,
        status: SessionRowVM.Status = SessionRowVM.Status.Completed,
        unseen: Boolean = true,
    ): SessionRowVM = SessionRowVM(
        id = "abc",
        title = title,
        projectName = projectName,
        hasProject = true,
        colorIndex = 6,
        harness = null,
        branch = null,
        pr = pr,
        prNumber = prNumber,
        status = status,
        timeLabel = "4h",
        unseen = unseen,
        pinned = false,
        sendFailed = false,
    )

    // ------------------------------------------------------ the fallbacks

    @Test
    fun projectNameFallsBackProjectThenDeviceThenNoProject() {
        assertEquals("zeron", SessionRowVM.from(row(project = ProjectRef("p", "zeron", 6u)), 6).projectName)
        assertEquals("MacBook Pro", SessionRowVM.from(row(deviceName = "MacBook Pro"), 6).projectName)
        // The core trims the device name and sends None when blank, so the
        // last fallback only fires for a truly projectless, deviceless row.
        assertEquals("No project", SessionRowVM.from(row(deviceName = null), 6).projectName)
    }

    @Test
    fun aProjectSetsHasProjectAndItsColorIndex() {
        val with = SessionRowVM.from(row(project = ProjectRef("p", "zeron", 3u)), 6)
        assertTrue(with.hasProject)
        assertEquals(3, with.colorIndex)
    }

    @Test
    fun aProjectlessRowTonesLikeTheHomeTile() {
        val projectless = SessionRowVM.from(row(project = null), 6)
        assertFalse(projectless.hasProject)
        assertEquals(6, projectless.colorIndex)
        // The passed-in homeColorIndex is the fallback; 6 is the core's
        // projectColorIndex("home").
        assertEquals(2, SessionRowVM.from(row(project = null), 2).colorIndex)
    }

    // -------------------------------------------------------------- fields

    @Test
    fun everyChatIndicatorMapsExhaustively() {
        assertEquals(SessionRowVM.Status.Working, statusOf(ChatIndicator.WORKING))
        assertEquals(SessionRowVM.Status.Awaiting, statusOf(ChatIndicator.AWAITING_INPUT))
        assertEquals(SessionRowVM.Status.Errored, statusOf(ChatIndicator.ERRORED))
        assertEquals(SessionRowVM.Status.Completed, statusOf(ChatIndicator.COMPLETED))
        assertEquals(SessionRowVM.Status.Idle, statusOf(ChatIndicator.IDLE))
        // …and through from(): the display indicator, never hostIndicator.
        for (i in ChatIndicator.entries) {
            assertEquals(statusOf(i), SessionRowVM.from(row(indicator = i), 6).status)
        }
    }

    @Test
    fun prAndPrNumberMapTogether() {
        val with = SessionRowVM.from(row(pullRequest = pr(77uL, PullRequestState.OPEN)), 6)
        assertEquals(PrState.Open, with.pr)
        assertEquals(77uL, with.prNumber)
        assertEquals(PrState.Merged, SessionRowVM.from(row(pullRequest = pr(412uL, PullRequestState.MERGED)), 6).pr)
        assertEquals(PrState.Closed, SessionRowVM.from(row(pullRequest = pr(9uL, PullRequestState.CLOSED)), 6).pr)
        val without = SessionRowVM.from(row(), 6)
        assertNull(without.pr)
        assertNull(without.prNumber)
    }

    @Test
    fun onlyAFailedSendMarksSendFailed() {
        assertTrue(SessionRowVM.from(row(sendState = SendState.FAILED), 6).sendFailed)
        assertFalse(SessionRowVM.from(row(sendState = SendState.SENDING), 6).sendFailed)
        assertFalse(SessionRowVM.from(row(sendState = SendState.QUEUED), 6).sendFailed)
        assertFalse(SessionRowVM.from(row(sendState = null), 6).sendFailed)
    }

    @Test
    fun thePlainFieldsCarryOver() {
        val v = SessionRowVM.from(
            row(
                id = "abc", title = "Keyboard avoidance for composer",
                harness = "claude-code", branch = "main",
                indicator = ChatIndicator.AWAITING_INPUT, timeLabel = "2m",
                unseen = true, pinned = true,
            ),
            6,
        )
        assertEquals("abc", v.id)
        assertEquals("Keyboard avoidance for composer", v.title)
        assertEquals("claude-code", v.harness)
        assertEquals("main", v.branch)
        assertEquals("2m", v.timeLabel)
        assertEquals(true, v.unseen)
        assertEquals(true, v.pinned)
    }

    // ----------------------------------------------------------- the label

    @Test
    fun doneUnseenWithAnOpenPullRequestSpellsTheFullLabel() {
        assertEquals(
            "T, zeron, Done, Pull request 77, open",
            sessionRowA11yLabel(vm(pr = PrState.Open, prNumber = 77uL)),
        )
    }

    @Test
    fun anIdleRowIsTitleAndProjectOnlyNeverTheTime() {
        assertEquals("T, zeron", sessionRowA11yLabel(vm(status = SessionRowVM.Status.Idle, unseen = false)))
    }

    @Test
    fun thePullRequestPartNeedsBothPrAndNumber() {
        // pr != null but prNumber == null: no PR part (PRBadgeView's rule).
        assertEquals("T, zeron, Done", sessionRowA11yLabel(vm(pr = PrState.Open, prNumber = null)))
        // pr == null but prNumber != null: still no part.
        assertEquals("T, zeron, Done", sessionRowA11yLabel(vm(pr = null, prNumber = 77uL)))
    }

    @Test
    fun mergedAndClosedPullRequestsSpellTheirOwnWords() {
        assertEquals(
            "T, zeron, Done, Pull request 412, merged",
            sessionRowA11yLabel(vm(pr = PrState.Merged, prNumber = 412uL)),
        )
        assertEquals(
            "T, zeron, Done, Pull request 77, closed",
            sessionRowA11yLabel(vm(pr = PrState.Closed, prNumber = 77uL)),
        )
    }

    @Test
    fun aSeenCompletedRowHasNoCornerWord() {
        assertEquals("T, zeron", sessionRowA11yLabel(vm(status = SessionRowVM.Status.Completed, unseen = false)))
    }
}
