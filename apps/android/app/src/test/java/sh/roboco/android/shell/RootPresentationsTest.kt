package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root-swap dismissal registry (android-ui-parity #35, plain JVM for
 * the merge check): latest registered first, unregister works, and the
 * entries list is a snapshot (a handler that unregisters its neighbour
 * cannot skip it). `RegisterRootPresentation(active = false)` is covered
 * by `AppRootTest` (it needs a composition).
 */
class RootPresentationsTest {

    @Test
    fun dismissesLatestRegisteredFirst() {
        val registry = RootPresentations()
        val order = mutableListOf<String>()
        registry.register { order += "a" }
        registry.register { order += "b" }
        registry.register { order += "c" }

        registry.dismissAllInstantly()

        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun anUnregisteredEntryIsNotDismissed() {
        val registry = RootPresentations()
        var dismissed = 0
        val unregister = registry.register { dismissed++ }
        unregister()

        registry.dismissAllInstantly()

        assertEquals(0, dismissed)
    }

    @Test
    fun dismissingTwiceIsSafe() {
        val registry = RootPresentations()
        var dismissed = 0
        // Entries stay registered (RootHosts registers its hosts once for
        // the root's lifetime), so a second call runs the handler again; a
        // real presenter's instant dismiss is idempotent, like this one.
        var showing = true
        registry.register {
            if (showing) {
                showing = false
                dismissed++
            }
        }

        registry.dismissAllInstantly()
        registry.dismissAllInstantly()

        assertEquals(1, dismissed)
    }

    @Test
    fun theSnapshotSurvivesAHandlerUnregisteringAnother() {
        val registry = RootPresentations()
        val order = mutableListOf<String>()
        registry.register { order += "a" }
        val unregisterB = registry.register { order += "b" }
        // "c"'s handler unregisters "b" while the snapshot iterates; "b"
        // still runs — all entries of the same frame are dismissed.
        registry.register {
            order += "c"
            unregisterB()
        }

        registry.dismissAllInstantly()

        assertEquals(listOf("c", "b", "a"), order)
        assertTrue(order.isNotEmpty())
    }
}
