package sh.roboco.android.newsession

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.RepoRef

/**
 * The checkout / model / effort menu content (android-ui-parity #114) —
 * the ticket's acceptance table on the plain JVM: the pure builders, the
 * branch deferred's load and cache, [CoreRefSource]'s sort and error
 * rules, and every pick's draft transform. No native library is loaded:
 * [RefSource] is faked, and [CoreRefSource] is exercised only through
 * its lambda seam (the generated `RepoRef` records and `CoreException`
 * variants construct without JNA).
 *
 * `io` is a single-thread executor named `fake-io`, so the branch load's
 * dispatcher hop is asserted by thread name. The Robolectric behaviour
 * (menus over the real strip and host) lives in
 * [CheckoutModelEffortMenusUiTest]; the goldens in
 * [CheckoutModelEffortMenusScreenshotTest].
 */
class CheckoutModelEffortMenusTest {

    // ── fixtures (the ticket's acceptance constants) ───────────────────

    /** `catalog.rs` FULL_LADDER — 7 levels. */
    private val full = listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink")

    /** `catalog.rs` CODEX XHIGH_LADDER — 4 levels. */
    private val codexLadder = listOf("low", "medium", "high", "xhigh")

    /** The M fixture: Codex first, so the section sort is under test. */
    private val models = listOf(
        ModelChoice("codex", "Codex", "gpt-5.5", "GPT-5.5", codexLadder),
        ModelChoice("codex", "Codex", "gpt-5.4", "GPT-5.4", codexLadder),
        ModelChoice("claude-code", "Claude Code", "claude-fable-5-1", "Fable 5.1", full),
        ModelChoice("claude-code", "Claude Code", "claude-opus-5-5", "Opus 5.5", full),
        ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList()),
    )

    /** The ticket's row-1 project. */
    private val project = ProjectOption(
        id = "p1",
        name = "roboco",
        path = "/srv/roboco",
        device = "d1",
        deviceName = "Workstation",
        online = true,
        git = true,
        groupKey = "repo:r",
        groupName = "roboco",
        colorIndex = 2,
    )

    /** The fake engine: records calls and the calling thread. */
    private class RecordingRefSource(private val result: List<String> = listOf("main", "dev")) : RefSource {
        var calls = 0
            private set
        val threads = mutableListOf<String>()

        override suspend fun refs(project: ProjectOption): List<String> {
            calls++
            threads += Thread.currentThread().name
            return result
        }
    }

    /** The page under test: the menus over one local draft, the fake refs, `fake-io`. */
    private inner class MenusPage(
        var draft: NewSessionDraft,
        val refSource: RefSource = RecordingRefSource(),
    ) {
        /** The row-1 project; a test with no project assigns `null` before it opens a menu. */
        var project: ProjectOption? = this@CheckoutModelEffortMenusTest.project

        val models: List<ModelChoice> = this@CheckoutModelEffortMenusTest.models

        val menus = CheckoutModelEffortMenus(
            draft = { draft },
            project = { project },
            models = { models },
            selectedModel = {
                models.firstOrNull { m -> m.harness == draft.harness && m.id == draft.model }
                    ?: models.firstOrNull { m -> m.harness == draft.harness }
            },
            update = { t -> draft = t(draft) },
            refSource = refSource,
            io = fakeIo,
        )
    }

    private lateinit var fakeIo: ExecutorCoroutineDispatcher
    private var fakeIoExecutor: ExecutorService? = null

    @Before
    fun startFakeIo() {
        fakeIoExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-io") }
        fakeIo = fakeIoExecutor!!.asCoroutineDispatcher()
    }

    @After
    fun stopFakeIo() {
        fakeIoExecutor?.shutdownNow()
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** `children[1]` as the inline `Branch` section (asserting the shape). */
    private fun branchSectionOf(menu: Menu): MenuSection {
        val section = menu.children[1] as MenuSection
        assertEquals("Branch", section.title)
        assertTrue(section.inline)
        return section
    }

    /** The `Branch` section's deferred — the load the host runs. */
    private fun branchDeferredOf(menu: Menu): MenuElement.Deferred =
        branchSectionOf(menu).children.single() as MenuElement.Deferred

    /** `children[0]` as the "New worktree" row. */
    private fun worktreeRowOf(menu: Menu): MenuItem = menu.children[0] as MenuItem

    /** The model menu's rows, flattened across its inline sections. */
    private fun modelRowsOf(menu: Menu): List<MenuItem> =
        menu.children.map { it as MenuSection }.flatMap { section -> section.children.map { c -> c as MenuItem } }

    private fun rowTitles(rows: List<MenuElement>): List<String> = rows.map { (it as MenuItem).title }

    private fun loadBranchRows(page: MenusPage): List<MenuItem> =
        runBlocking { branchDeferredOf(page.menus.checkout()).load() }.map { it as MenuItem }

    // ── effortTitle (the Spec table, row by row) ───────────────────────

    @Test
    fun effortTitleFollowsTheSpecTable() {
        assertEquals("Minimal", effortTitle("minimal"))
        assertEquals("Low", effortTitle("low"))
        assertEquals("Medium", effortTitle("medium"))
        assertEquals("High", effortTitle("high"))
        assertEquals("Xhigh", effortTitle("xhigh"))
        assertEquals("Max", effortTitle("max"))
        assertEquals("Ultra", effortTitle("ultra"))
        assertEquals("Ultracode", effortTitle("ultracode"))
        assertEquals("Ultrathink", effortTitle("ultrathink"))
        assertEquals("High", effortTitle("HIGH"))
        assertEquals("", effortTitle(""))
    }

    // ── checkout() shape and the worktree row ──────────────────────────

    @Test
    fun checkoutIsTheWorktreeRowThenTheInlineBranchSectionWithACachedDeferred() {
        val page = MenusPage(NewSessionDraft(projectId = "p1"))
        val menu = page.menus.checkout()
        assertEquals("Checkout", menu.title)
        val worktree = worktreeRowOf(menu)
        assertEquals("New worktree", worktree.title)
        assertNull("no subtitle since #593", worktree.subtitle)
        assertNull("no icon since #593", worktree.icon)
        assertFalse(worktree.checked)
        assertEquals("new-session-menu-worktree", worktree.testTag)
        val deferred = branchDeferredOf(menu)
        assertTrue(deferred.cached)
    }

    @Test
    fun theWorktreeRowIsCheckedWithTheDraft() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", worktree = true))
        assertTrue(worktreeRowOf(page.menus.checkout()).checked)
    }

    @Test
    fun togglingTheWorktreeRowFlipsTheDraftAndLeavesTheBranchAlone() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", branch = "main"))
        val row = worktreeRowOf(page.menus.checkout())
        row.action()
        assertTrue(page.draft.worktree)
        row.action()
        assertFalse(page.draft.worktree)
        assertEquals("main", page.draft.branch)
    }

    // ── the branch load ────────────────────────────────────────────────

    @Test
    fun theBranchLoadSortsCurrentFirstRunsOnIoAndChecksTheBranchOrFirstRef() {
        // The engine's answer, as android-app#09 returns it: `dev` is current.
        val engine = CoreRefSource(listRefs = { _, _ ->
            listOf(
                RepoRef(name = "main", current = false, worktreePath = null),
                RepoRef(name = "dev", current = true, worktreePath = null),
                RepoRef(name = "a", current = false, worktreePath = null),
            )
        })
        var calls = 0
        val threads = mutableListOf<String>()
        val refs = RefSource { p ->
            calls++
            // Coroutine debug mode (on under -ea) renames the running
            // thread "fake-io @coroutine#N"; the lane is the base name.
            threads += Thread.currentThread().name.substringBefore(" @coroutine#")
            engine.refs(p)
        }
        val page = MenusPage(NewSessionDraft(projectId = "p1"), refSource = refs)
        val rows = loadBranchRows(page)

        assertEquals(listOf("dev", "main", "a"), rowTitles(rows))
        assertEquals("the FFI hop ran on the io lane", listOf("fake-io"), threads)
        assertEquals(1, calls)

        // Nothing picked: the first ref is checked (iOS `draft.branch ?? refs.first`).
        assertEquals("new-session-menu-branch-dev", rows[0].testTag)
        assertTrue(rows[0].checked)
        assertFalse(rows[1].checked)
        assertFalse(rows[2].checked)

        // `branch == "a"`: only a is checked.
        val picked = MenusPage(NewSessionDraft(projectId = "p1", branch = "a"), refSource = RecordingRefSource(listOf("dev", "main", "a")))
        val pickedRows = loadBranchRows(picked)
        assertFalse(pickedRows[0].checked)
        assertFalse(pickedRows[1].checked)
        assertTrue(pickedRows[2].checked)
    }

    @Test
    fun aMissingProjectLoadsNoRowsAndNeverAsksTheEngine() {
        val refs = RecordingRefSource()
        val page = MenusPage(NewSessionDraft(projectId = "gone"), refSource = refs)
        page.project = null
        val rows = runBlocking { branchDeferredOf(page.menus.checkout()).load() }
        assertTrue(rows.isEmpty())
        assertEquals("no project, no call", 0, refs.calls)
    }

    @Test
    fun pickingABranchRowSetsTheDraftBranchAndLeavesWorktreeOn() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", worktree = true))
        val rows = loadBranchRows(page)
        rows[1].action() // "dev"
        assertEquals("dev", page.draft.branch)
        assertTrue("a branch pick never clears worktree", page.draft.worktree)
    }

    // ── the branch cache's lifetime (the BranchKey hoist) ──────────────

    @Test
    fun theBranchDeferredIsHoistedUntilAnyPickChangesTheDraft() {
        val page = MenusPage(NewSessionDraft(projectId = "p1"))
        val first = branchDeferredOf(page.menus.checkout())
        assertSame("no draft change keeps the instance", first, branchDeferredOf(page.menus.checkout()))

        // A worktree pick (Checkout's own menu) drops the cache.
        worktreeRowOf(page.menus.checkout()).action()
        val second = branchDeferredOf(page.menus.checkout())
        assertNotSame("a worktree pick drops the cache", first, second)
        assertSame(second, branchDeferredOf(page.menus.checkout()))

        // A branch pick.
        loadBranchRows(page)[0].action()
        val third = branchDeferredOf(page.menus.checkout())
        assertNotSame("a branch pick drops the cache", second, third)

        // A model pick.
        modelRowsOf(page.menus.model()).first { m -> m.title == "GPT-5.5" }.action()
        val fourth = branchDeferredOf(page.menus.checkout())
        assertNotSame("a model pick drops the cache", third, fourth)

        // An effort pick.
        val effortRows = page.menus.effort().children.map { it as MenuItem }
        effortRows.first { r -> r.title == "Low" }.action()
        assertNotSame("an effort pick drops the cache", fourth, branchDeferredOf(page.menus.checkout()))
    }

    @Test
    fun aProjectWhoseDeviceOrPathChangedDropsTheBranchCache() {
        var current = project
        var draft = NewSessionDraft(projectId = "p1")
        val menus = CheckoutModelEffortMenus(
            draft = { draft },
            project = { current },
            models = { models },
            selectedModel = { null },
            update = { t -> draft = t(draft) },
            refSource = RecordingRefSource(),
            io = fakeIo,
        )
        val first = branchDeferredOf(menus.checkout())
        assertSame(first, branchDeferredOf(menus.checkout()))
        current = current.copy(device = "d2", path = "/other/roboco")
        assertNotSame(first, branchDeferredOf(menus.checkout()))
    }

    @Test
    fun onlyTheBranchLoadEverCallsTheRefSource() {
        val refs = RecordingRefSource()
        val page = MenusPage(NewSessionDraft(projectId = "p1"), refSource = refs)
        page.menus.checkout()
        page.menus.model()
        page.menus.effort()
        assertEquals("building the menus asks no engine", 0, refs.calls)
        runBlocking { branchDeferredOf(page.menus.checkout()).load() }
        assertEquals(1, refs.calls)
    }

    // ── CoreRefSource (the engine call's shape) ────────────────────────

    @Test
    fun coreRefSourceSortsCurrentFirstAndKeepsTheEngineOrderOtherwise() {
        val source = CoreRefSource(listRefs = { _, _ ->
            listOf(
                RepoRef(name = "main", current = false, worktreePath = null),
                RepoRef(name = "dev", current = true, worktreePath = null),
                RepoRef(name = "a", current = false, worktreePath = null),
            )
        })
        assertEquals(listOf("dev", "main", "a"), runBlocking { source.refs(project) })
    }

    @Test
    fun aThrowingListRefsReadsAsNoRefs() {
        val source = CoreRefSource(listRefs = { _, _ -> throw CoreException.HostUnavailable("engine offline") })
        assertEquals(emptyList<String>(), runBlocking { source.refs(project) })
    }

    @Test
    fun aNullClientReadsAsNoRefs() {
        var clientCalls = 0
        val source = CoreRefSource(client = { clientCalls++; null })
        assertEquals(emptyList<String>(), runBlocking { source.refs(project) })
        assertEquals("the client was resolved once (and never asked)", 1, clientCalls)
    }

    @Test
    fun aCancellationPropagatesInsteadOfReadingAsNoRefs() {
        val source = CoreRefSource(listRefs = { _, _ -> throw CancellationException("menu closed") })
        try {
            runBlocking { source.refs(project) }
            fail("a cancellation must propagate: ticket 15 cancels the load when the menu closes")
        } catch (expected: CancellationException) {
            // rethrown, never cached, never read as "no branches"
        }
    }

    // ── model() ────────────────────────────────────────────────────────

    @Test
    fun theModelMenuSortsItsSectionsByHarnessIdAndListsRowsInInputOrder() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", harness = "claude-code", model = "claude-opus-5-5"))
        val menu = page.menus.model()
        assertEquals("Model", menu.title)
        assertEquals(2, menu.children.size)

        val claude = menu.children[0] as MenuSection
        assertEquals("Claude Code", claude.title)
        assertEquals(MenuIcon.Brand("claude-code"), claude.icon)
        assertTrue(claude.inline)
        assertEquals(listOf("Fable 5.1", "Opus 5.5", "Haiku 4.5"), claude.children.map { (it as MenuItem).title })

        val codex = menu.children[1] as MenuSection
        assertEquals("Codex", codex.title)
        assertEquals(MenuIcon.Brand("codex"), codex.icon)
        assertTrue(codex.inline)
        assertEquals(listOf("GPT-5.5", "GPT-5.4"), codex.children.map { (it as MenuItem).title })
    }

    @Test
    fun theModelCheckIsTheDraftsHarnessAndIdPairOnly() {
        val picked = MenusPage(NewSessionDraft(projectId = "p1", harness = "claude-code", model = "claude-opus-5-5"))
        val rows = modelRowsOf(picked.menus.model())
        // Menu order, not input order: sections sort by harness id, so the
        // rows are Fable, Opus, Haiku, GPT-5.5, GPT-5.4 (as the test above
        // pins) and Opus 5.5 is row 1.
        assertEquals(listOf(false, true, false, false, false), rows.map { it.checked })
        assertEquals("new-session-menu-model-claude-code-claude-opus-5-5", rows[1].testTag)

        // A model id that belongs to another harness checks nothing.
        val mixed = MenusPage(NewSessionDraft(projectId = "p1", harness = "codex", model = "claude-opus-5-5"))
        assertEquals(0, modelRowsOf(mixed.menus.model()).count { it.checked })

        // Nothing picked: nothing checked, even though the chip names a model.
        val none = MenusPage(NewSessionDraft(projectId = "p1"))
        assertEquals(0, modelRowsOf(none.menus.model()).count { it.checked })
    }

    @Test
    fun aModelPickSetsTheHarnessAndModelAndClearsTheEffort() {
        val page = MenusPage(
            NewSessionDraft(
                projectId = "p1",
                hostId = null,
                branch = "main",
                worktree = true,
                harness = "claude-code",
                model = "claude-opus-5-5",
                effort = "max",
            ),
        )
        val gpt = modelRowsOf(page.menus.model()).first { m -> m.title == "GPT-5.5" }
        assertEquals("new-session-menu-model-codex-gpt-5.5", gpt.testTag)
        gpt.action()
        val d = page.draft
        assertEquals("codex", d.harness)
        assertEquals("gpt-5.5", d.model)
        assertNull("the new model's ladder decides the default (109)", d.effort)
        assertEquals("main", d.branch)
        assertTrue(d.worktree)
        assertEquals("p1", d.projectId)
        assertNull(d.hostId)
    }

    // ── effort() ───────────────────────────────────────────────────────

    @Test
    fun theEffortMenuListsTheSelectedModelsLadderSwiftCapitalized() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", harness = "claude-code", model = "claude-fable-5-1"))
        val menu = page.menus.effort()
        assertEquals("Reasoning effort", menu.title)
        val rows = menu.children.map { it as MenuItem }
        assertEquals(
            listOf("Low", "Medium", "High", "Xhigh", "Max", "Ultracode", "Ultrathink"),
            rows.map { it.title },
        )
        assertEquals("the tags carry the wire levels", full, rows.map { it.testTag?.removePrefix("new-session-menu-effort-") })
        assertEquals("nothing picked, nothing checked", 0, rows.count { it.checked })
    }

    @Test
    fun theEffortCheckIsTheDraftEffortOnly() {
        val page = MenusPage(
            NewSessionDraft(projectId = "p1", harness = "claude-code", model = "claude-fable-5-1", effort = "low"),
        )
        val rows = page.menus.effort().children.map { it as MenuItem }
        assertEquals(1, rows.count { it.checked })
        assertTrue(rows.first { it.title == "Low" }.checked)
        assertEquals("new-session-menu-effort-low", rows.first { it.title == "Low" }.testTag)
    }

    @Test
    fun withoutAModelTheEffortMenuHasNoChildren() {
        val page = MenusPage(NewSessionDraft(projectId = "p1", harness = "grok"))
        val menu = page.menus.effort()
        assertEquals("Reasoning effort", menu.title)
        assertTrue(menu.children.isEmpty())
    }

    @Test
    fun anEffortPickSetsOnlyTheEffort() {
        val page = MenusPage(
            NewSessionDraft(projectId = "p1", branch = "main", worktree = true, harness = "claude-code", model = "claude-fable-5-1"),
        )
        val before = page.draft
        val low = page.menus.effort().children.map { it as MenuItem }.first { it.title == "Low" }
        low.action()
        assertEquals("low", page.draft.effort)
        assertEquals(before.copy(effort = "low"), page.draft)
    }
}
