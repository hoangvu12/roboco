package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.MenuItem

/**
 * The row-menu item builder (android-ui-parity #128), plain JVM —
 * [rowMenuActions] is pure and [toMenu] builds pure data, so no
 * Robolectric: nothing here touches Android types beyond `R.drawable`
 * ints (the [sh.roboco.android.design.RobocoIcons] entries).
 *
 * Every row of the ticket's table, including the Swift `??` quirk:
 * `("a", "")` yields **Copy only** — an empty `message` hides Select Text
 * even when the block has text, because `message ?? block` only falls
 * back on a **null** message (`ios:Transcript/TranscriptListView.swift:612-628`).
 */
class RowMenuActionsTest {

    // ------------------------------------------------------ the item table

    @Test
    fun aRowWhoseMessageEqualsItsBlockShowsCopyAndSelectText() {
        assertEquals(
            listOf(RowMenuAction(RowMenuKind.Copy, "a"), RowMenuAction(RowMenuKind.SelectText, "a")),
            rowMenuActions("a", "a"),
        )
    }

    @Test
    fun aBlockOfAMultiBlockReplyAlsoShowsCopyMessageWithTheJoinedMessage() {
        assertEquals(
            listOf(
                RowMenuAction(RowMenuKind.Copy, "a"),
                RowMenuAction(RowMenuKind.CopyMessage, "a\n\nb"),
                RowMenuAction(RowMenuKind.SelectText, "a\n\nb"),
            ),
            rowMenuActions("a", "a\n\nb"),
        )
    }

    @Test
    fun anEmptyBlockInAMessageWithTextShowsCopyMessageAndSelectText() {
        assertEquals(
            listOf(RowMenuAction(RowMenuKind.CopyMessage, "x"), RowMenuAction(RowMenuKind.SelectText, "x")),
            rowMenuActions("", "x"),
        )
    }

    @Test
    fun anEmptyBlockAndAnEmptyMessageShowNothing() {
        assertEquals(emptyList<RowMenuAction>(), rowMenuActions("", ""))
    }

    @Test
    fun aNullMessageFallsBackToTheBlock() {
        assertEquals(
            listOf(RowMenuAction(RowMenuKind.Copy, "a"), RowMenuAction(RowMenuKind.SelectText, "a")),
            rowMenuActions("a", null),
        )
    }

    @Test
    fun aNullMessageWithAnEmptyBlockShowsNothing() {
        assertEquals(emptyList<RowMenuAction>(), rowMenuActions("", null))
    }

    @Test
    fun anEmptyMessageHidesSelectTextEvenWhenTheBlockHasText() {
        // The `??` quirk: `message ?: block` is "" here (message is
        // present but empty), so Select Text is hidden.
        assertEquals(
            listOf(RowMenuAction(RowMenuKind.Copy, "a")),
            rowMenuActions("a", ""),
        )
    }

    // ------------------------------------------------- titles, icons, tags

    @Test
    fun theKindsUseTheIosTitlesSymbolsAndTestTags() {
        assertEquals("Copy", RowMenuKind.Copy.title)
        assertEquals("Copy Message", RowMenuKind.CopyMessage.title)
        assertEquals("Select Text", RowMenuKind.SelectText.title)
        assertSame(RobocoIcons.DocOnDoc, RowMenuKind.Copy.icon)
        assertSame(RobocoIcons.DocOnClipboard, RowMenuKind.CopyMessage.icon)
        assertSame(RobocoIcons.CharacterCursorIbeam, RowMenuKind.SelectText.icon)
        assertEquals("row-menu-copy", RowMenuKind.Copy.testTag)
        assertEquals("row-menu-copy-message", RowMenuKind.CopyMessage.testTag)
        assertEquals("row-menu-select-text", RowMenuKind.SelectText.testTag)
    }

    // -------------------------------------------------------- menu building

    @Test
    fun anEmptyActionListBuildsNoMenu() {
        assertNull(emptyList<RowMenuAction>().toMenu {})
    }

    @Test
    fun aBuiltMenuCarriesPlainRowsInOrderWithTitlesSymbolsAndTags() {
        val actions = rowMenuActions("a", "a\n\nb")
        val menu = actions.toMenu {}!!
        assertNull("UIMenu(children:) — no menu title", menu.title)
        assertEquals(actions.size, menu.children.size)
        val items = menu.children.map { it as MenuItem }
        // Order: Copy, Copy Message, Select Text — fixed.
        assertEquals("Copy", items[0].title)
        assertEquals("Copy Message", items[1].title)
        assertEquals("Select Text", items[2].title)
        assertEquals("row-menu-copy", items[0].testTag)
        assertEquals("row-menu-copy-message", items[1].testTag)
        assertEquals("row-menu-select-text", items[2].testTag)
        // No subtitle, no checkmark, not destructive, not disabled.
        items.forEach { item ->
            assertNull(item.subtitle)
            assertFalse(item.checked)
            assertFalse(item.destructive)
            assertFalse(item.disabled)
        }
    }

    @Test
    fun theMenuRowsRunTheirOwnAction() {
        val run = mutableListOf<String>()
        val menu = rowMenuActions("a", "a\n\nb").toMenu { run += it.text }!!
        menu.children.map { it as MenuItem }.forEach { it.action() }
        assertEquals(listOf("a", "a\n\nb", "a\n\nb"), run)
    }
}
