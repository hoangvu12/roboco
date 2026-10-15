package sh.roboco.android.shell

import androidx.compose.foundation.text.input.clearText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SearchTabState] through a real [MainTabState] wired with `onSelected`
 * (android-ui-parity #29): `previousTab` follows Sessions/Settings →
 * Search, Search → Search changes nothing, the `activationToken`
 * increments only on entry, back-from-root routes through the same hook,
 * and `query` mirrors `text` (raw, untrimmed). Plain JVM — every piece
 * here is snapshot state, no Robolectric needed.
 */
class SearchTabStateTest {

    private fun wired(initial: TabId = TabId.Sessions): Pair<MainTabState, SearchTabState> {
        val main = MainTabState(initial)
        val search = SearchTabState()
        main.onSelected = search::onTabSelected
        return main to search
    }

    // -------------------------------------------------- previousTab + token

    @Test
    fun previousTabFollowsTheSelectionsThroughTheWiredShell() {
        val (main, search) = wired()
        main.select(TabId.Settings)
        assertEquals(TabId.Sessions, main.previousTab)
        assertEquals(0, search.activationToken)

        main.select(TabId.Search)
        assertEquals(TabId.Settings, main.previousTab)
        assertEquals(1, search.activationToken)

        // A re-select of the already-selected tab is a no-op (UIKit's
        // selectedTab): no token, no previousTab change.
        main.select(TabId.Search)
        assertEquals(TabId.Settings, main.previousTab)
        assertEquals(1, search.activationToken)

        main.select(TabId.Sessions)
        assertEquals(1, search.activationToken)
        main.select(TabId.Search)
        assertEquals(2, search.activationToken)
        // Leaving Search does not update previousTab; re-entering keeps
        // pointing at the tab the user actually came from.
        assertEquals(TabId.Sessions, main.previousTab)
    }

    @Test
    fun onTabSelectedCountsOnlyEntriesIntoSearch() {
        val s = SearchTabState()
        s.onTabSelected(TabId.Sessions, TabId.Settings)
        assertEquals(0, s.activationToken)
        s.onTabSelected(TabId.Settings, TabId.Search)
        assertEquals(1, s.activationToken)
        s.onTabSelected(TabId.Search, TabId.Settings)
        assertEquals(1, s.activationToken)
        s.onTabSelected(TabId.Sessions, TabId.Search)
        assertEquals(2, s.activationToken)
    }

    @Test
    fun backFromRootRoutesThroughTheHookToo() {
        val (main, search) = wired(TabId.Settings)
        main.select(TabId.Search)
        assertEquals(1, search.activationToken)
        // System back at the Search root: 26's backFromRoot → select →
        // the hook (a leave, not an entry).
        assertEquals(true, main.backFromRoot())
        assertEquals(TabId.Settings, main.selected)
        assertEquals(1, search.activationToken)
        assertEquals(TabId.Settings, main.previousTab)
    }

    @Test
    fun unwiredSelectsDoNotTouchTheToken() {
        val main = MainTabState(TabId.Sessions)
        main.select(TabId.Search)
        val search = SearchTabState()
        assertEquals(0, search.activationToken)
    }

    // ------------------------------------------------- the rotation guard

    @Test
    fun aConsumedTokenDoesNotRefocus() {
        val s = SearchTabState()
        assertEquals(0, s.consumedToken)
        assertEquals(0, s.activationToken)
        s.onTabSelected(TabId.Sessions, TabId.Search)
        // The field acted on it (a recreated composition must not refocus).
        s.consumedToken = s.activationToken
        assertEquals(1, s.consumedToken)
        // Re-entering Search after leaving bumps it again.
        s.onTabSelected(TabId.Settings, TabId.Search)
        assertEquals(2, s.activationToken)
        assertEquals(1, s.consumedToken)
    }

    // ----------------------------------------------------------- the query

    @Test
    fun queryMirrorsTheRawText() {
        val s = SearchTabState()
        assertEquals("", s.query)
        s.text.edit { append("ze") }
        assertEquals("ze", s.query)
        // Raw, never trimmed: the spaces belong to ticket 49's search.
        s.text.edit { append(" ") }
        assertEquals("ze ", s.query)
        s.text.clearText()
        assertEquals("", s.query)
    }

    @Test
    fun aFreshProcessStartsWithAnEmptyQuery() {
        // The holder is never saved to instance state: a fresh SearchTabState
        // (a new process) starts empty — the query is gone by construction.
        assertEquals("", SearchTabState().query)
    }
}
