package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MovePinNeighbours] (android-ui-parity #47) — every case of the
 * ticket's table: the engine-block walk, the drop-target clamp, the
 * `movePin` neighbour rule (`after` / `before` from the final order,
 * cross-engine neighbours `null`) and the accessibility step rule.
 *
 * Plain JUnit 4, no Robolectric: pure functions over [PinKey] lists.
 */
class MovePinNeighboursTest {

    /** `PinKey(id, engine)` — `A` and `B` are engine ids, like the ticket. */
    private fun k(id: String, engine: String) = PinKey(id, engine)

    private fun ids(order: List<PinKey>): List<String> = order.map { it.id }

    // ------------------------------------------------------------- drop

    @Test
    fun `move a to index 2 in one engine gives after=c before=d`() {
        val original = listOf(k("a", "A"), k("b", "A"), k("c", "A"), k("d", "A"))
        val final = listOf(k("b", "A"), k("c", "A"), k("a", "A"), k("d", "A"))
        assertEquals(PinMove("a", after = "c", before = "d"), MovePinNeighbours.drop(original, final, "a"))
    }

    @Test
    fun `move c to the top gives after=null before=a`() {
        val original = listOf(k("a", "A"), k("b", "A"), k("c", "A"))
        val final = listOf(k("c", "A"), k("a", "A"), k("b", "A"))
        assertEquals(PinMove("c", after = null, before = "a"), MovePinNeighbours.drop(original, final, "c"))
    }

    @Test
    fun `move a to the bottom gives after=c before=null`() {
        val original = listOf(k("a", "A"), k("b", "A"), k("c", "A"))
        val final = listOf(k("b", "A"), k("c", "A"), k("a", "A"))
        assertEquals(PinMove("a", after = "c", before = null), MovePinNeighbours.drop(original, final, "a"))
    }

    @Test
    fun `unchanged order gives null`() {
        val original = listOf(k("a", "A"), k("b", "A"), k("c", "A"))
        assertNull(MovePinNeighbours.drop(original, original.toList(), "a"))
    }

    @Test
    fun `a vanished id gives null`() {
        val original = listOf(k("a", "A"), k("b", "A"))
        val final = listOf(k("b", "A"), k("c", "A"))
        assertNull(MovePinNeighbours.drop(original, final, "a"))
    }

    @Test
    fun `a cross-engine neighbour is null both sides`() {
        // [a1A,a2A,b1B,b2B], a1 moved to index 1: b1 is now below it but
        // belongs to B, so `before` is null — never b1's id.
        val original = listOf(k("a1", "A"), k("a2", "A"), k("b1", "B"), k("b2", "B"))
        val final = listOf(k("a2", "A"), k("a1", "A"), k("b1", "B"), k("b2", "B"))
        assertEquals(PinMove("a1", after = "a2", before = null), MovePinNeighbours.drop(original, final, "a1"))
    }

    @Test
    fun `b2 to the top of its block gives after=null before=b1`() {
        val original = listOf(k("a1", "A"), k("b1", "B"), k("b2", "B"))
        val final = listOf(k("a1", "A"), k("b2", "B"), k("b1", "B"))
        assertEquals(PinMove("b2", after = null, before = "b1"), MovePinNeighbours.drop(original, final, "b2"))
    }

    // ------------------------------------------------------------- clamp

    @Test
    fun `clampTarget stays inside the dragged row engine block`() {
        val order = listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"), k("b1", "B"), k("b2", "B"))
        assertEquals(2, MovePinNeighbours.clampTarget(order, from = 2, to = 4))
        assertEquals(3, MovePinNeighbours.clampTarget(order, from = 3, to = 0))
        assertEquals(2, MovePinNeighbours.clampTarget(order, from = 0, to = 2))
    }

    @Test
    fun `a single-row block clamps to itself`() {
        val order = listOf(k("a1", "A"), k("b1", "B"))
        assertEquals(0..0, MovePinNeighbours.engineBlock(order, 0))
        assertEquals(1..1, MovePinNeighbours.engineBlock(order, 1))
        assertEquals(0, MovePinNeighbours.clampTarget(order, from = 0, to = 1))
        assertEquals(1, MovePinNeighbours.clampTarget(order, from = 1, to = 0))
    }

    @Test
    fun `engineBlock spans the whole contiguous run`() {
        val order = listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"), k("b1", "B"), k("b2", "B"))
        assertEquals(0..2, MovePinNeighbours.engineBlock(order, 1))
        assertEquals(3..4, MovePinNeighbours.engineBlock(order, 4))
    }

    // ------------------------------------------------------------- step

    @Test
    fun `step up from the top of a block is null`() {
        val order = listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"))
        assertNull(MovePinNeighbours.step(order, 0, -1))
        // The block's top under another engine's tail: still no same-engine
        // neighbour above.
        val mixed = listOf(k("a1", "A"), k("b1", "B"))
        assertNull(MovePinNeighbours.step(mixed, 1, -1))
    }

    @Test
    fun `step down from a2 swaps with a3 and gives a3 before=null`() {
        val order = listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"))
        val step = MovePinNeighbours.step(order, 1, 1)
        assertNotNull(step)
        val (newOrder, move) = step!!
        assertEquals(listOf("a1", "a3", "a2"), ids(newOrder))
        assertEquals(PinMove("a2", after = "a3", before = null), move)
    }

    @Test
    fun `step up from a2 swaps with a1 and gives after=null`() {
        val order = listOf(k("a1", "A"), k("a2", "A"), k("a3", "A"))
        val step = MovePinNeighbours.step(order, 1, -1)
        assertNotNull(step)
        val (newOrder, move) = step!!
        assertEquals(listOf("a2", "a1", "a3"), ids(newOrder))
        assertEquals(PinMove("a2", after = null, before = "a1"), move)
    }

    @Test
    fun `step past the list end is null`() {
        val order = listOf(k("a1", "A"), k("a2", "A"))
        assertNull(MovePinNeighbours.step(order, 1, 1))
        assertNull(MovePinNeighbours.step(order, 0, -1))
    }

    @Test
    fun `a step over an id missing from the snapshot is the same rule`() {
        // The visible list may skip ids the raw stored list still has
        // (a pinned-and-sectioned chat); step only ever sees the visible
        // rows, like the drag path.
        val order = listOf(k("a1", "A"), k("a3", "A"))
        val step = MovePinNeighbours.step(order, 1, -1)
        assertNotNull(step)
        assertEquals(PinMove("a3", after = null, before = "a1"), step!!.second)
    }
}
