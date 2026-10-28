package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.R
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.SectionView

/**
 * [sessionRowMenu] and the [SessionRowCommands] adapter (android-ui-parity
 * #44) — plain JVM, no Robolectric: the menu is pure data over the
 * recording fakes, the FFI records are plain Kotlin data classes, and
 * `R.string` fields are compile-time ints (the ticket 06 pattern).
 */
class SessionRowMenuTest {

    // ------------------------------------------------------------ the fakes

    /** Records every call as one line. */
    private class RecordingCommands : SessionRowCommands {
        val calls = mutableListOf<String>()

        override fun setPinned(chatId: String, pinned: Boolean) {
            calls += "setPinned $chatId $pinned"
        }

        override fun archive(chatId: String, onAccepted: (Boolean) -> Unit) {
            calls += "archive $chatId"
        }

        override fun unarchive(chatId: String) {
            calls += "unarchive $chatId"
        }

        override fun move(chatId: String, sectionId: String?) {
            calls += "move $chatId ${sectionId ?: "-"}"
        }

        override fun moveToSectionSheet(row: SessionRow) {
            calls += "sheet ${row.id}"
        }

        override fun renameSession(row: SessionRow) {
            calls += "rename ${row.id}"
        }

        override fun deleteSession(row: SessionRow) {
            calls += "delete ${row.id}"
        }

        override fun newSection(engineId: String?) {
            calls += "newSection ${engineId ?: "-"}"
        }

        override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
    }

    /** 42's surface: one scripted outcome per archive, else Ok. */
    private class FakeRowActions : RowActions {
        val calls = mutableListOf<String>()
        var archiveOutcome: ActionOutcome = ActionOutcome.Ok
        val sections = mutableListOf<SectionView>()

        override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
            calls += "setPinned $chatId $pinned"
            onResult(ActionOutcome.Ok)
        }

        override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "archive $chatId"
            onResult(archiveOutcome)
        }

        override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "unarchive $chatId"
            onResult(ActionOutcome.Ok)
        }
        override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "delete $chatId"
            onResult(ActionOutcome.Ok)
        }

        override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
            calls += "move $chatId ${toSection ?: "-"}"
            onResult(ActionOutcome.Ok)
        }

        override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
            onResult(ActionOutcome.Ok)
        }

        override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
            calls += "rename $chatId $title"
            onResult(ActionOutcome.Ok)
        }

        override fun createSection(
            name: String,
            engineId: String?,
            onResult: (ActionOutcome) -> Unit,
            onCreated: (sectionId: String) -> Unit,
        ) {
            onResult(ActionOutcome.Ok)
        }

        override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {
            onResult(ActionOutcome.Ok)
        }

        override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {
            onResult(ActionOutcome.Ok)
        }

        override fun sectionsFor(engineId: String): List<SectionView> = sections

        override fun report(error: Throwable) {}
    }

    /** 43's surface. */
    private class FakePrompts : SessionPrompts {
        val calls = mutableListOf<String>()

        override fun renameSession(row: SessionRow) {
            calls += "renameSession ${row.id}"
        }

        override fun newSection(engineId: String?) {
            calls += "newSection ${engineId ?: "-"}"
        }

        override fun renameSection(sectionId: String, currentName: String, onRenamed: ((String) -> Unit)?) {}

        override fun moveToSection(row: SessionRow) {
            calls += "moveToSection ${row.id}"
        }

        override fun confirmDeleteSection(sectionId: String, name: String, onDeleted: () -> Unit) {}

        override fun confirmDeleteSession(chatId: String, title: String, onDeleted: () -> Unit) {
            calls += "confirmDeleteSession $chatId $title"
        }
    }

    // ---------------------------------------------------------- the fixture

    private val strings: (Int) -> String = { id ->
        when (id) {
            R.string.swipe_pin -> "Pin"
            R.string.swipe_unpin -> "Unpin"
            R.string.swipe_archive -> "Archive"
            R.string.swipe_move -> "Move"
            R.string.swipe_unarchive -> "Unarchive"
            R.string.menu_move_to_section -> "Move to Section"
            R.string.menu_no_section -> "No Section"
            R.string.menu_new_section -> "New Section…"
            R.string.menu_rename -> "Rename…"
            R.string.menu_delete -> "Delete"
            else -> "string $id"
        }
    }

    private fun row(
        id: String,
        title: String = "Rename me",
        pinned: Boolean = false,
        engineId: String = "eng-A",
    ): SessionRow = SessionRow(
        id = id,
        engineId = engineId,
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = null,
        deviceId = "device-A",
        deviceName = null,
        deviceOnline = true,
        engineOnline = true,
        harness = null,
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = null,
        cwd = null,
        indicator = ChatIndicator.IDLE,
        hostIndicator = ChatIndicator.IDLE,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = "now",
        createdAtMs = 0L,
        unseen = false,
        archived = false,
        pinned = pinned,
        sectionId = null,
        pullRequest = null,
        sendState = null,
        parentChatId = null,
    )

    private fun vm(r: SessionRow): SessionRowVM = SessionRowVM.from(r, homeColorIndex = 6)

    private fun section(id: String, name: String, engineId: String = "eng-A"): SectionView =
        SectionView(id = id, engineId = engineId, name = name, collapsed = false, sessions = emptyList())

    private val sections = listOf(section("s1", "Work"), section("s2", ""))

    private fun menu(r: SessionRow, cmd: RecordingCommands, archived: Boolean = false, list: List<SectionView> = sections) =
        sessionRowMenu(r, vm(r), archived, list, cmd, strings)

    // ------------------------------------------------------------ the menu

    @Test
    fun theNormalMenuIsPinMoveRenameArchiveDelete() {
        val menu = menu(row("c1"), RecordingCommands())
        assertNull(menu.title)
        val children = menu.children
        assertEquals(5, children.size)

        val pin = children[0] as MenuItem
        assertEquals("Pin", pin.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Pin), pin.icon)
        assertFalse(pin.destructive)
        assertEquals("row-menu-pin", pin.testTag)

        val move = children[1] as MenuSection
        assertEquals("Move to Section", move.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Folder), move.icon)
        assertFalse(move.inline)

        val rename = children[2] as MenuItem
        assertEquals("Rename…", rename.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Pencil), rename.icon)
        assertFalse(rename.destructive)
        assertEquals("row-menu-rename", rename.testTag)

        val archive = children[3] as MenuItem
        assertEquals("Archive", archive.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Archivebox), archive.icon)
        assertFalse("upstream #967: Archive is no longer destructive", archive.destructive)
        assertEquals("row-menu-archive", archive.testTag)

        val delete = children[4] as MenuItem
        assertEquals("Delete", delete.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Trash), delete.icon)
        assertTrue(delete.destructive)
        assertEquals("row-menu-delete", delete.testTag)
    }

    @Test
    fun aPinnedRowSaysUnpinWithTheSlashIcon() {
        val menu = menu(row("c1", pinned = true), RecordingCommands())
        val pin = menu.children[0] as MenuItem
        assertEquals("Unpin", pin.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.PinSlash), pin.icon)
        assertEquals("row-menu-unpin", pin.testTag)
    }

    @Test
    fun theMoveSubmenuListsTheSectionsInOrderThenNoSectionAndNewSection() {
        val menu = menu(row("c1"), RecordingCommands())
        val move = menu.children[1] as MenuSection
        // Exactly the `sections` argument, in order — empty sections
        // included, the current one not filtered out, no checkmarks.
        assertEquals(4, move.children.size)
        val s1 = move.children[0] as MenuItem
        assertEquals("Work", s1.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Folder), s1.icon)
        assertEquals("row-menu-section-s1", s1.testTag)
        assertFalse(s1.checked)
        val s2 = move.children[1] as MenuItem
        assertEquals("", s2.title)
        assertEquals("row-menu-section-s2", s2.testTag)
        val none = move.children[2] as MenuItem
        assertEquals("No Section", none.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Tray), none.icon)
        assertEquals("row-menu-no-section", none.testTag)
        val newSection = move.children[3] as MenuItem
        assertEquals("New Section…", newSection.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.FolderBadgePlus), newSection.icon)
        assertEquals("row-menu-new-section", newSection.testTag)
    }

    @Test
    fun theArchivedMenuIsUnarchiveRenameAndDelete() {
        val menu = menu(row("c1"), RecordingCommands(), archived = true)
        assertEquals(3, menu.children.size)
        val unarchive = menu.children[0] as MenuItem
        assertEquals("Unarchive", unarchive.title)
        assertEquals(MenuIcon.Symbol(RobocoIcons.TrayAndArrowUp), unarchive.icon)
        assertEquals("row-menu-unarchive", unarchive.testTag)
        assertFalse(unarchive.destructive)
        val rename = menu.children[1] as MenuItem
        assertEquals("Rename…", rename.title)
        assertEquals("row-menu-rename", rename.testTag)
        val delete = menu.children[2] as MenuItem
        assertEquals("row-menu-delete", delete.testTag)
        assertTrue(delete.destructive)
    }

    @Test
    fun everyItemRunsItsOwnCommandExactlyOnce() {
        val cmd = RecordingCommands()
        val menu = menu(row("c9"), cmd)
        (menu.children[0] as MenuItem).action() // Pin
        val move = menu.children[1] as MenuSection
        (move.children[0] as MenuItem).action() // to s1
        (move.children[1] as MenuItem).action() // to s2
        (move.children[2] as MenuItem).action() // No Section
        (menu.children[2] as MenuItem).action() // Rename…
        (menu.children[3] as MenuItem).action() // Archive
        (menu.children[4] as MenuItem).action() // Delete (the confirm)
        assertEquals(
            listOf(
                "setPinned c9 true",
                "move c9 s1",
                "move c9 s2",
                "move c9 -",
                "rename c9",
                "archive c9",
                "delete c9",
            ),
            cmd.calls,
        )
    }

    @Test
    fun pinnedItemsUnpinAndArchivedItemsUnarchive() {
        val cmd = RecordingCommands()
        (menu(row("c1", pinned = true), cmd).children[0] as MenuItem).action()
        (menu(row("c2"), cmd, archived = true).children[0] as MenuItem).action()
        assertEquals(listOf("setPinned c1 false", "unarchive c2"), cmd.calls)
    }

    @Test
    fun newSectionCreatesOnTheRowsEngineAndNeverMovesTheChat() {
        val cmd = RecordingCommands()
        val r = row("c1", engineId = "eng-B")
        val menu = sessionRowMenu(r, vm(r), false, cmd.sectionsFor("eng-B"), cmd, strings)
        val newSection = (menu.children[1] as MenuSection).children.last() as MenuItem
        newSection.action()
        assertEquals(listOf("newSection eng-B"), cmd.calls)
    }

    // ---------------------------------------------------------- the adapter

    @Test
    fun theAdapterAcceptsOkAndGoneArchives() {
        for (outcome in listOf(ActionOutcome.Ok, ActionOutcome.Gone)) {
            val actions = FakeRowActions().apply { archiveOutcome = outcome }
            val accepted = mutableListOf<Boolean>()
            SessionRowCommands(actions, FakePrompts()).archive("c1") { accepted += it }
            assertEquals(listOf(true), accepted)
        }
    }

    @Test
    fun theAdapterRefusesEveryOtherArchiveOutcome() {
        val refused = listOf(
            ActionOutcome.EngineOffline,
            ActionOutcome.PinsNotSynced,
            ActionOutcome.NoClient,
            ActionOutcome.Failed,
            ActionOutcome.Skipped,
        )
        for (outcome in refused) {
            val actions = FakeRowActions().apply { archiveOutcome = outcome }
            val accepted = mutableListOf<Boolean>()
            SessionRowCommands(actions, FakePrompts()).archive("c1") { accepted += it }
            assertEquals(listOf(false), accepted)
        }
    }

    @Test
    fun theAdapterDelegatesEveryWrite() {
        val actions = FakeRowActions()
        val prompts = FakePrompts()
        actions.sections += section("s1", "Work")
        val cmd = SessionRowCommands(actions, prompts)
        val r = row("c1", engineId = "eng-A")

        cmd.setPinned("c1", true)
        cmd.unarchive("c1")
        cmd.move("c1", "s1")
        cmd.move("c1", null)
        cmd.moveToSectionSheet(r)
        cmd.renameSession(r)
        cmd.newSection("eng-A")
        cmd.deleteSession(r)
        cmd.archive("c1") {}

        assertEquals(
            listOf(
                "setPinned c1 true",
                "unarchive c1",
                "move c1 s1",
                "move c1 -",
                "archive c1",
            ),
            actions.calls,
        )
        assertEquals(
            listOf(
                "moveToSection c1",
                "renameSession c1",
                "newSection eng-A",
                "confirmDeleteSession c1 Rename me",
            ),
            prompts.calls,
        )
        assertEquals(listOf(section("s1", "Work")), cmd.sectionsFor("eng-A"))
    }

    /** Wave-10 33: a confirmed row delete tells the router (the tablet leaves the open session). */
    @Test
    fun aConfirmedDeleteReachesTheRouterHook() {
        val deleted = mutableListOf<String>()
        val prompts = object : SessionPrompts by FakePrompts() {
            override fun confirmDeleteSession(chatId: String, title: String, onDeleted: () -> Unit) = onDeleted()
        }
        SessionRowCommands(FakeRowActions(), prompts) { deleted += it }.deleteSession(row("c1"))
        assertEquals(listOf("c1"), deleted)
    }
}
