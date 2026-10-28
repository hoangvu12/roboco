package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wave-10 41 (upstream #909's iPhone drag): the destinations, the drop
 * slot's neighbours and the write each drop makes — iOS's
 * `dropTarget`, `dropSlot` and `performDropWith` — on the plain JVM.
 */
class SessionDropTest {

    @Test
    fun theDestinationsArePinnedRecentAndUserSectionsOnly() {
        val users = setOf("sec-a")
        assertEquals(SessionDropTarget.Pinned, dropTargetOf(FrontPageSections.PINNED, users, searching = false))
        assertEquals(SessionDropTarget.Recent, dropTargetOf(FrontPageSections.RECENT, users, searching = false))
        assertEquals(SessionDropTarget.Section("sec-a"), dropTargetOf("sec-a", users, searching = false))
        assertNull("a project group is no place", dropTargetOf("project:x", users, searching = false))
        assertNull("search results are no place", dropTargetOf(FrontPageSections.PINNED, users, searching = true))
    }

    @Test
    fun theSlotsNeighboursSkipTheDraggedRow() {
        val rows = listOf("a", "b", "c")
        assertEquals(SessionDropSlot(SessionDropTarget.Pinned, 0, null, "a"), sessionDropSlot(SessionDropTarget.Pinned, rows, "x", 0))
        assertEquals(SessionDropSlot(SessionDropTarget.Pinned, 1, "a", "c"), sessionDropSlot(SessionDropTarget.Pinned, rows, "b", 1))
        assertEquals(SessionDropSlot(SessionDropTarget.Pinned, 3, "c", null), sessionDropSlot(SessionDropTarget.Pinned, rows, "x", 9))
        assertEquals(SessionDropSlot(SessionDropTarget.Recent, 0, null, null), sessionDropSlot(SessionDropTarget.Recent, emptyList(), "x", 2))
    }

    @Test
    fun eachDropMakesUpstreamsWrite() {
        val pinned = SessionDropSlot(SessionDropTarget.Pinned, 1, "a", "b")
        assertEquals(SessionDropWrite.MovePin("a", "b"), sessionDropWrite(SessionDropTarget.Pinned, pinned))
        assertEquals(SessionDropWrite.PinAt("a", "b"), sessionDropWrite(SessionDropTarget.Recent, pinned))
        val section = SessionDropSlot(SessionDropTarget.Section("s"), 0, null, "m")
        assertEquals(SessionDropWrite.Place("s", null, "m"), sessionDropWrite(SessionDropTarget.Pinned, section))
        val recent = SessionDropSlot(SessionDropTarget.Recent, 0, null, null)
        assertEquals(SessionDropWrite.Unpin, sessionDropWrite(SessionDropTarget.Pinned, recent))
        assertEquals(SessionDropWrite.LeaveSection, sessionDropWrite(SessionDropTarget.Section("s"), recent))
        assertEquals("Recent sorts by activity", SessionDropWrite.NoMove, sessionDropWrite(SessionDropTarget.Recent, recent))
    }
}
