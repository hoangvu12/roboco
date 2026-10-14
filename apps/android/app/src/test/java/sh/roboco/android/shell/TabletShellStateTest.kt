package sh.roboco.android.shell

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet shell state (android-ui-parity #33): the Saver round-trip
 * for each [DetailRoot] kind, `setDetailRoot`'s generation bump and
 * `toggleSidebar`. Plain JUnit: the state holder is pure Compose-runtime
 * (no Android types), so no Robolectric runner is needed.
 */
class TabletShellStateTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun roundTrip(state: TabletShellState): TabletShellState {
        // Saver.save is a member-extension (SaverScope.save(value)): the Saver is
        // the dispatch receiver and the scope the extension receiver — exactly how rememberSaveable's
        // holder calls it.
        val saved = with(TabletShellState.Saver) { with(scope) { save(state) } }
        return TabletShellState.Saver.restore(saved!!)!!
    }

    // ------------------------------------------------------- Saver round-trip

    @Test
    fun draftRoundTripKeepsPromptAndDropsFocusOnAppear() {
        val restored = roundTrip(
            TabletShellState("chat-1", true, DetailRoot.Draft(prompt = "hi", focusOnAppear = true)),
        )
        assertEquals("chat-1", restored.currentChatId)
        assertTrue(restored.sidebarVisible)
        // focusOnAppear is one-shot: it never survives a restore.
        assertEquals(DetailRoot.Draft(prompt = "hi", focusOnAppear = false), restored.detailRoot)
        // Not saved, not restored: the generation restarts and the
        // collapse is re-derived (108's SideEffect).
        assertEquals(0, restored.detailGeneration)
        assertTrue(restored.isCollapsed)
    }

    @Test
    fun draftWithoutPromptRoundTrips() {
        val restored = roundTrip(TabletShellState(null, true, DetailRoot.Draft(null, false)))
        assertEquals(null, restored.currentChatId)
        assertEquals(DetailRoot.Draft(prompt = null, focusOnAppear = false), restored.detailRoot)
    }

    @Test
    fun sessionRoundTrip() {
        val restored = roundTrip(
            TabletShellState("chat-2", false, DetailRoot.Session("chat-9")),
        )
        assertEquals("chat-2", restored.currentChatId)
        assertFalse(restored.sidebarVisible)
        assertEquals(DetailRoot.Session("chat-9"), restored.detailRoot)
    }

    /** /code-review F16: a saved session without its id degrades to Empty, never !!. */
    @Test
    fun aSessionRootWithoutAnIdRestoresEmpty() {
        val restored = TabletShellState.Saver.restore(listOf("chat-1", true, "session", null))!!
        assertEquals(DetailRoot.Empty, restored.detailRoot)
        assertEquals("chat-1", restored.currentChatId)
    }

    @Test
    fun emptyRoundTrip() {
        val restored = roundTrip(TabletShellState(null, true, DetailRoot.Empty))
        assertEquals(DetailRoot.Empty, restored.detailRoot)
    }

    @Test
    fun hiddenSidebarRestoresHidden() {
        val restored = roundTrip(
            TabletShellState(null, initialSidebarVisible = false, initialDetailRoot = DetailRoot.Empty),
        )
        assertFalse(restored.sidebarVisible)
        // The animated fraction re-snaps from the target (SplitRoot's first
        // effect); the Saver deliberately doesn't save it.
        assertEquals(0f, restored.sidebarFraction)
    }

    @Test
    fun shownSidebarRestoresShown() {
        val restored = roundTrip(
            TabletShellState(null, initialSidebarVisible = true, initialDetailRoot = DetailRoot.Empty),
        )
        assertTrue(restored.sidebarVisible)
        assertEquals(1f, restored.sidebarFraction)
    }

    // ------------------------------------------------------------ mutators

    @Test
    fun setDetailRootBumpsGenerationEvenForAnEqualRoot() {
        val state = TabletShellState(null, true, DetailRoot.Draft(null, false))
        state.setDetailRoot(DetailRoot.Session("a"))
        assertEquals(1, state.detailGeneration)
        assertEquals(DetailRoot.Session("a"), state.detailRoot)
        state.setDetailRoot(DetailRoot.Session("a"))
        assertEquals(2, state.detailGeneration)
        assertEquals(DetailRoot.Session("a"), state.detailRoot)
        state.setDetailRoot(DetailRoot.Empty)
        assertEquals(3, state.detailGeneration)
        assertEquals(DetailRoot.Empty, state.detailRoot)
    }

    @Test
    fun toggleSidebarFlipsTheTarget() {
        val state = TabletShellState(null, true, DetailRoot.Empty)
        assertTrue(state.sidebarVisible)
        state.toggleSidebar()
        assertFalse(state.sidebarVisible)
        state.toggleSidebar()
        assertTrue(state.sidebarVisible)
    }

    @Test
    fun setDetailRootIsVisibleToDetailRootReaders() {
        // SplitRoot passes detailRoot to the slot; 106's setRoutes path
        // lands here too.
        val state = TabletShellState(null, true, DetailRoot.Draft("one", false))
        state.setDetailRoot(DetailRoot.Session("b"))
        assertEquals(DetailRoot.Session("b"), state.detailRoot)
    }
}
