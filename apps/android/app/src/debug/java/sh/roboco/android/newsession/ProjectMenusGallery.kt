package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.uiStyle

/**
 * The "New session project menus" page of the debug design gallery
 * (android-ui-parity #113), registered by ticket 22's registry as
 * `GallerySection("New session project menus", screenshotHeightDp = 1400)
 * { ProjectMenusGallerySection() }` (slug `new_session_project_menus`).
 *
 * The Roboco rename of upstream's demo data (`crates/client/src/demo/
 * fixtures.rs:238-355`): three engines and five spaces, two of them
 * clones of the same repository on different engines — the grouping the
 * iOS UI test pins. Five **static panels** (P1, P2, R1, R2, R3 — the
 * same fixtures the Roborazzi goldens capture) show the menus over
 * [GalleryStage]'s busy backdrop, then a **live demo** drives ticket 59's
 * [ChipStrip] with the two chips and the pure builders over a snapshot
 * draft (no `NewSessionModel`): picks update the chips and the caption,
 * and "New Project…" fakes the created space after 600 ms.
 */
@Composable
fun ProjectMenusGallerySection() {
    GlassMenuHost(hazeState = rememberHazeState()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProjectMenusStaticPanels()
            ProjectMenusLiveDemo()
        }
    }
}

// ---------------------------------------------------------------------
// The fixture (the ticket's demo-data rename)
// ---------------------------------------------------------------------

/** `repo:github.com/hoangvu12/roboco` — the two clones' shared group key. */
private const val RobocoRepo = "repo:github.com/hoangvu12/roboco"

private val FixtureHosts = listOf(
    HostOption("e2:dev-vps", "hetzner-01", online = true),
    HostOption("e3:dev-studio", "Mac Studio", online = false),
    HostOption("e1:dev-mac", "MacBook Pro", online = true),
)

/** In creation order (the oldest checkout of each group first). */
private val FixtureProjects = listOf(
    ProjectOption(
        id = "e3:space-blog", name = "blog", path = "/Users/dev/Projects/blog",
        device = "e3:dev-studio", deviceName = "Mac Studio", online = false, git = false,
        groupKey = "e3:space-blog", groupName = "blog", colorIndex = 6,
    ),
    ProjectOption(
        id = "e1:space-roboco", name = "roboco", path = "/Users/dev/roboco",
        device = "e1:dev-mac", deviceName = "MacBook Pro", online = true, git = true,
        groupKey = RobocoRepo, groupName = "roboco", colorIndex = 5,
    ),
    ProjectOption(
        id = "e2:space-roboco-vps", name = "roboco", path = "/srv/src/roboco",
        device = "e2:dev-vps", deviceName = "hetzner-01", online = true, git = true,
        groupKey = RobocoRepo, groupName = "roboco", colorIndex = 5,
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

/** R2's extra clone: a third checkout of the repository, on the same engine as one. */
private val FixtureFork = ProjectOption(
    id = "e1:space-roboco-fork", name = "roboco-fork", path = "/Users/dev/src/roboco-fork",
    device = "e1:dev-mac", deviceName = "MacBook Pro", online = true, git = true,
    groupKey = RobocoRepo, groupName = "roboco", colorIndex = 5,
)

/** The live demo's fake created space ("New Project…" after 600 ms). */
private val FixtureNewProject = ProjectOption(
    id = "e2:space-new", name = "new-thing", path = "/srv/src/new-thing",
    device = "e2:dev-vps", deviceName = "hetzner-01", online = true, git = false,
    groupKey = "e2:space-new", groupName = "new-thing", colorIndex = 6,
)

/** Ticket 92's checkout rule, for the R fixtures and the live demo. */
private fun groupCheckouts(projects: List<ProjectOption>, groupKey: String): List<ProjectOption> =
    projects.filter { it.groupKey == groupKey }
        .sortedWith(compareBy({ it.deviceName.lowercase() }, { it.path }))

// ---------------------------------------------------------------------
// The five static panels (the Roborazzi goldens)
// ---------------------------------------------------------------------

/**
 * P1, P2, R1, R2, R3 — each menu's flattened lines in a stateless
 * [GlassMenuPanel] over a [GalleryStage], so the glass has the busy
 * backdrop under it. Shared with `ProjectMenusScreenshotTest`.
 */
@Composable
internal fun ProjectMenusStaticPanels() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Stage heights leave room for the font-scale-1.3 golden (the
        // panels grow ~30% taller; see ProjectMenusScreenshotTest).
        PanelStage("P1 — project menu, a checkout picked (roboco checked)", projectMenuP1(), 500.dp)
        PanelStage("P2 — project menu, no project (No Project… checked)", projectMenuP2(), 500.dp)
        PanelStage("R1 — run on, the repository's two checkouts", runOnMenuR1(), 240.dp)
        PanelStage("R2 — run on, a third clone on the same engine (paths)", runOnMenuR2(), 310.dp)
        PanelStage("R3 — run on without a project (every engine)", runOnMenuR3(), 240.dp)
    }
}

/** P1: the roboco group is checked (the checkmark replaces its tile). */
internal fun projectMenuP1(): Menu = buildProjectMenu(
    projects = FixtureProjects,
    draft = NewSessionDraft(projectId = "e1:space-roboco"),
    onPickGroup = {},
    onNoProject = {},
    onNewProject = {},
)

/** P2: projectless — no group checked, "No Project…" checked. */
internal fun projectMenuP2(): Menu = buildProjectMenu(
    projects = FixtureProjects,
    draft = NewSessionDraft(projectId = null, hostId = "e2:dev-vps"),
    onPickGroup = {},
    onNoProject = {},
    onNewProject = {},
)

/** R1: the roboco group's two checkouts, one per engine. */
internal fun runOnMenuR1(): Menu = buildRunOnMenu(
    checkouts = groupCheckouts(FixtureProjects, RobocoRepo),
    hosts = FixtureHosts,
    draft = NewSessionDraft(projectId = "e1:space-roboco"),
    onPickCheckout = {},
    onPickHost = {},
)

/** R2: with the extra clone — both MacBook Pro rows show their paths. */
internal fun runOnMenuR2(): Menu = buildRunOnMenu(
    checkouts = groupCheckouts(FixtureProjects + FixtureFork, RobocoRepo),
    hosts = FixtureHosts,
    draft = NewSessionDraft(projectId = "e1:space-roboco"),
    onPickCheckout = {},
    onPickHost = {},
)

/** R3: no project — every paired engine, Mac Studio offline. */
internal fun runOnMenuR3(): Menu = buildRunOnMenu(
    checkouts = emptyList(),
    hosts = FixtureHosts,
    draft = NewSessionDraft(projectId = null, hostId = "e1:dev-mac"),
    onPickCheckout = {},
    onPickHost = {},
)

/** One captioned menu panel over the busy backdrop. */
@Composable
private fun PanelStage(caption: String, menu: Menu, height: Dp) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(caption, style = uiStyle(Face.SansMedium, typeScale.fixed(12f), palette.secondary))
        GlassStage(height = height) {
            GlassMenuPanel(
                lines = flattenLevel(
                    menu.title,
                    menu.children,
                    resolved = { null },
                    isRoot = true,
                    backTitle = null,
                ),
                onRow = {},
                onSubmenu = {},
                onBack = {},
            )
        }
    }
}

// ---------------------------------------------------------------------
// The live demo: the two chips over the pure builders, no page model
// ---------------------------------------------------------------------

/**
 * A [ChipStrip] with the `project` and `host` chips (109's titles, no
 * model chip), driven by a snapshot draft and the pure builders: a group
 * pick lands on the current-device checkout, "No Project…" falls back to
 * the first online engine, a Run on pick switches the checkout or the
 * engine, and "New Project…" adds `new-thing` and picks it 600 ms later
 * (no sheet here — the presentation itself is 110's). The caption prints
 * the draft's three fields this ticket can change.
 */
@Composable
private fun ProjectMenusLiveDemo() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(NewSessionDraft(projectId = "e1:space-roboco")) }
    var projects by remember { mutableStateOf(FixtureProjects) }
    var note by remember { mutableStateOf("Tap a chip — the menus are the page's real content.") }

    val picked = projects.firstOrNull { it.id == draft.projectId }
    val chips = listOf(
        picked?.let { p ->
            ComposerChip(
                id = NewSessionChipIds.Project,
                title = p.groupName,
                icon = ChipIcon.Project(p.groupName, p.colorIndex),
            )
        } ?: ComposerChip(id = NewSessionChipIds.Project, title = "No project", symbol = "tray"),
        ComposerChip(
            id = NewSessionChipIds.Host,
            title = picked?.deviceName
                ?: FixtureHosts.firstOrNull { it.id == draft.hostId }?.name
                ?: "Choose host",
            symbol = "desktopcomputer",
        ),
    )
    val menus = mapOf(
        NewSessionChipIds.Project to {
            buildProjectMenu(
                projects = projects,
                draft = draft,
                onPickGroup = { members ->
                    val device = projects.firstOrNull { it.id == draft.projectId }?.device
                        ?: draft.hostId ?: ""
                    draft = draft.withCheckout(pickProjectTarget(members, draft, device))
                },
                onNoProject = { draft = draft.withNoProject(FixtureHosts) },
                onNewProject = {
                    note = "New project sheet (110) would stack here; demo: new-thing in 600 ms"
                    scope.launch {
                        delay(600)
                        if (projects.none { it.id == FixtureNewProject.id }) {
                            projects = projects + FixtureNewProject
                        }
                        draft = draft.withCreatedProject(FixtureNewProject.id)
                        note = "Created new-thing on hetzner-01; the draft picked it"
                    }
                },
            )
        },
        NewSessionChipIds.Host to {
            val checkouts = picked?.let { p -> groupCheckouts(projects, p.groupKey) }.orEmpty()
            buildRunOnMenu(
                checkouts = checkouts,
                hosts = FixtureHosts,
                draft = draft,
                onPickCheckout = { p -> draft = draft.withCheckout(p) },
                onPickHost = { h -> draft = draft.withHost(h) },
            )
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "Live — the pure builders over a draft (no page model); New Project… fakes the created space",
            style = uiStyle(Face.SansMedium, typeScale.fixed(12f), palette.secondary),
        )
        Box(Modifier.width(376.dp)) {
            ChipStrip(chips = chips, chipMenus = menus, canWrite = true, onChipTap = {})
        }
        BasicText(
            "draft · projectId=${draft.projectId ?: "—"} · hostId=${draft.hostId ?: "—"} · branch=${draft.branch ?: "—"}",
            style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
        )
        BasicText(note, style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary))
    }
}

// ---------------------------------------------------------------------------
// Previews (the BRIEF's component-ticket convention): P1 + R2 side by side.
// ---------------------------------------------------------------------------

@Composable
private fun ProjectMenusPanelsPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        GlassMenuHost(hazeState = rememberHazeState()) {
            Row(
                Modifier.height(470.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    GalleryBackdrop(Modifier.matchParentSize())
                    MenuPanelForPreview(projectMenuP1())
                }
                Box(Modifier.weight(1f)) {
                    GalleryBackdrop(Modifier.matchParentSize())
                    MenuPanelForPreview(runOnMenuR2())
                }
            }
        }
    }
}

@Composable
private fun MenuPanelForPreview(menu: Menu) {
    GlassMenuPanel(
        lines = flattenLevel(
            menu.title,
            menu.children,
            resolved = { null },
            isRoot = true,
            backTitle = null,
        ),
        onRow = {},
        onSubmenu = {},
        onBack = {},
    )
}

@Preview(name = "New session project menus light")
@Composable
private fun ProjectMenusLightPreview() {
    ProjectMenusPanelsPreviewBody(Appearance.Light)
}

@Preview(name = "New session project menus dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProjectMenusDarkPreview() {
    ProjectMenusPanelsPreviewBody(Appearance.Dark)
}
