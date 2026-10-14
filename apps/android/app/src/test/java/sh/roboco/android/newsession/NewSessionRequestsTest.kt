package sh.roboco.android.newsession

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.composer.StagedImage
import uniffi.roboco_core.BusyPolicy
import uniffi.roboco_core.SandboxLevel
import uniffi.roboco_core.SessionTarget

/**
 * [NewSessionRequests] and [newSessionCanWrite] (android-ui-parity #94)
 * — the Request mapping and Offline gate tables, field by field. Runs
 * under Robolectric because it constructs `uniffi.roboco_core` records
 * (android-app#13's rule; the `StagedImage` thumb needs a platform
 * `ImageBitmap`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewSessionRequestsTest {

    // ------------------------------------------------------------- fixtures

    /** One project row; [online] and [path] are the fields the mapping reads. */
    private fun project(id: String, path: String = "/repo", online: Boolean = true) = ProjectOption(
        id = id,
        name = "Project $id",
        path = path,
        device = "dev-$id",
        deviceName = "dev-$id",
        online = online,
        git = true,
        groupKey = "g-$id",
        groupName = "g-$id",
        colorIndex = 0,
    )

    private fun host(id: String, online: Boolean = true) = HostOption(id = id, name = "Host $id", online = online)

    private fun image(id: String, vararg bytes: Int) =
        StagedImage(id, "$id.jpg", bytes.map { it.toByte() }.toByteArray(), ImageBitmap(1, 1))

    // ── target precedence (`AppModel.swift:622-628`) ─────────────────────

    @Test
    fun projectWinsOverHostWhenBothAreSet() {
        val d = NewSessionDraft(projectId = "p1", hostId = "h1")
        when (val t = NewSessionRequests.target(d)) {
            is SessionTarget.Project -> assertEquals("p1", t.spaceId)
            else -> fail("expected Project, was $t")
        }
    }

    @Test
    fun hostOnlyTargetsProjectless() {
        val d = NewSessionDraft(hostId = "h1")
        when (val t = NewSessionRequests.target(d)) {
            is SessionTarget.Projectless -> assertEquals("h1", t.deviceId)
            else -> fail("expected Projectless, was $t")
        }
    }

    @Test
    fun neitherTargetIsNullSoNoCoreCallHappens() {
        assertNull(NewSessionRequests.target(NewSessionDraft()))
    }

    // ── NewSession (`:629-630`) ──────────────────────────────────────────

    @Test
    fun configCarriesTheDraftWithWorkspaceWriteAndNilEffort() {
        val d = NewSessionDraft(
            projectId = "p1",
            harness = "mock",
            model = "mock-1",
            effort = null,
        )
        val r = NewSessionRequests.newSession(d, SessionTarget.Project(spaceId = "p1"))
        val config = checkNotNull(r.config)
        assertEquals("mock", config.harness)
        assertEquals("mock-1", config.model)
        assertNull(config.reasoning) // nil ⇒ the engine chooses
        assertTrue(config.modelOptions.isEmpty())
        assertEquals(SandboxLevel.WORKSPACE_WRITE, config.sandbox)
        assertEquals("p1", (r.target as SessionTarget.Project).spaceId)
    }

    @Test
    fun effortMapsToReasoning() {
        val d = NewSessionDraft(projectId = "p1", effort = "ultrathink")
        val r = NewSessionRequests.newSession(d, SessionTarget.Projectless(deviceId = "h1"))
        assertEquals("ultrathink", checkNotNull(r.config).reasoning)
    }

    @Test
    fun branchIsSentOnlyWithoutAWorktree() {
        val noWorktree = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1", branch = "main"),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertEquals("main", noWorktree.branch)

        val worktree = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1", branch = "main", worktree = true),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertNull(worktree.branch) // the branch becomes the worktree's base
    }

    @Test
    fun cwdAndTitleAreAlwaysNull() {
        val r = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1"),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertNull(r.cwd)
        assertNull(r.title)
    }

    // ── SendRequest (`:632-637`, `:646-648`) ────────────────────────────

    @Test
    fun imagesBecomeJpegAttachmentsWithTheStagedBytes() {
        val d = NewSessionDraft(projectId = "p1")
        val images = listOf(image("i1", 1, 2, 3), image("i2", 9))
        val r = NewSessionRequests.sendRequest(d, "body", images, listOf(project("p1")))
        assertEquals("body", r.text)
        assertEquals(2, r.attachments.size)
        assertEquals("i1.jpg", r.attachments[0].name)
        assertEquals("image/jpeg", r.attachments[0].mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.attachments[0].data)
        assertEquals("i2.jpg", r.attachments[1].name)
        assertArrayEquals(byteArrayOf(9), r.attachments[1].data)
        assertEquals(BusyPolicy.QUEUE, r.busy) // a new chat is idle
    }

    @Test
    fun worktreeSpecUsesTheProjectPathAndTheBranchAsBase() {
        val d = NewSessionDraft(projectId = "p1", branch = "main", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1", path = "C:\\repo")))
        val w = checkNotNull(r.worktree)
        assertEquals("C:\\repo", w.repoPath)
        assertEquals("main", w.base)
        assertEquals("p1", w.spaceId)
    }

    @Test
    fun worktreeBaseFallsBackToHeadWithoutABranch() {
        val d = NewSessionDraft(projectId = "p1", branch = null, worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertEquals("HEAD", checkNotNull(r.worktree).base)
    }

    @Test
    fun worktreeWithoutAProjectIsNullEvenWhenTheDraftAsksForOne() {
        val d = NewSessionDraft(hostId = "h1", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), emptyList())
        assertNull(r.worktree) // iOS `project.map` — no project, no spec
    }

    @Test
    fun noWorktreeMeansNoSpec() {
        val d = NewSessionDraft(projectId = "p1", worktree = false)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertNull(r.worktree)
    }

    @Test
    fun aProjectMissingFromTheOptionsGivesNoWorktreeSpec() {
        val d = NewSessionDraft(projectId = "gone", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertNull(r.worktree)
    }

    // ── newSessionCanWrite (the offline gate, 94's table) ────────────────

    @Test
    fun anOnlineProjectEngineAllowsWrites() {
        assertTrue(
            newSessionCanWrite(
                NewSessionDraft(projectId = "p1"),
                listOf(project("p1", online = true)),
                emptyList(),
            )
        )
    }

    @Test
    fun anOfflineProjectEngineRefusesWrites() {
        assertEquals(
            false,
            newSessionCanWrite(
                NewSessionDraft(projectId = "p1"),
                listOf(project("p1", online = false)),
                emptyList(),
            )
        )
    }

    @Test
    fun anOnlineHostAllowsWritesAndAnOfflineHostRefuses() {
        val d = NewSessionDraft(hostId = "h1")
        assertTrue(newSessionCanWrite(d, emptyList(), listOf(host("h1", online = true))))
        assertEquals(false, newSessionCanWrite(d, emptyList(), listOf(host("h1", online = false))))
    }

    @Test
    fun noTargetOrAnUnknownIdCountsAsWritable() {
        // No target: the send runs and the alert explains.
        assertTrue(newSessionCanWrite(NewSessionDraft(), emptyList(), emptyList()))
        // A stale id is not in the options: keep Send enabled (the alert
        // explains), never a silently disabled button.
        assertTrue(newSessionCanWrite(NewSessionDraft(projectId = "gone"), listOf(project("p1")), emptyList()))
        assertTrue(newSessionCanWrite(NewSessionDraft(hostId = "gone"), emptyList(), listOf(host("h1"))))
    }

    @Test
    fun theProjectWinsOverTheHostInTheGateToo() {
        // An offline project engine gates even when a host is also set.
        assertEquals(
            false,
            newSessionCanWrite(
                NewSessionDraft(projectId = "p1", hostId = "h1"),
                listOf(project("p1", online = false)),
                listOf(host("h1", online = true)),
            )
        )
    }
}
