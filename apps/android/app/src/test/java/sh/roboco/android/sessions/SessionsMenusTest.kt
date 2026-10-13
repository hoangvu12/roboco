package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem

/**
 * [sessionsOptionsMenu] and [sessionHeaderMenu] (android-ui-parity #46) —
 * plain JVM, no Robolectric: the menus are pure data over a recording
 * [SessionsScreenActions], and `R.drawable` fields are compile-time ints
 * (the ticket 06 pattern).
 */
class SessionsMenusTest {

    // ------------------------------------------------------------ the fake

    /**
     * Records every call as one line; `userSectionName` answers from a
     * mutable map so the tests can flip what the workspace reports.
     */
    private class RecordingActions(
        // Not an overriding `var`: its setter would clash with
        // setGroupByProject(Boolean) on the JVM.
        var grouped: Boolean = false,
    ) : SessionsScreenActions {
        override val groupByProject: Boolean get() = grouped
        val calls = mutableListOf<String>()
        val sectionNames = mutableMapOf(
            "sec-p0" to "P0",
            "sec-mobile" to "Mobile",
        )

        override fun setGroupByProject(on: Boolean) {
            calls += "setGroupByProject($on)"
        }

        override fun newSection() {
            calls += "newSection()"
        }

        override fun collapseAll() {
            calls += "collapseAll()"
        }

        override fun toggleSection(id: String) {
            calls += "toggleSection($id)"
        }

        override fun openPinned() {
            calls += "openPinned()"
        }

        override fun openArchived() {
            calls += "openArchived()"
        }

        override fun openSection(id: String, name: String) {
            calls += "openSection($id, $name)"
        }

        override fun renameSection(id: String, currentName: String) {
            calls += "renameSection($id, $currentName)"
        }

        override fun deleteSection(id: String) {
            calls += "deleteSection($id)"
        }

        override fun userSectionName(id: String): String? = sectionNames[id]
    }

    /** The menu's [MenuItem] children, in order. */
    private fun rows(menu: Menu): List<MenuItem> =
        menu.children.filterIsInstance<MenuItem>()

    // ------------------------------------------------------- the ⋯ menu

    @Test
    fun theOptionsMenuHasFourRowsWithTheExactTitlesIconsAndTags() {
        val menu = sessionsOptionsMenu(RecordingActions())
        assertNull("no menu title", menu.title)
        val items = rows(menu)
        assertEquals(4, items.size)
        assertEquals("exactly four rows, nothing else", 4, menu.children.size)

        assertEquals("New Section\u2026", items[0].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.FolderBadgePlus), items[0].icon)
        assertEquals("sessions-options-new-section", items[0].testTag)

        assertEquals("Collapse All", items[1].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.RectangleCompressVertical), items[1].icon)
        assertEquals("sessions-options-collapse-all", items[1].testTag)

        assertEquals("Group by Project", items[2].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.SquareStack3dUp), items[2].icon)
        assertEquals("sessions-options-group-by-project", items[2].testTag)

        assertEquals("Archived", items[3].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Archivebox), items[3].icon)
        assertEquals("sessions-options-archived", items[3].testTag)

        items.forEach { item -> assertFalse("no destructive row in the ⋯ menu", item.destructive) }
    }

    @Test
    fun theCheckMatchesThePrefAtBuildTimeAndIsASnapshot() {
        val menu = sessionsOptionsMenu(RecordingActions(grouped = true))
        assertTrue(rows(menu)[2].checked)

        val off = sessionsOptionsMenu(RecordingActions(grouped = false))
        assertFalse(rows(off)[2].checked)

        // A recorder flip after the build does not change the built menu —
        // iOS's uncached deferred element re-reads only on the NEXT open.
        val recorder = RecordingActions(grouped = true)
        val built = sessionsOptionsMenu(recorder)
        assertTrue(rows(built)[2].checked)
        recorder.grouped = false
        assertTrue("the menu is a snapshot", rows(built)[2].checked)
        // …and the next build reads the new value.
        assertFalse(rows(sessionsOptionsMenu(recorder))[2].checked)
    }

    @Test
    fun eachRowRunsItsActionOnce() {
        val recorder = RecordingActions()
        val items = rows(sessionsOptionsMenu(recorder))
        items[0].action()
        assertEquals(listOf("newSection()"), recorder.calls)
        items[1].action()
        assertEquals(listOf("newSection()", "collapseAll()"), recorder.calls)
        items[3].action()
        assertEquals(listOf("newSection()", "collapseAll()", "openArchived()"), recorder.calls)
    }

    @Test
    fun theToggleReadsTheLiveValueAtPickTime() {
        // iOS `app.groupByProject.toggle()` flips the live value, not the
        // value the menu showed when it opened.
        val recorder = RecordingActions(grouped = true)
        val built = rows(sessionsOptionsMenu(recorder))
        assertTrue(built[2].checked)
        built[2].action()
        assertEquals(listOf("setGroupByProject(false)"), recorder.calls)
        // And the other way: built while off…
        val offRecorder = RecordingActions(grouped = false)
        rows(sessionsOptionsMenu(offRecorder))[2].action()
        assertEquals(listOf("setGroupByProject(true)"), offRecorder.calls)
    }

    // ------------------------------------------------------ header menus

    @Test
    fun recentAndProjectGroupsAndUnknownIdsHaveNoMenu() {
        val recorder = RecordingActions()
        assertNull("recent → null", sessionHeaderMenu("recent", recorder))
        assertNull("project group → null", sessionHeaderMenu("project:zeron", recorder))
        assertNull("home group → null", sessionHeaderMenu("home:dev1", recorder))
        assertNull("unknown id → null", sessionHeaderMenu("nope", recorder))
        // Nothing fired while classifying.
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun pinnedGetsOpenAndReorderBothOpeningThePinnedPage() {
        val recorder = RecordingActions()
        val menu = sessionHeaderMenu("pinned", recorder)!!
        val items = rows(menu)
        assertEquals(2, items.size)
        assertNull("no menu title", menu.title)

        assertEquals("Open", items[0].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.ArrowUpRight), items[0].icon)
        assertEquals("section-menu-open", items[0].testTag)

        assertEquals("Reorder\u2026", items[1].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.ArrowUpArrowDown), items[1].icon)
        assertEquals("section-menu-reorder", items[1].testTag)

        // "Reorder…" does exactly what "Open" does (no auto-Edit, 47 owns
        // the mode on the page itself).
        items[0].action()
        items[1].action()
        assertEquals(listOf("openPinned()", "openPinned()"), recorder.calls)
    }

    @Test
    fun aUserSectionGetsOpenRenameAndDelete() {
        val recorder = RecordingActions()
        val menu = sessionHeaderMenu("sec-p0", recorder)!!
        val items = rows(menu)
        assertEquals(3, items.size)

        assertEquals("Open", items[0].title)
        assertEquals("section-menu-open", items[0].testTag)

        assertEquals("Rename Section\u2026", items[1].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Pencil), items[1].icon)
        assertEquals("section-menu-rename", items[1].testTag)

        assertEquals("Delete Section", items[2].title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Trash), items[2].icon)
        assertTrue("delete is the only destructive row", items[2].destructive)
        assertFalse(items[0].destructive)
        assertFalse(items[1].destructive)
        assertEquals("section-menu-delete", items[2].testTag)

        items[0].action()
        items[1].action()
        items[2].action()
        assertEquals(
            listOf("openSection(sec-p0, P0)", "renameSection(sec-p0, P0)", "deleteSection(sec-p0)"),
            recorder.calls,
        )
    }

    @Test
    fun renamePrefillsTheRawNameNeverTheSuffixedHeaderTitle() {
        val recorder = RecordingActions()
        // The header TITLE carries 40's collision suffix; the menu is built
        // from the id and the raw SectionView name.
        recorder.sectionNames["sec-p0"] = "P0"
        val menu = sessionHeaderMenu("sec-p0", recorder)!!
        rows(menu)[1].action()
        assertEquals(listOf("renameSection(sec-p0, P0)"), recorder.calls)
    }

    @Test
    fun aSectionThatVanishedFromTheWorkspaceHasNoMenu() {
        val recorder = RecordingActions()
        recorder.sectionNames.clear()
        assertNull(sessionHeaderMenu("sec-p0", recorder))
    }
}
