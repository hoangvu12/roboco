package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model cache's eviction policy (ticket 117; iOS `store(_:for:)`,
 * `ios:Transcript/TranscriptListView.swift:436-443`): 500 entries, the
 * 501st distinct store drops the 100 **oldest by insertion order**
 * leaving 401, lookups and re-stores never reorder. Plain JVM, `String`
 * keys.
 */
class InsertionOrderCacheTest {

    private fun full(): InsertionOrderCache<String, Int> =
        InsertionOrderCache<String, Int>().also { c -> (1..500).forEach { c.store("k$it", it) } }

    @Test
    fun fiveHundredDistinctStoresFillTheCache() {
        val c = full()
        assertEquals(500, c.size)
        assertEquals(500, c.keysInOrder().size)
        assertNotNull(c["k1"])
        assertNotNull(c["k500"])
    }

    @Test
    fun the501stDistinctStoreDropsThe100OldestLeaving401() {
        val c = full()
        c.store("k501", 501)
        assertEquals(401, c.size)
        (1..100).forEach { assertNull("k$it should be evicted", c["k$it"]) }
        assertEquals(101, c["k101"])
        assertNotNull(c["k501"])
        assertEquals("k101", c.keysInOrder().first())
    }

    @Test
    fun aLookupDoesNotSaveAKeyFromEviction() {
        val c = full()
        assertEquals(1, c["k1"]) // a read before the overflow
        c.store("k501", 501)
        assertNull("k1 was saved by the lookup (access order?)", c["k1"])
        assertEquals(401, c.size)
    }

    @Test
    fun reStoringAKeyReplacesTheValueWithoutMovingIt() {
        val c = InsertionOrderCache<String, Int>()
        (1..10).forEach { c.store("k$it", it) }
        val order = c.keysInOrder()
        c.store("k5", 55)
        assertEquals(55, c["k5"])
        assertEquals("re-storing moved the key", order, c.keysInOrder())
        assertEquals(10, c.size)
    }

    @Test
    fun clearEmptiesEverythingAndTheNextFiveHundredStoresEvictNothing() {
        val c = full()
        c.clear()
        assertEquals(0, c.size)
        assertEquals(emptyList<String>(), c.keysInOrder())
        (1..500).forEach { c.store("k$it", it * 2) }
        assertEquals(500, c.size)
        assertNotNull("the fresh 500th store evicted something", c["k1"])
        assertEquals(2, c["k1"])
    }

    @Test
    fun containsMirrorsGet() {
        val c = InsertionOrderCache<String, Int>()
        assertEquals(false, "k1" in c)
        c.store("k1", 1)
        assertEquals(true, "k1" in c)
        c.clear()
        assertEquals(false, "k1" in c)
    }
}
