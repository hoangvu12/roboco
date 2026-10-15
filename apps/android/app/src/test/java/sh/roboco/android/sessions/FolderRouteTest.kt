package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * `FolderRoute` / [folderKind] / [folderRows] (android-ui-parity #45), the
 * port of `FolderViewController.viewDidLoad`'s id switch
 * (`ios:Threads/SessionListController.swift:449-513`) and
 * `AppModel.sessions(inFolder:)` (`ios:App/AppModel.swift:484-486`). Pure
 * and plain-JVM: the FFI records are plain data classes (the same property
 * `FrontPageSectionsTest` relies on).
 */
class FolderRouteTest {

    // ------------------------------------------------------------ fixtures

    /** A row list with distinct ids, so equality failures name the row. */
    private fun rows(vararg ids: String): List<SessionRow> = ids.map { row(it) }

    /** A workspace with a Pinned list, the "P0" section and an archive. */
    private fun ws(
        pinned: List<SessionRow> = emptyList(),
        sectionRows: List<SessionRow> = emptyList(),
        archived: List<SessionRow> = emptyList(),
    ): WorkspaceSnapshot {
        val base = snapshot(
            7uL,
            front = front(
                pinned = pinned,
                sections = listOf(section("eng1:sec-p0", name = "P0", rows = sectionRows)),
                recent = rows("r1"),
            ),
        )
        // WorkspaceFixtures.snapshot hardcodes an empty archive; rebuild it
        // with the archived list the Archived folder must read.
        return base.copy(archived = archived)
    }

    // --------------------------------------------------------------- kinds

    @Test
    fun theIdAloneDecidesTheKind() {
        assertEquals(FolderKind.Pinned, folderKind("pinned"))
        assertEquals(FolderKind.Archived, folderKind("archived"))
        assertEquals(FolderKind.Section, folderKind("eng1:abc"))
        // An id that merely contains the reserved words is a section.
        assertEquals(FolderKind.Section, folderKind("eng1:pinned"))
        assertEquals(FolderKind.Section, folderKind("eng1:archived"))
        // "recent" is not a folder (iOS has no sectionSessions["recent"]).
        assertEquals(FolderKind.Section, folderKind("recent"))
    }

    // --------------------------------------------------------------- rows

    @Test
    fun pinnedRowsAreTheFrontPagePins() {
        val pins = rows("p1", "p2")
        assertEquals(pins, folderRows(ws(pinned = pins), "pinned"))
    }

    @Test
    fun archivedRowsAreTheArchive() {
        val archived = rows("a1", "a2")
        assertEquals(archived, folderRows(ws(archived = archived), "archived"))
    }

    @Test
    fun sectionRowsAreThatSectionsSessions() {
        val sectionRows = rows("s1", "s2", "s3")
        val snapshot = ws(sectionRows = sectionRows)
        assertEquals(
            snapshot.front.sections.first { it.id == "eng1:sec-p0" }.sessions,
            folderRows(snapshot, "eng1:sec-p0"),
        )
    }

    @Test
    fun anUnknownOrDeletedIdGivesAnEmptyList() {
        val snapshot = ws(sectionRows = rows("s1"))
        assertTrue(folderRows(snapshot, "eng1:gone").isEmpty())
        // "recent" resolves to no section: empty, not ws.front.recent.
        assertTrue(folderRows(snapshot, "recent").isEmpty())
    }

    // ---------------------------------------------------------- the route

    @Test
    fun theFactoriesBuildTheExpectedRoutes() {
        assertEquals(FolderRoute("pinned", "Pinned"), FolderRoute.pinned())
        assertEquals(FolderRoute("archived", "Archived"), FolderRoute.archived())
        assertEquals(FolderRoute("eng1:sec-p0", "P0"), FolderRoute.section("eng1:sec-p0", "P0"))
    }

    @Test
    fun thePaneTitleIsTheFolderName() {
        assertEquals("Pinned", FolderRoute.pinned().paneTitle)
        assertEquals("Archived", FolderRoute.archived().paneTitle)
        assertEquals("P0", FolderRoute.section("eng1:sec-p0", "P0").paneTitle)
    }

    @Test
    fun theBottomChromeStaysVisibleOverEveryKind() {
        // FolderViewController never sets hidesBottomBarWhenPushed
        // (only the Session screen hides the bar, SessionViewController.swift:29).
        assertFalse(FolderRoute.pinned().hidesBottomChrome)
        assertFalse(FolderRoute.archived().hidesBottomChrome)
        assertFalse(FolderRoute.section("eng1:sec-p0", "P0").hidesBottomChrome)
    }

    @Test
    fun routeEqualityCoversTheIdAndTheTitle() {
        // 46/100 push equal routes; setRoutes reuses an old entry by route
        // equality, so a re-pushed folder must match the first one.
        assertEquals(FolderRoute.section("eng1:sec-p0", "P0"), FolderRoute.section("eng1:sec-p0", "P0"))
        assertNotEquals(FolderRoute.section("eng1:sec-p0", "P0"), FolderRoute.section("eng1:sec-p0", "P1"))
        assertNotEquals(FolderRoute.section("eng1:sec-p0", "P0"), FolderRoute.section("eng1:sec-p1", "P0"))
    }
}
