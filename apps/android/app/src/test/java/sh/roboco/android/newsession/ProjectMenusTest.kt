package sh.roboco.android.newsession

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuLine
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.glass.hasContent

/**
 * The Project / Run on menu content (android-ui-parity #113) — the pure
 * builders and the model glue on the plain JVM (the ticket's Verification
 * section): grouping, sorting, the subtitle de-dupe, `pickProjectTarget`,
 * the four draft transitions, the P1/P2/R1/R2/R3 row tables, the empty-
 * menu rules, and [ProjectRunOnMenus] over 92's real model with fakes
 * (a token codec for org.json, a counting DataStore so "exactly one
 * update per pick" is observable, an empty model catalog).
 *
 * Every collator use passes [localizedCaseInsensitive] with `Locale.US`
 * so the results do not depend on the machine locale. The visual row
 * (panel, checkmark in the leading slot, 18 dp tile) is ticket 15/12's —
 * the Roborazzi goldens in `ProjectMenusScreenshotTest` pin it.
 */
internal const val ProjectMenusRobocoGroupKey = "repo:github.com/hoangvu12/roboco"

// ── the fixture (the ticket's demo-data rename; shared with the
//    Robolectric UI test) ──────────────────────────────────────────

internal fun projectMenusFixtureHosts(): List<HostOption> = listOf(
    HostOption("e2:dev-vps", "hetzner-01", online = true),
    HostOption("e3:dev-studio", "Mac Studio", online = false),
    HostOption("e1:dev-mac", "MacBook Pro", online = true),
)

internal fun projectMenusFixtureProjects(): List<ProjectOption> = listOf(
    ProjectOption(
        id = "e3:space-blog", name = "blog", path = "/Users/dev/Projects/blog",
        device = "e3:dev-studio", deviceName = "Mac Studio", online = false, git = false,
        groupKey = "e3:space-blog", groupName = "blog", colorIndex = 6,
    ),
    ProjectOption(
        id = "e1:space-roboco", name = "roboco", path = "/Users/dev/roboco",
        device = "e1:dev-mac", deviceName = "MacBook Pro", online = true, git = true,
        groupKey = ProjectMenusRobocoGroupKey, groupName = "roboco", colorIndex = 5,
    ),
    ProjectOption(
        id = "e2:space-roboco-vps", name = "roboco", path = "/srv/src/roboco",
        device = "e2:dev-vps", deviceName = "hetzner-01", online = true, git = true,
        groupKey = ProjectMenusRobocoGroupKey, groupName = "roboco", colorIndex = 5,
    ),
    ProjectOption(
        id = "e2:space-edge", name = "edge", path = "/srv/deploys/edge",
        device = "e2:dev-vps", deviceName = "hetzner-01", online = true, git = true,
        groupKey = "e2:space-edge", groupName = "edge", colorIndex = 0,
    ),
    ProjectOption(
        id = "e1:space-mobile", name = "Roboco Mobile", path = "/Users/dev/roboco-mobile",
        device = "e1:dev-mac", deviceName = "MacBook Pro", online = true, git = true,
        groupKey = "e1:space-mobile", groupName = "Roboco Mobile", colorIndex = 2,
    ),
)

internal fun projectMenusFixtureOptions(): NewSessionOptions =
    NewSessionOptions(projectMenusFixtureProjects(), projectMenusFixtureHosts())

/** R2's extra clone: a third checkout of the repository, on the same engine as one. */
internal fun projectMenusForkOption(): ProjectOption = ProjectOption(
    id = "e1:space-roboco-fork", name = "roboco-fork", path = "/Users/dev/src/roboco-fork",
    device = "e1:dev-mac", deviceName = "MacBook Pro", online = true, git = true,
    groupKey = ProjectMenusRobocoGroupKey, groupName = "roboco", colorIndex = 5,
)

/** The fake created space ("New Project…"). */
internal fun projectMenusNewProjectOption(): ProjectOption = ProjectOption(
    id = "e2:space-new", name = "new-thing", path = "/srv/src/new-thing",
    device = "e2:dev-vps", deviceName = "hetzner-01", online = true, git = false,
    groupKey = "e2:space-new", groupName = "new-thing", colorIndex = 6,
)

class ProjectMenusTest {

    private val us: Comparator<String> = localizedCaseInsensitive(Locale.US)

    // ── grouping and sorting ───────────────────────────────────────────

    @Test
    fun groupsFixtureByRepositoryInCollatorOrder() {
        val groups = projectGroups(projectMenusFixtureProjects(), us)
        assertEquals(listOf("blog", "edge", "roboco", "Roboco Mobile"), groups.map { it.name })
        val roboco = groups.first { it.name == "roboco" }
        assertEquals(ProjectMenusRobocoGroupKey, roboco.groupKey)
        assertEquals(5, roboco.colorIndex)
        // members keep options order: the oldest checkout first.
        assertEquals(listOf("e1:space-roboco", "e2:space-roboco-vps"), roboco.members.map { it.id })
        // the two engines, de-duplicated, collator-sorted, ", "-joined.
        assertEquals("hetzner-01, MacBook Pro", roboco.hosts)
        assertEquals("Mac Studio", groups.first { it.name == "blog" }.hosts)
        assertEquals("hetzner-01", groups.first { it.name == "edge" }.hosts)
        assertEquals("MacBook Pro", groups.first { it.name == "Roboco Mobile" }.hosts)
    }

    @Test
    fun sortTiesKeepOptionsOrderAndNamesCompareCaseInsensitively() {
        fun g(id: String, groupName: String, groupKey: String) = ProjectOption(
            id = id, name = groupName, path = "/$id", device = "d-$id", deviceName = "D-$id",
            online = true, git = true, groupKey = groupKey, groupName = groupName, colorIndex = 0,
        )
        val groups = projectGroups(
            listOf(g("a1", "api", "g1"), g("z", "Zeta", "g3"), g("a2", "api", "g2"), g("al", "alpha", "g4")),
            us,
        )
        // alpha < api (case-insensitive) < api < Zeta; the two "api" groups
        // are equal under the collator and keep options order.
        assertEquals(listOf("alpha", "api", "api", "Zeta"), groups.map { it.name })
        assertEquals(
            listOf("a1", "a2"),
            groups.filter { it.name == "api" }.map { it.members.single().id },
        )
    }

    @Test
    fun theHostSubtitleDeDupesExactlyAndSortsWithTheCollator() {
        fun d(id: String, deviceName: String) = ProjectOption(
            id = id, name = "one", path = "/$id", device = "d-$id", deviceName = deviceName,
            online = true, git = true, groupKey = "same", groupName = "one", colorIndex = 0,
        )
        val groups = projectGroups(listOf(d("m1", "b-box"), d("m2", "B-box"), d("m3", "a")), us)
        // exact-string de-dupe keeps both spellings; the two equal-collating
        // names keep first-seen order; "a" sorts first.
        assertEquals("a, b-box, B-box", groups.single().hosts)
    }

    // ── pickProjectTarget (the ticket's preference order) ──────────────

    @Test
    fun pickProjectTargetFollowsThePreferenceOrder() {
        val projects = projectMenusFixtureProjects()
        val mac = projects.first { it.id == "e1:space-roboco" }
        val vps = projects.first { it.id == "e2:space-roboco-vps" }
        val members = listOf(mac, vps)

        // 1 — the current checkout belongs to the group (re-pick changes nothing).
        assertEquals(vps, pickProjectTarget(members, NewSessionDraft(projectId = vps.id), "whatever"))
        // 2 — it does not: the member on the current device.
        assertEquals(
            vps,
            pickProjectTarget(members, NewSessionDraft(projectId = "e2:space-edge"), "e2:dev-vps"),
        )
        // 3 — no project: the member on the draft host's device.
        assertEquals(mac, pickProjectTarget(members, NewSessionDraft(hostId = "e1:dev-mac"), "e1:dev-mac"))
        // 4 — the device holds none: the first online member.
        assertEquals(
            vps,
            pickProjectTarget(
                listOf(mac.copy(online = false), vps),
                NewSessionDraft(hostId = "e3:dev-studio"),
                "e3:dev-studio",
            ),
        )
        // 5 — all offline, no device: the first member (options order) —
        // the offline copy that was passed in, not the online fixture row.
        val offlineMac = mac.copy(online = false)
        assertEquals(
            offlineMac,
            pickProjectTarget(
                listOf(offlineMac, vps.copy(online = false)),
                NewSessionDraft(),
                "",
            ),
        )
    }

    // ── the draft transitions ──────────────────────────────────────────

    @Test
    fun withCheckoutNullsHostIdAndClearsBranchOnlyForAnotherCheckout() {
        val projects = projectMenusFixtureProjects()
        val mac = projects.first { it.id == "e1:space-roboco" }
        val vps = projects.first { it.id == "e2:space-roboco-vps" }
        val base = NewSessionDraft(projectId = mac.id, branch = "main", worktree = true)
        // same checkout: only hostId is (re-)asserted.
        assertEquals(
            NewSessionDraft(projectId = mac.id, branch = "main", worktree = true),
            base.withCheckout(mac),
        )
        // another checkout of the same repository: branch cleared, hostId null.
        assertEquals(
            NewSessionDraft(projectId = vps.id, branch = null, worktree = true),
            base.withCheckout(vps),
        )
        // a stale hostId in the draft is always dropped.
        assertNull(base.copy(hostId = "e1:dev-mac").withCheckout(mac).hostId)
        // worktree is never touched, in either direction.
        assertFalse(base.copy(worktree = false).withCheckout(vps).worktree)
        assertTrue(base.withCheckout(vps).worktree)
    }

    @Test
    fun withNoProjectKeepsAValidHostElseTheFirstOnlineOne() {
        val hosts = projectMenusFixtureHosts()
        // no host picked: the first online host, not the first host.
        assertEquals(
            NewSessionDraft(hostId = "e2:dev-vps", branch = "main", worktree = true),
            NewSessionDraft(projectId = "e1:space-roboco", branch = "main", worktree = true)
                .withNoProject(hosts),
        )
        // all offline: the first host.
        assertEquals(
            "e2:dev-vps",
            NewSessionDraft(projectId = "x")
                .withNoProject(hosts.map { it.copy(online = false) }).hostId,
        )
        // an existing host is kept even when offline; branch and worktree untouched.
        val kept = NewSessionDraft(projectId = "x", hostId = "e3:dev-studio", branch = "main", worktree = true)
            .withNoProject(hosts)
        assertEquals("e3:dev-studio", kept.hostId)
        assertEquals("main", kept.branch)
        assertTrue(kept.worktree)
        // no hosts at all: hostId stays null. (From a draft with no host:
        // a picked host is always kept, as iOS's `hostId ?? …` does.)
        assertNull(NewSessionDraft(projectId = "x").withNoProject(emptyList()).hostId)
    }

    @Test
    fun withCreatedProjectAndWithHostTouchOnlyTheirFields() {
        val base = NewSessionDraft(
            projectId = "e1:space-roboco",
            hostId = "e1:dev-mac",
            branch = "main",
            worktree = true,
            effort = "high",
        )
        val created = base.withCreatedProject("e2:space-new")
        assertEquals("e2:space-new", created.projectId)
        assertNull(created.hostId)
        assertNull(created.branch)
        assertTrue(created.worktree)
        assertEquals("high", created.effort)

        val host = base.withHost(HostOption("e3:dev-studio", "Mac Studio", online = false))
        assertEquals("e1:space-roboco", host.projectId)
        assertEquals("e3:dev-studio", host.hostId)
        assertEquals("main", host.branch)
        assertTrue(host.worktree)
        assertEquals("high", host.effort)
    }

    // ── buildProjectMenu (P1 / P2 / empty / stale) ─────────────────────

    @Test
    fun theProjectMenuContentOfP1AndP2() {
        val projects = projectMenusFixtureProjects()

        val p1 = buildProjectMenu(projects, NewSessionDraft(projectId = "e1:space-roboco"), {}, {}, {}, us)
        assertEquals("Project", p1.title)
        // two untitled inline sections, section 1 first.
        assertEquals(2, p1.children.size)
        val section1 = p1.children[0] as MenuSection
        val section2 = p1.children[1] as MenuSection
        assertTrue(section1.inline)
        assertNull(section1.title)
        assertNull(section1.icon)
        assertTrue(section2.inline)
        assertNull(section2.title)

        val rows = menuRows(p1)
        assertEquals(
            listOf("blog", "edge", "roboco", "Roboco Mobile", "No Project…", "New Project…"),
            rows.map { it.title },
        )
        assertEquals(
            listOf("Mac Studio", "hetzner-01", "hetzner-01, MacBook Pro", "MacBook Pro", null, null),
            rows.map { it.subtitle },
        )
        assertEquals(listOf(false, false, true, false, false, false), rows.map { it.checked })
        assertEquals(
            listOf(
                "project-menu-group", "project-menu-group", "project-menu-group", "project-menu-group",
                "project-menu-none", "project-menu-new",
            ),
            rows.map { it.testTag },
        )
        // the group rows carry the 18 dp monogram (never tinted — the ink is
        // ignored); the fixed rows carry tray and folder.badge.plus.
        rows.take(4).forEach { assertTrue("a group row draws the tile", it.icon is MenuIcon.Custom) }
        assertEquals(MenuIcon.Symbol(RobocoIcons.Tray), rows[4].icon)
        assertEquals(MenuIcon.Symbol(RobocoIcons.FolderBadgePlus), rows[5].icon)

        // P2 — no project: no group checked, "No Project…" checked.
        val p2 = buildProjectMenu(projects, NewSessionDraft(projectId = null, hostId = "e2:dev-vps"), {}, {}, {}, us)
        assertEquals(
            listOf(false, false, false, false, true, false),
            menuRows(p2).map { it.checked },
        )

        // a stale projectId checks nothing at all.
        val stale = buildProjectMenu(projects, NewSessionDraft(projectId = "gone"), {}, {}, {}, us)
        assertEquals(
            "a stale projectId checks no row",
            listOf(false, false, false, false, false, false),
            menuRows(stale).map { it.checked },
        )
    }

    @Test
    fun anEmptyProjectMenuDropsSectionOneAndItsSeparator() {
        val menu = buildProjectMenu(emptyList(), NewSessionDraft(), {}, {}, {}, us)
        val lines = flattenLevel(menu.title, menu.children, resolved = { null }, isRoot = true, backTitle = null)
        // Title, then the two section-2 rows — and no Separator anywhere.
        assertEquals(3, lines.size)
        assertEquals(MenuLine.Title("Project"), lines[0])
        assertEquals("No Project…", (lines[1] as MenuLine.Row).item.title)
        assertEquals("New Project…", (lines[2] as MenuLine.Row).item.title)
        assertFalse(lines.contains(MenuLine.Separator))
    }

    // ── buildRunOnMenu (R1 / R2 / R3 / shared name / empty) ────────────

    @Test
    fun theRunOnMenuContentOfR1R2AndR3() {
        // R1 — the roboco group's two checkouts, one per engine. 92's real
        // checkouts computation drives the row order.
        val r1 = newMenusModel(projectMenusFixtureOptions(), NewSessionDraft(projectId = "e1:space-roboco"))
        val r1Menu = buildRunOnMenu(r1.model.checkouts, projectMenusFixtureHosts(), r1.model.draft, {}, {})
        assertEquals("Run on", r1Menu.title)
        val r1Rows = menuRows(r1Menu)
        assertEquals(listOf("hetzner-01", "MacBook Pro"), r1Rows.map { it.title })
        assertEquals(listOf("Online", "Online"), r1Rows.map { it.subtitle })
        assertEquals(listOf(false, true), r1Rows.map { it.checked })
        assertRunOnRowChrome(r1Rows)

        // R2 — the third clone lands on the same engine as one: the paths
        // tell the two MacBook Pro rows apart.
        val forkOptions = NewSessionOptions(
            projectMenusFixtureProjects() + projectMenusForkOption(),
            projectMenusFixtureHosts(),
        )
        val r2 = newMenusModel(forkOptions, NewSessionDraft(projectId = "e1:space-roboco"))
        val r2Rows = menuRows(buildRunOnMenu(r2.model.checkouts, projectMenusFixtureHosts(), r2.model.draft, {}, {}))
        assertEquals(listOf("hetzner-01", "MacBook Pro", "MacBook Pro"), r2Rows.map { it.title })
        assertEquals(
            listOf("Online", "/Users/dev/roboco", "/Users/dev/src/roboco-fork"),
            r2Rows.map { it.subtitle },
        )
        assertEquals(listOf(false, true, false), r2Rows.map { it.checked })
        assertRunOnRowChrome(r2Rows)

        // R3 — no project: every paired engine, offline included.
        val r3 = newMenusModel(projectMenusFixtureOptions(), NewSessionDraft(projectId = null, hostId = "e1:dev-mac"))
        val r3Rows = menuRows(buildRunOnMenu(r3.model.checkouts, projectMenusFixtureHosts(), r3.model.draft, {}, {}))
        assertEquals(listOf("hetzner-01", "Mac Studio", "MacBook Pro"), r3Rows.map { it.title })
        assertEquals(listOf("Online", "Offline", "Online"), r3Rows.map { it.subtitle })
        assertEquals(listOf(false, false, true), r3Rows.map { it.checked })
        assertRunOnRowChrome(r3Rows)
    }

    @Test
    fun theSharedDeviceSubtitleUsesTheDeviceIdNotTheName() {
        // Two checkouts on different devices that share the *name* — each
        // device holds one, so the subtitles stay Online/Offline, never paths.
        fun c(id: String, device: String, path: String, online: Boolean) = ProjectOption(
            id = id, name = id, path = path, device = device, deviceName = "MacBook Pro",
            online = online, git = true, groupKey = "g", groupName = id, colorIndex = 0,
        )
        val rows = menuRows(
            buildRunOnMenu(
                listOf(c("one", "d1", "/a", online = true), c("two", "d2", "/b", online = false)),
                emptyList(),
                NewSessionDraft(projectId = "one"),
                {},
                {},
            ),
        )
        assertEquals(listOf("Online", "Offline"), rows.map { it.subtitle })
    }

    @Test
    fun anEmptyRunOnMenuHasNoContentAndRunOnReturnsNull() {
        val menu = buildRunOnMenu(emptyList(), emptyList(), NewSessionDraft(), {}, {})
        assertEquals("Run on", menu.title)
        assertTrue(menu.children.isEmpty())
        assertFalse(menu.hasContent())

        // the provider glue: no checkouts and no hosts → null, so 59 falls
        // through to the no-op chip tap and no panel opens.
        val fixture = newMenusModel(NewSessionOptions(emptyList(), emptyList()))
        val menus = ProjectRunOnMenus(fixture.model, NewProjectPresenter { }, { true })
        assertNull(menus.runOn())
    }

    // ── ProjectRunOnMenus over 92's model (the pick flow) ──────────────

    @Test
    fun aGroupPickRunsExactlyOneUpdateAndClearsTheHost() {
        val fixture = newMenusModel(projectMenusFixtureOptions(), NewSessionDraft(hostId = "e1:dev-mac"))
        val menus = ProjectRunOnMenus(fixture.model, NewProjectPresenter { }, { true })
        val baseline = fixture.disk.writes.get()

        menuRow(menus.project(), "roboco").action()

        // The member on the current device (e1:dev-mac), hostId cleared,
        // through exactly one update — one persist of the changed draft.
        assertEquals(NewSessionDraft(projectId = "e1:space-roboco"), fixture.model.draft)
        assertEquals("exactly one update for the pick", baseline + 1, fixture.disk.writes.get())

        // "No Project…" (the first online host), then Run on → Mac Studio
        // (offline, still pickable) sets only the host.
        menuRow(menus.project(), "No Project…").action()
        assertNull(fixture.model.draft.projectId)
        assertEquals("e2:dev-vps", fixture.model.draft.hostId)
        menuRow(menus.runOn()!!, "Mac Studio").action()
        assertEquals("e3:dev-studio", fixture.model.draft.hostId)
        assertNull(fixture.model.draft.projectId)
        assertEquals("three updates in total", baseline + 3, fixture.disk.writes.get())
    }

    @Test
    fun newProjectOnCreatedAppliesTheCreatedProjectOnlyWhileAlive() {
        val fixture = newMenusModel(projectMenusFixtureOptions())
        var alive = true
        var captured: ((String) -> Unit)? = null
        val menus = ProjectRunOnMenus(fixture.model, NewProjectPresenter { onCreated -> captured = onCreated }, { alive })

        // A branch that withCreatedProject must clear.
        fixture.model.update { it.copy(branch = "main") }

        menuRow(menus.project(), "New Project…").action()
        assertNotNull("the presenter got the callback", captured)
        captured!!("e1:space-mobile")
        assertEquals("e1:space-mobile", fixture.model.draft.projectId)
        assertNull(fixture.model.draft.hostId)
        assertNull(fixture.model.draft.branch)

        // the page is gone (the [weak self] guard): nothing happens.
        alive = false
        captured!!("e2:space-edge")
        assertEquals("e1:space-mobile", fixture.model.draft.projectId)
    }

    // ── the fixture machinery ──────────────────────────────────────────

    /** One row per action row, flattening one level of sections. */
    private fun menuRows(menu: Menu): List<MenuItem> = menu.children.flatMap { element ->
        when (element) {
            is MenuItem -> listOf(element)
            is MenuSection -> element.children.filterIsInstance<MenuItem>()
            else -> emptyList()
        }
    }

    private fun menuRow(menu: Menu, title: String): MenuItem =
        menuRows(menu).firstOrNull { it.title == title }
            ?: throw AssertionError("no row titled \"$title\" among ${menuRows(menu).map { it.title }}")

    private fun assertRunOnRowChrome(rows: List<MenuItem>) {
        rows.forEach { row ->
            assertEquals(MenuIcon.Symbol(RobocoIcons.Desktopcomputer), row.icon)
            assertEquals("run-on-row", row.testTag)
            assertFalse("no row is ever disabled", row.disabled)
        }
    }

    /** Tokens ↔ drafts, so the "disk" can hold a fake-encoded draft without org.json. */
    private class DraftTokenCodec : NewSessionDraftCodec {
        private val byToken = HashMap<String, NewSessionDraft>()
        private var next = 0

        override fun encode(d: NewSessionDraft): String {
            val token = "d${next++}"
            byToken[token] = d
            return token
        }

        override fun decode(json: String?): NewSessionDraft = json?.let { byToken[it] } ?: NewSessionDraft()
    }

    /**
     * The DataStore fake: every `updateData` (one per persist) is counted,
     * so "exactly one update" per pick is observable on the plain JVM.
     */
    private class CountingDraftDisk(initial: String?) : DataStore<Preferences> {
        val writes = AtomicInteger(0)
        private val state = MutableStateFlow(
            initial?.let { preferencesOf(NewSessionDraftStore.KEY to it) } ?: preferencesOf(),
        )

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            writes.incrementAndGet()
            return next
        }
    }

    /** 92's fake catalog: no built-ins, no fetches (no FFI on the JVM). */
    private object EmptyModelCatalog : ModelCatalogSource {
        override fun builtIn(): List<ModelChoice> = emptyList()
        override suspend fun fetch(deviceId: String): List<ModelChoice> = emptyList()
    }

    private class MenusModelFixture(
        val model: NewSessionModel,
        val disk: CountingDraftDisk,
        val options: MutableStateFlow<NewSessionOptions?>,
    )

    /** A page over the fakes: Unconfined everywhere, the store warmed. */
    private fun newMenusModel(
        options: NewSessionOptions,
        draft: NewSessionDraft = NewSessionDraft(),
    ): MenusModelFixture {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val codec = DraftTokenCodec()
        val disk = CountingDraftDisk(codec.encode(draft))
        val drafts = Drafts(FakePrefsStore(), scope, Dispatchers.Unconfined)
        val store = NewSessionDraftStore(
            disk,
            drafts,
            scope,
            io = Dispatchers.Unconfined,
            launchHarness = { null },
            codec = codec,
        )
        store.warm()
        val flow = MutableStateFlow<NewSessionOptions?>(options)
        val model = NewSessionModel(
            prompt = null,
            store = store,
            options = flow,
            catalog = EmptyModelCatalog,
            pageScope = scope,
            appScope = scope,
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
        return MenusModelFixture(model, disk, flow)
    }
}
