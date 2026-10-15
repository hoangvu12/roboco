package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinReorderState] (android-ui-parity #47) — the Idle / Dragging /
 * Committing table: adopts replace or stash, moves resolve by key (never
 * by item index) and clamp, `end` returns the drop and adopts the stash
 * when nothing moved, `committed` adopts or snaps back, `applyStep`
 * commits an accessibility step. Plain JUnit 4, no Robolectric — the
 * snapshot state is pure Kotlin.
 */
class PinReorderStateTest {

    private fun k(id: String, engine: String) = PinKey(id, engine)

    private fun ids(state: PinReorderState): List<String> = state.order.map { it.id }

    private val twoEngines = listOf(
        k("a1", "A"), k("a2", "A"), k("a3", "A"), k("b1", "B"), k("b2", "B"),
    )

    @Test
    fun `adopt while idle replaces the order`() {
        val state = PinReorderState(listOf(k("a1", "A")))
        state.adopt(listOf(k("a1", "A"), k("a2", "A")))
        assertEquals(listOf("a1", "a2"), ids(state))
    }

    @Test
    fun `adopt of an equal list is ignored`() {
        val state = PinReorderState(twoEngines)
        val order = state.order
        state.adopt(twoEngines.toList())
        assertSame(order, state.order) // no clear/addAll churn
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `adopt while dragging is stashed, not applied`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        state.adopt(listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"), k("b1", "B")))
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
        // The stash surfaces when the drag ends without a move.
        assertNull(state.end())
        assertEquals(listOf("a1", "a2", "a3", "b1"), ids(state))
    }

    @Test
    fun `move ignores keys outside the list and clamps into the engine block`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        // 41's session keys are "s:<id>"; anything else (a spacer, a
        // header, the scaffold's large-title item) resolves to nothing.
        assertFalse(state.move("s:spacer", "s:a1"))
        assertFalse(state.move("s:a1", "s:__large_title"))
        // Dragging a1 onto b2 clamps to the end of A's block.
        assertTrue(state.move("s:a1", "s:b2"))
        assertEquals(listOf("a2", "a3", "a1", "b1", "b2"), ids(state))
    }

    @Test
    fun `move inside another engine block is refused`() {
        val state = PinReorderState(twoEngines)
        state.start("a3")
        // a3 is already the last row of A's block: every target clamps to
        // itself, so the row can never cross into B's block.
        assertFalse(state.move("s:a3", "s:b2"))
        assertFalse(state.move("s:a3", "s:b1"))
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `end returns the drop result for the new neighbours`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertEquals("A", state.dragEngine)
        val move = state.end()
        assertEquals(PinMove("a1", after = "a3", before = null), move)
        assertNull(state.draggedId)
        assertNull(state.dragEngine)
    }

    @Test
    fun `a null end adopts the stash when there was no move`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        state.adopt(listOf(k("b1", "B"), k("b2", "B"), k("a1", "A"), k("a2", "A"), k("a3", "A")))
        assertNull(state.end())
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), ids(state))
        assertFalse(state.committing)
    }

    @Test
    fun `after a non-null end the state is committing and adopts are stashed`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        assertTrue(state.committing)
        state.adopt(listOf(k("a2", "A"), k("a3", "A"), k("a1", "A"), k("b1", "B"), k("b2", "B")))
        assertEquals(listOf("a2", "a3", "a1", "b1", "b2"), ids(state)) // the echo is stashed
        // A second drag cannot start while committing (the handles are
        // disabled; `start` is pure defence).
        state.start("a2")
        assertNull(state.draggedId)
    }

    @Test
    fun `committed ok adopts the stash`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        // The echo of the drop plus a desktop-side swap of the B block —
        // the latest snapshot, stashed while the write was in flight.
        state.adopt(listOf(k("a2", "A"), k("a3", "A"), k("a1", "A"), k("b2", "B"), k("b1", "B")))
        state.committed(ok = true)
        assertFalse(state.committing)
        assertEquals(listOf("a2", "a3", "a1", "b2", "b1"), ids(state))
    }

    @Test
    fun `committed ok keeps the order when no snapshot arrived`() {
        val state = PinReorderState(twoEngines)
        state.start("a2")
        assertTrue(state.move("s:a2", "s:a1"))
        assertEquals(PinMove("a2", after = null, before = "a1"), state.end())
        state.committed(ok = true)
        assertEquals(listOf("a2", "a1", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `committed not ok restores the latest stash`() {
        val state = PinReorderState(twoEngines)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        state.adopt(listOf(k("b1", "B"), k("b2", "B"), k("a1", "A"), k("a2", "A"), k("a3", "A")))
        state.committed(ok = false)
        assertFalse(state.committing)
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), ids(state))
    }

    @Test
    fun `committed not ok without a stash snaps back to the last snapshot`() {
        val state = PinReorderState(twoEngines)
        // A first successful drop whose echo never arrives, then a failed
        // one: the snap-back target is the last adopted rows, not the
        // post-drop local order.
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        state.committed(ok = true)
        state.start("a2")
        assertTrue(state.move("s:a2", "s:a3"))
        assertNotNull(state.end())
        state.committed(ok = false)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `applyStep sets the order and enters committing`() {
        val state = PinReorderState(listOf(k("a1", "A"), k("a2", "A"), k("a3", "A")))
        val step = MovePinNeighbours.step(state.order.toList(), 1, 1)
        assertNotNull(step)
        val (newOrder, move) = step!!
        state.applyStep(newOrder)
        assertTrue(state.committing)
        assertEquals(listOf("a1", "a3", "a2"), ids(state))
        state.committed(ok = true)
        assertEquals(PinMove("a2", after = "a3", before = null), move)
        assertEquals(listOf("a1", "a3", "a2"), ids(state))
    }
}
