package sh.roboco.android.newsession

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import sh.roboco.android.composer.ChipMenuProvider
import sh.roboco.android.design.ProjectTile
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.design.glass.hasContent

/**
 * The Project and Run on menus (android-ui-parity #113) — the port of
 * `NewSessionViewController.projectMenu()` and `hostMenu()`
 * (`ios:Session/NewSessionViewController.swift:280-359`), as pure builders
 * over ticket 92's records plus one glue class reading the page model at
 * tap time.
 *
 * Everything they draw is other tickets': the panel, rows, checkmarks and
 * separators are ticket 15's [Menu] model, the 18 dp monogram is ticket
 * 12's [ProjectTile], the chips and the tap routing are 109/59, and every
 * change to the draft goes through ticket 92's `NewSessionModel.update`
 * (which persists it and reloads the per-engine model catalog — the iOS
 * `loadModels()` after each pick). This file owns only the content:
 * which rows, in which order, with which subtitle, checkmark and action.
 */
// ---------------------------------------------------------------------
// Grouping (pure)
// ---------------------------------------------------------------------

/** One Project-menu row: checkouts of one repository, on any engine. */
@Immutable
data class ProjectGroup(
    /** The shared `ProjectView.groupKey` (`"repo:<repositoryId>"`, else the scoped space id). */
    val groupKey: String,
    /** The representative checkout's display name, shared by the group. */
    val name: String,
    /** The representative checkout's `FNV-1a % 8` index, shared by the group. */
    val colorIndex: Int,
    /** The members' engine names, de-duplicated and collator-sorted, joined with `", "`. */
    val hosts: String,
    /** The group's checkouts in options order (the oldest first). */
    val members: List<ProjectOption>,
)

/**
 * Foundation's `localizedCaseInsensitiveCompare` as a `Comparator`: a
 * `java.text.Collator` at `SECONDARY` strength (case ignored, accents
 * significant), the closest Android equivalent of
 * `compare(_:options: .caseInsensitive, locale: .current)`.
 *
 * The collator is not thread-safe and not cheap to build: one is created
 * per [projectGroups] call (menus are built on the main thread, once per
 * tap, so the cost is negligible) — do not share a single instance.
 */
fun localizedCaseInsensitive(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * Groups the workspace's projects into one Project-menu row per
 * repository (iOS `projectMenu`'s grouping loop, `:282-289`):
 *
 * - options order is creation order, and a group's members keep it (the
 *   first member is the oldest checkout — the representative);
 * - the groups are stable-sorted by their name with the localized
 *   case-insensitive comparator (equal names keep first-appearance order);
 * - `hosts` is the members' `deviceName`s, de-duplicated by exact string
 *   (first-seen order kept) and sorted with the same comparator.
 */
fun projectGroups(
    projects: List<ProjectOption>,
    compare: Comparator<String> = localizedCaseInsensitive(),
): List<ProjectGroup> {
    val byKey = LinkedHashMap<String, MutableList<ProjectOption>>()
    for (p in projects) byKey.getOrPut(p.groupKey) { mutableListOf() }.add(p)
    return byKey.values
        .map { m ->
            ProjectGroup(
                groupKey = m[0].groupKey,
                name = m[0].groupName,
                colorIndex = m[0].colorIndex,
                hosts = m.map { it.deviceName }.distinct().sortedWith(compare).joinToString(", "),
                members = m,
            )
        }
        .sortedWith(compareBy(compare) { it.name }) // Kotlin's sortedWith is stable
}

// ---------------------------------------------------------------------
// Draft transitions (pure, on ticket 92's NewSessionDraft)
// ---------------------------------------------------------------------

/**
 * Which checkout a group pick lands on (iOS `pickProject`,
 * `:323-331`), in preference order: the current checkout if it belongs
 * to the group (re-picking the same group changes nothing), else the
 * member on the current device, else the first online member, else the
 * first member. [deviceId] is read at pick time, like the Swift.
 */
fun pickProjectTarget(
    members: List<ProjectOption>,
    draft: NewSessionDraft,
    deviceId: String,
): ProjectOption =
    members.firstOrNull { it.id == draft.projectId }
        ?: members.firstOrNull { it.device == deviceId }
        ?: members.firstOrNull { it.online }
        ?: members[0]

/**
 * iOS `pickCheckout` (`:333-338`): the invariant `picked project ⇒
 * hostId == null`, and `branch` cleared only when the checkout id
 * changed (the branch belongs to the old checkout). Never touches
 * `worktree`.
 */
fun NewSessionDraft.withCheckout(p: ProjectOption): NewSessionDraft = copy(
    projectId = p.id,
    hostId = null,
    branch = if (p.id != projectId) null else branch,
)

/**
 * The "No Project…" action (`:302-307`): keep the host when one is
 * picked, else the first online host, else the first host. Never touches
 * `branch` or `worktree`.
 */
fun NewSessionDraft.withNoProject(hosts: List<HostOption>): NewSessionDraft = copy(
    projectId = null,
    hostId = hostId ?: (hosts.firstOrNull { it.online } ?: hosts.firstOrNull())?.id,
)

/**
 * The browser's `onCreated(id)` (`:311-316`): the new project is picked,
 * with no host and no branch.
 */
fun NewSessionDraft.withCreatedProject(id: String): NewSessionDraft = copy(
    projectId = id,
    hostId = null,
    branch = null,
)

/** The projectless host pick (`:354-357`): only `hostId` changes. */
fun NewSessionDraft.withHost(h: HostOption): NewSessionDraft = copy(hostId = h.id)

// ---------------------------------------------------------------------
// The menus (pure builders — the gallery and the tests call them directly)
// ---------------------------------------------------------------------

/**
 * The Project menu (`:280-321`): section 1 one row per repository group,
 * section 2 "No Project…" and "New Project…", both untitled inline
 * sections (ticket 15 draws the separator between them, and drops an
 * empty section 1 with its separator, exactly UIKit's omission).
 *
 * A group row's checkmark follows the picked project's `groupKey` (a
 * stale `projectId` checks nothing); its icon is the 18 dp monogram,
 * never tinted (the `ink` is ignored on purpose).
 */
fun buildProjectMenu(
    projects: List<ProjectOption>,
    draft: NewSessionDraft,
    onPickGroup: (List<ProjectOption>) -> Unit,
    onNoProject: () -> Unit,
    onNewProject: () -> Unit,
    compare: Comparator<String> = localizedCaseInsensitive(),
): Menu {
    val picked = projects.firstOrNull { it.id == draft.projectId }?.groupKey
    val groups = projectGroups(projects, compare).map { g ->
        MenuItem(
            title = g.name,
            subtitle = g.hosts,
            icon = MenuIcon.Custom { _ -> ProjectTile(g.name, g.colorIndex, side = 18.dp) },
            checked = g.groupKey == picked,
            testTag = "project-menu-group",
        ) { onPickGroup(g.members) }
    }
    val fixed = listOf(
        MenuItem(
            title = "No Project…",
            icon = MenuIcon.Symbol(RobocoIcons.Tray),
            checked = draft.projectId == null,
            testTag = "project-menu-none",
        ) { onNoProject() },
        MenuItem(
            title = "New Project…",
            icon = MenuIcon.Symbol(RobocoIcons.FolderBadgePlus),
            checked = false,
            testTag = "project-menu-new",
        ) { onNewProject() },
    )
    return Menu(
        title = "Project",
        children = listOf(
            MenuSection(inline = true, children = groups),
            MenuSection(inline = true, children = fixed),
        ),
    )
}

/**
 * The Run on menu (`:340-359`): with a project, one row per checkout of
 * its group (ticket 92's `checkouts` order — lowercased device name,
 * then path); without one, one row per paired engine, offline included.
 *
 * The subtitle is the checkout's path only when more than one checkout of
 * the group sits on the same **device id** (the test is on the id, never
 * the name); otherwise `Online`/`Offline`. No row is ever disabled — an
 * offline target is picked and fails later, as on iOS.
 */
fun buildRunOnMenu(
    /** Empty → the host branch (92's `checkouts`). */
    checkouts: List<ProjectOption>,
    hosts: List<HostOption>,
    draft: NewSessionDraft,
    onPickCheckout: (ProjectOption) -> Unit,
    onPickHost: (HostOption) -> Unit,
): Menu {
    val children: List<MenuElement> = if (checkouts.isNotEmpty()) {
        checkouts.map { p ->
            val shared = checkouts.count { it.device == p.device } > 1
            MenuItem(
                title = p.deviceName,
                subtitle = if (shared) p.path else if (p.online) "Online" else "Offline",
                icon = MenuIcon.Symbol(RobocoIcons.Desktopcomputer),
                checked = p.id == draft.projectId,
                testTag = "run-on-row",
            ) { onPickCheckout(p) }
        }
    } else {
        hosts.map { h ->
            MenuItem(
                title = h.name,
                subtitle = if (h.online) "Online" else "Offline",
                icon = MenuIcon.Symbol(RobocoIcons.Desktopcomputer),
                checked = h.id == draft.hostId,
                testTag = "run-on-row",
            ) { onPickHost(h) }
        }
    }
    return Menu(title = "Run on", children = children)
}

// ---------------------------------------------------------------------
// The glue: ticket 92's model + 110's presenter, read at tap time
// ---------------------------------------------------------------------

/**
 * The provider glue 109 mounts in `composer.chipMenus` — the port of the
 * two closures `composer.chipMenus` holds on iOS (`:270-276`). The menus
 * are built **at tap time** (ticket 59 calls the provider then, never
 * caching), so the options, the checked state and `deviceId` are always
 * current; the menu actions go through [NewSessionModel.update] — one
 * `update` per pick, which persists the draft and reloads the model
 * catalog (the iOS `loadModels()` call after every pick).
 */
class ProjectRunOnMenus(
    private val model: NewSessionModel,
    private val newProject: NewProjectPresenter,
    /** The page's liveness guard — the iOS `[weak self]` of `onCreated`. */
    private val isAlive: () -> Boolean,
) {

    /** The project chip's menu, over the model's current options. */
    fun project(): Menu {
        val options = model.currentOptions
        return buildProjectMenu(
            projects = options?.projects.orEmpty(),
            draft = model.draft,
            onPickGroup = { members ->
                // deviceId depends on the current draft: read it before
                // the update, at pick time (iOS reads it in the action).
                val deviceId = model.deviceId
                model.update { d -> d.withCheckout(pickProjectTarget(members, d, deviceId)) }
            },
            onNoProject = {
                val hosts = model.currentOptions?.hosts.orEmpty()
                model.update { it.withNoProject(hosts) }
            },
            onNewProject = {
                newProject.present { id ->
                    if (isAlive()) model.update { it.withCreatedProject(id) }
                }
            },
        )
    }

    /**
     * The host chip's menu: null when there is nothing to show (no
     * checkouts and no hosts). Ticket 59 then falls through to the
     * no-op `onChipTap`, so the tap opens no panel — UIKit's behaviour
     * for a menu with no children.
     */
    fun runOn(): Menu? = buildRunOnMenu(
        checkouts = model.checkouts,
        hosts = model.currentOptions?.hosts.orEmpty(),
        draft = model.draft,
        onPickCheckout = { p -> model.update { it.withCheckout(p) } },
        onPickHost = { h -> model.update { it.withHost(h) } },
    ).takeIf { it.hasContent() }

    /** The two chip ids 109's strip looks up: `project` and `host`. */
    fun providers(): Map<String, ChipMenuProvider> = mapOf(
        NewSessionChipIds.Project to { project() },
        NewSessionChipIds.Host to { runOn() },
    )
}
