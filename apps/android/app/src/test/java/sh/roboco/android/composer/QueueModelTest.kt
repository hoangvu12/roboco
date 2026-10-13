package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.composerState
import uniffi.roboco_core.QueueGate

/**
 * [QueueModel] — android-ui-parity #82's pure model, pinned on the plain
 * JVM (no Robolectric runner): the gate labels
 * (`CoreSessionSource.swift:96-100`), the row / footer copy
 * (`QuestionPanel.swift:257-279`), the ⋯ row menu (`:292-298`) and
 * [QueueChromeFiller]'s mapping (`CoreSessionSource.swift:95-102`).
 *
 * The generated UniFFI records are plain Kotlin data classes whose
 * construction touches no JNA, and [composerState] is ticket 51's test
 * fixture helper.
 */
class QueueModelTest {

    // -------------------------------------------------------- gate labels

    @Test
    fun gateLabelsFollowCoreSessionSource() {
        assertEquals("Editing", queueGateLabel(QueueGate.Editing(ownerDeviceId = "dev", expiresAtMs = 0L, mine = true), actionPending = false))
        assertEquals("Being edited", queueGateLabel(QueueGate.Editing(ownerDeviceId = "other", expiresAtMs = 0L, mine = false), actionPending = false))
        assertEquals("Needs review", queueGateLabel(QueueGate.ReviewRequired(ownerDeviceId = "dev"), actionPending = false))
        assertEquals("Updating", queueGateLabel(gate = null, actionPending = true))
        assertNull(queueGateLabel(gate = null, actionPending = false))
    }

    @Test
    fun aGatedRowKeepsItsGateLabelEvenWhilePending() {
        // The `nil` arm is the only one that reads actionPending, so a
        // gated row that is also pending shows the gate, never "Updating".
        assertEquals(
            "Editing",
            queueGateLabel(QueueGate.Editing(ownerDeviceId = "dev", expiresAtMs = 0L, mine = true), actionPending = true),
        )
        assertEquals(
            "Needs review",
            queueGateLabel(QueueGate.ReviewRequired(ownerDeviceId = "dev"), actionPending = true),
        )
    }

    // ------------------------------------------------------ row / footer

    @Test
    fun rowTextJoinsGateWithAMiddleDot() {
        assertEquals(
            "Needs review · Fix it",
            queueRowText(SessionChrome.QueuedItem(id = "q1", text = "Fix it", gate = "Needs review")),
        )
        assertEquals(
            "Fix it",
            queueRowText(SessionChrome.QueuedItem(id = "q1", text = "Fix it", gate = null)),
        )
    }

    @Test
    fun rowTextUsesTheMiddleDotScalarWithSingleSpaces() {
        val text = queueRowText(SessionChrome.QueuedItem(id = "q1", text = "Fix it", gate = "Editing"))
        assertEquals("Editing · Fix it", text)
        assertTrue("the separator is U+00B7", text.contains('·'))
        assertFalse("no double space", text.contains("  "))
    }

    @Test
    fun footerTextOnlyAppearsPastThreeRows() {
        assertNull(queueFooterText(0))
        assertNull(queueFooterText(1))
        assertNull(queueFooterText(2))
        assertNull(queueFooterText(3))
        assertEquals("+1 more queued", queueFooterText(4))
        assertEquals("+2 more queued", queueFooterText(5))
        assertEquals("+7 more queued", queueFooterText(10))
    }

    // ------------------------------------------------------ the ⋯ menu

    /** The [MenuItem] rows of one [queueRowMenu], in order. */
    private fun rowsOf(index: Int, count: Int): List<MenuItem> =
        queueRowMenu(index, count) { }.children.filterIsInstance<MenuItem>()

    @Test
    fun rowMenuHasNoTitleAndFourRowsInOrder() {
        val menu = queueRowMenu(0, 1) { }
        assertNull(menu.title)
        assertEquals(
            listOf("Edit", "Move Up", "Move Down", "Remove"),
            rowsOf(0, 1).map { it.title },
        )
    }

    @Test
    fun rowMenuOnASingleRowDisablesBothMoves() {
        val rows = rowsOf(0, 1)
        assertFalse(rows[0].disabled)
        assertTrue("Move Up disabled on row 0", rows[1].disabled)
        assertTrue("Move Down disabled on the only row", rows[2].disabled)
        assertFalse(rows[3].disabled)
    }

    @Test
    fun rowMenuInTheMiddleOfFiveHasNothingDisabled() {
        val rows = rowsOf(2, 5)
        // index 2 of 5: not row 0, and count − 1 = 4 ≠ 2.
        assertFalse(rows.any { it.disabled })
    }

    @Test
    fun rowMenuOnTheLastVisibleRowDisablesOnlyMoveDown() {
        val rows = rowsOf(2, 3)
        assertFalse(rows[0].disabled)
        assertFalse(rows[1].disabled)
        assertTrue("Move Down disabled on the last item (count, not rows shown)", rows[2].disabled)
        assertFalse(rows[3].disabled)
    }

    @Test
    fun rowMenuIconsAndDestructiveRules() {
        val rows = rowsOf(1, 4)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Pencil), rows[0].icon)
        assertEquals(MenuIcon.Symbol(RobocoIcons.ArrowUp), rows[1].icon)
        assertEquals(MenuIcon.Symbol(RobocoIcons.ArrowDown), rows[2].icon)
        assertEquals(MenuIcon.Symbol(RobocoIcons.Trash), rows[3].icon)
        assertFalse(rows[0].destructive)
        assertFalse(rows[1].destructive)
        assertFalse(rows[2].destructive)
        assertTrue("Remove is destructive", rows[3].destructive)
    }

    @Test
    fun rowMenuTestTags() {
        val menu = queueRowMenu(0, 2) { }
        assertEquals(
            listOf("queue-menu-edit", "queue-menu-move-up", "queue-menu-move-down", "queue-menu-remove"),
            menu.children.filterIsInstance<MenuItem>().map { it.testTag },
        )
    }

    @Test
    fun rowMenuActionsFireThroughTheCallback() {
        val fired = mutableListOf<QueueAction>()
        queueRowMenu(1, 3) { fired += it }.children.filterIsInstance<MenuItem>().forEach { it.action() }
        assertEquals(
            listOf(QueueAction.Edit, QueueAction.MoveUp, QueueAction.MoveDown, QueueAction.Remove),
            fired,
        )
    }

    // ------------------------------------------------------ the filler

    /** A [uniffi.roboco_core.QueueItem] with only the mapped fields apart. */
    private fun queueItem(
        id: String,
        text: String,
        visibleText: String = text,
        gate: QueueGate? = null,
        actionPending: Boolean = false,
    ) = uniffi.roboco_core.QueueItem(
        id = id,
        text = text,
        visibleText = visibleText,
        attachments = emptyList(),
        holdForTurnEnd = true,
        issuedBy = "device",
        fromThisDevice = true,
        issuedAtMs = 0L,
        editedAtMs = null,
        gate = gate,
        actionPending = actionPending,
    )

    @Test
    fun fillerMapsGatesTextAndOrder() {
        val composer = composerState().copy(
            queue = listOf(
                queueItem("q1", "raw one [appshot]", visibleText = "one"),
                queueItem(
                    "q2",
                    "raw two",
                    gate = QueueGate.Editing(ownerDeviceId = "dev", expiresAtMs = 0L, mine = true),
                    actionPending = true,
                ),
                queueItem("q3", "raw three", gate = QueueGate.Editing(ownerDeviceId = "other", expiresAtMs = 0L, mine = false)),
                queueItem("q4", "raw four", gate = QueueGate.ReviewRequired(ownerDeviceId = "dev")),
                queueItem("q5", "raw five", actionPending = true),
            ),
        )
        val input = ChromeInput(chatId = "chat-a", composer = composer, row = null, engine = null)

        val chrome = QueueChromeFiller().fill(SessionChrome(), input)

        assertEquals(
            listOf(
                SessionChrome.QueuedItem(id = "q1", text = "one", gate = null),
                SessionChrome.QueuedItem(id = "q2", text = "raw two", gate = "Editing"),
                SessionChrome.QueuedItem(id = "q3", text = "raw three", gate = "Being edited"),
                SessionChrome.QueuedItem(id = "q4", text = "raw four", gate = "Needs review"),
                SessionChrome.QueuedItem(id = "q5", text = "raw five", gate = "Updating"),
            ),
            chrome.queue,
        )
    }

    @Test
    fun fillerUsesVisibleTextNeverTheRawText() {
        val input = ChromeInput(
            chatId = "chat-a",
            composer = composerState().copy(
                queue = listOf(queueItem("q1", text = "raw with trailer", visibleText = "clean")),
            ),
            row = null,
            engine = null,
        )
        assertEquals("clean", QueueChromeFiller().fill(SessionChrome(), input).queue.single().text)
    }

    @Test
    fun fillerAlwaysLeavesTheThumbnailNull() {
        val input = ChromeInput(
            chatId = "chat-a",
            composer = composerState().copy(queue = listOf(queueItem("q1", "one"))),
            row = null,
            engine = null,
        )
        assertNull(QueueChromeFiller().fill(SessionChrome(), input).queue.single().thumbnailRef)
    }

    @Test
    fun fillerLeavesAnEmptyQueueEmptyAndKeepsTheOtherChromeFields() {
        val input = ChromeInput(
            chatId = "chat-a",
            composer = composerState().copy(),
            row = null,
            engine = null,
        )
        val base = SessionChrome(title = "T", subtitle = "P @ E", running = true)
        val chrome = QueueChromeFiller().fill(base, input)
        assertTrue(chrome.queue.isEmpty())
        assertEquals("T", chrome.title)
        assertEquals("P @ E", chrome.subtitle)
        assertTrue(chrome.running)
    }
}
