package sh.roboco.android.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.LocalNavStack

/**
 * The Sessions tab's menus (android-ui-parity #46) — the port of
 * `SessionsViewController`'s two `UIMenu` builders
 * (`ios:Threads/SessionListController.swift:383-434`):
 * [sessionsOptionsMenu] (the ⋯ bar item) and [sessionHeaderMenu] (the
 * header long-press menus).
 *
 * Both are **pure**: they build the menu from the [SessionsScreenActions]
 * seam and invoke nothing until a row is picked. They are built at
 * **trigger time** (the ⋯ tap, the long press) — never during
 * composition — so the Group-by-Project check and the section names are
 * read at open time, exactly as iOS's `UIDeferredMenuElement.uncached`
 * and its `headerMenu(_:)` override do.
 */

/**
 * What the screen and its menus do; the real one wires 40/42/43/45/24
 * (`rememberSessionsActions`), tests and the gallery pass a recorder.
 * Main-thread API, called from click handlers and menu `onSelect`s.
 */
interface SessionsScreenActions {

    /**
     * The Group-by-Project pref, read at **menu-open time** (iOS's
     * uncached deferred element reads `app.groupByProject` when the menu
     * opens, `:425-429`). A getter: every access reads the live value.
     */
    val groupByProject: Boolean

    /** Flips the pref (40's `Prefs.setGroupByProject`; Settings 100 flips the same one). */
    fun setGroupByProject(on: Boolean)

    /** ⋯ "New Section…" → 43's "from ⋯" flow (no fixed engine; the chooser is 43's). */
    fun newSection()

    /** ⋯ "Collapse All" → 40's `FrontPageModel.collapseAll`. */
    fun collapseAll()

    /** A header tap → 40's `FrontPageModel.toggleSection(id, feedback)`. */
    fun toggleSection(id: String)

    /** The Pinned header menu's "Open" and "Reorder…" (both push the Pinned page, 45). */
    fun openPinned()

    /** ⋯ "Archived" → 45's Archived folder page on this tab's stack. */
    fun openArchived()

    /** A user section's "Open" → 45's section folder page. */
    fun openSection(id: String, name: String)

    /** A user section's "Rename Section…" → 43's alert, pre-filled with the raw name. */
    fun renameSection(id: String, currentName: String)

    /** A user section's "Delete Section" → 42's routed, toasted write — no confirmation. */
    fun deleteSection(id: String)

    /**
     * Raw name of a user section (40's `WorkspaceSnapshot.front.sections[i].name`),
     * or null when `id` is not a user section (Pinned, Recent, a project
     * group, an unknown id) — the iOS `guard let folder = …` rule.
     */
    fun userSectionName(id: String): String?
}

/**
 * The ⋯ options menu (`SessionListController.swift:411-434`): four rows,
 * one flat list, no title, no separators. Every row uses a ticket 06
 * `MenuIcon.Symbol`.
 *
 * The Group-by-Project check is read **here** — the moment the menu is
 * built (open time) — never inside the action, and the toggle reads the
 * live pref again when it runs, so a Settings change made while the menu
 * was open is never undone (`app.groupByProject.toggle()` flips the live
 * value on iOS).
 */
fun sessionsOptionsMenu(actions: SessionsScreenActions): Menu = Menu(
    children = listOf(
        MenuItem(
            title = "New Section\u2026",
            icon = MenuIcon.Symbol(RobocoIcons.FolderBadgePlus),
            testTag = "sessions-options-new-section",
        ) { actions.newSection() },
        MenuItem(
            title = "Collapse All",
            icon = MenuIcon.Symbol(RobocoIcons.RectangleCompressVertical),
            testTag = "sessions-options-collapse-all",
        ) { actions.collapseAll() },
        // iOS: UIDeferredMenuElement.uncached { done([UIAction(state:
        // app.groupByProject ? .on : .off) …]) } — `done` runs
        // synchronously, so a plain MenuItem read at build time is the
        // same result (the ticket's ADAPT).
        run {
            val grouped = actions.groupByProject
            MenuItem(
                title = "Group by Project",
                icon = MenuIcon.Symbol(RobocoIcons.SquareStack3dUp),
                checked = grouped,
                testTag = "sessions-options-group-by-project",
            ) { actions.setGroupByProject(!actions.groupByProject) }
        },
        MenuItem(
            title = "Archived",
            icon = MenuIcon.Symbol(RobocoIcons.Archivebox),
            testTag = "sessions-options-archived",
        ) { actions.openArchived() },
    ),
)

/**
 * A section header's long-press menu (`SessionListController.swift:383-398`),
 * classified by the header id with the iOS rules (the same answer as 40's
 * `FrontPageUi.meta[id].kind`):
 *
 * - `"recent"` → **null** (no menu, no lift, no haptic; a tap still folds);
 * - a project group (`project:…` / `home:…`) or an unknown id → **null**
 *   (iOS `guard let folder = app.frontPage.folders.first(where: …)` fails);
 * - `"pinned"` → "Open" + "Reorder…" (both push the merged Pinned page —
 *   "Reorder…" never turns on Edit mode; the user taps Edit there, 47);
 * - a user section → "Open", "Rename Section…" (pre-filled with the
 *   **raw** name, never the header title with 40's `" · <engine>"`
 *   collision suffix) and "Delete Section" (destructive, **immediate** —
 *   iOS calls `app.deleteSection` with no confirmation; only the folder
 *   page's ⋯ asks first, 45).
 */
internal fun sessionHeaderMenu(id: String, actions: SessionsScreenActions): Menu? {
    if (id == FrontPageSections.RECENT) return null
    if (id == FrontPageSections.PINNED) {
        return Menu(
            children = listOf(
                MenuItem(
                    title = "Open",
                    icon = MenuIcon.Symbol(RobocoIcons.ArrowUpRight),
                    testTag = "section-menu-open",
                ) { actions.openPinned() },
                MenuItem(
                    title = "Reorder\u2026",
                    icon = MenuIcon.Symbol(RobocoIcons.ArrowUpArrowDown),
                    testTag = "section-menu-reorder",
                ) { actions.openPinned() },
            ),
        )
    }
    val name = actions.userSectionName(id) ?: return null
    return Menu(
        children = listOf(
            MenuItem(
                title = "Open",
                icon = MenuIcon.Symbol(RobocoIcons.ArrowUpRight),
                testTag = "section-menu-open",
            ) { actions.openSection(id, name) },
            MenuItem(
                title = "Rename Section\u2026",
                icon = MenuIcon.Symbol(RobocoIcons.Pencil),
                testTag = "section-menu-rename",
            ) { actions.renameSection(id, name) },
            MenuItem(
                title = "Delete Section",
                icon = MenuIcon.Symbol(RobocoIcons.Trash),
                destructive = true,
                testTag = "section-menu-delete",
            ) { actions.deleteSection(id) },
        ),
    )
}

// ---------------------------------------------------------------------------
// The real actions (40/42/43/45/24) and the tablet toolbar's reuse
// ---------------------------------------------------------------------------

/**
 * The production [SessionsScreenActions]: one object wiring the ⋯ and the
 * header menus to the app's singletons (40's `FrontPageModel` and `Prefs`,
 * 42's [RowActions], 43's [SessionPrompts], 45's [FolderRoute] pushes on
 * [navStack], 24's `push(route, animated = true)`). `userSectionName`
 * resolves through the live workspace snapshot, so the Rename prefill
 * follows a rename made elsewhere — iOS captures the name at push (an
 * upstream bug we do not copy).
 */
@Composable
internal fun rememberSessionsActions(navStack: NavStack): SessionsScreenActions {
    val app = LocalContext.current.applicationContext as RobocoApplication
    val frontPage = app.frontPage
    val prefs = app.prefs
    val feed = app.workspaceFeed
    // The prompts' engine list comes from the live client, resolved at
    // call time (43's CoreSessionPromptBackend rule). Passed explicitly,
    // not through the ambient, so this also works outside the sessions
    // tab (the tablet toolbar, 107).
    val backend = remember(app) { CoreSessionPromptBackend(app::clientOrNull) }
    val prompts = rememberSessionPrompts(backend = backend)
    val rowActions = LocalRowActions.current
    val feedback = LocalFeedback.current
    return remember(navStack, frontPage, prefs, feed, prompts, rowActions, feedback) {
        object : SessionsScreenActions {
            override val groupByProject: Boolean
                get() = prefs.groupByProject.value

            override fun setGroupByProject(on: Boolean) = prefs.setGroupByProject(on)

            override fun newSection() = prompts.newSection(engineId = null)

            override fun collapseAll() = frontPage.collapseAll(feedback)

            override fun toggleSection(id: String) = frontPage.toggleSection(id, feedback)

            override fun openPinned() {
                navStack.push(FolderRoute.pinned(), animated = true)
            }

            override fun openArchived() {
                navStack.push(FolderRoute.archived(), animated = true)
            }

            override fun openSection(id: String, name: String) {
                navStack.push(FolderRoute.section(id, name), animated = true)
            }

            override fun renameSection(id: String, currentName: String) =
                prompts.renameSection(id, currentName)

            override fun deleteSection(id: String) = rowActions.deleteSection(id)

            override fun userSectionName(id: String): String? =
                feed.snapshot.value?.front?.sections?.firstOrNull { it.id == id }?.name
        }
    }
}

/**
 * The same options menu for the tablet sidebar toolbar's ⋯
 * (`ios:Shell/SplitRootController.swift:244`): the actions bound to the
 * sidebar's own [navStack] instead of the phone tab's. Ticket 107 calls
 * this with the sidebar's stack; the menu is still built on each open —
 * call the returned lambda from the button's `onClick`, never during
 * composition.
 */
@Composable
fun rememberSessionsOptionsMenu(navStack: NavStack = LocalNavStack.current): () -> Menu {
    val actions = rememberSessionsActions(navStack)
    return remember(actions) { { sessionsOptionsMenu(actions) } }
}
