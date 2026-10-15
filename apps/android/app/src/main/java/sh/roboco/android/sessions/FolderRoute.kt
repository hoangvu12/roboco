package sh.roboco.android.sessions

import sh.roboco.android.shell.NavRoute
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * Which of the three folder kinds a [FolderRoute] shows — decided by the
 * folder id alone, exactly as iOS decides in `FolderViewController.viewDidLoad`
 * (`ios:Threads/SessionListController.swift:449, :453, :460`): `"pinned"`
 * → Pinned, `"archived"` → Archived, anything else → a user section.
 */
enum class FolderKind { Pinned, Section, Archived }

/**
 * One folder's sessions (android-ui-parity #45) — the pushed route of the
 * Android port of `FolderViewController`
 * (`ios:Threads/SessionListController.swift:437-514`): a large title (the
 * folder name) over one header-less list of session rows, with the same
 * rows, tap navigation, swipe actions, context menu and pull-to-refresh as
 * the front page.
 *
 * The three kinds key off [folderId] (see [folderKind]); [title] is only
 * what the caller shows at push — the screen keeps it in saveable state and
 * a successful Rename changes it in place. Section ids are engine-scoped
 * (`"eng1:sec-p0"`-shaped, android-app#07); Pinned and Archived use the two
 * reserved ids.
 *
 * [hidesBottomChrome] stays `false` for every kind: `FolderViewController`
 * never sets `hidesBottomBarWhenPushed`, so the tab bar and the accessory
 * stay visible over a folder (only the Session screen hides them,
 * `ios:Session/SessionViewController.swift:29`).
 */
data class FolderRoute(val folderId: String, val title: String) : NavRoute {

    /** TalkBack pane title: the folder's name ("Pinned", "Archived", "P0"). */
    override val paneTitle: String get() = title

    companion object {
        /** The merged pin list's reserved id (`FolderRowVM(id: "pinned", …)`). */
        const val PINNED = "pinned"

        /** The archive's reserved id (`FolderRowVM(id: "archived", …)`). */
        const val ARCHIVED = "archived"

        /** The Pinned folder — the title iOS passes (`AppModel.swift:379`). */
        fun pinned() = FolderRoute(PINNED, "Pinned")

        /** The Archived folder (`SessionListController.swift:431`, `MoreViewController.swift:247`). */
        fun archived() = FolderRoute(ARCHIVED, "Archived")

        /** A user section; the title is the name as the caller shows it (possibly suffixed, 40). */
        fun section(id: String, title: String) = FolderRoute(id, title)
    }
}

/**
 * The kind of [id]: `"pinned"` → [FolderKind.Pinned], `"archived"` →
 * [FolderKind.Archived], anything else → [FolderKind.Section] (iOS `:449,
 * :453, :460` — the id alone decides).
 */
fun folderKind(id: String): FolderKind = when (id) {
    FolderRoute.PINNED -> FolderKind.Pinned
    FolderRoute.ARCHIVED -> FolderKind.Archived
    else -> FolderKind.Section
}

/**
 * One folder's rows — iOS `AppModel.sessions(inFolder:)`
 * (`ios:App/AppModel.swift:484-486`): Pinned → `front.pinned`, Archived →
 * `archived`, a section → that `SectionView`'s sessions. An unknown or
 * deleted id gives an empty list (`?? []`): the screen stays open with
 * nothing in it. `"recent"` is **not** a folder id here — iOS has no
 * `sectionSessions["recent"]`, so it resolves to no section and returns
 * empty (unlike pr621's `sessionsIn`, which had an extra branch for it).
 */
fun folderRows(ws: WorkspaceSnapshot, id: String): List<SessionRow> = when (folderKind(id)) {
    FolderKind.Pinned -> ws.front.pinned
    FolderKind.Archived -> ws.archived
    FolderKind.Section -> ws.front.sections.firstOrNull { it.id == id }?.sessions.orEmpty()
}
