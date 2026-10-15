package sh.roboco.android.shell.tablet

import androidx.compose.foundation.text.input.clearText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.sessions.FolderRoute

/**
 * [SidebarState] — the long-lived state holder (android-ui-parity #105),
 * plain JUnit: `NavStack` completes its ops synchronously without a host,
 * so `focusSearch()`/`showSessions()`'s pop-to-root is observable here
 * without Robolectric. The focus hand-off itself (the flags being
 * consumed by the field) is [SidebarTest]'s job.
 */
class SidebarStateTest {

    /** A stack pushed to depth 3 (the root + three folders). */
    private fun stacked(): SidebarState = SidebarState().apply {
        stack.push(FolderRoute.pinned(), animated = false)
        stack.push(FolderRoute.archived(), animated = false)
        stack.push(FolderRoute.section("sec-p0", "P0"), animated = false)
    }

    @Test
    fun focusSearchPopsToRootWithoutAnimationAndRequestsFocus() {
        val s = stacked()
        assertEquals(4, s.stack.entries.size)

        s.focusSearch()

        // popToRoot(false): no transition is left, no animation ran, and
        // the stack is at the root.
        assertEquals(1, s.stack.entries.size)
        assertSame(SidebarRootRoute, s.stack.entries.single().route)
        assertNull(s.stack.transition)
        // The one-shot flags: focus requested, no drop pending.
        assertTrue(s.pendingFocus)
        assertFalse(s.pendingDropFocus)
    }

    @Test
    fun showSessionsEndsTheSearchEmptiesTheTextAndPopsToRoot() {
        val s = stacked()
        s.search.edit { append("tool") }
        assertEquals("tool", s.query)

        s.showSessions()

        assertEquals("", s.query)
        assertEquals(1, s.stack.entries.size)
        assertSame(SidebarRootRoute, s.stack.entries.single().route)
        assertNull(s.stack.transition)
        assertTrue(s.pendingDropFocus)
        assertFalse(s.pendingFocus)
    }

    @Test
    fun aLaterRequestSupersedesAnEarlierOne() {
        // focusSearch() then showSessions(): the drop wins (the field must
        // not grab the IME after the search was ended); the reverse order
        // cancels the drop. The flags are one slot, not a queue — iOS's
        // two methods are always called from the main thread in order.
        val a = stacked()
        a.focusSearch()
        a.showSessions()
        assertFalse(a.pendingFocus)
        assertTrue(a.pendingDropFocus)

        val b = stacked()
        b.showSessions()
        b.focusSearch()
        assertTrue(b.pendingFocus)
        assertFalse(b.pendingDropFocus)
    }

    @Test
    fun queryMirrorsTheRawFieldText() {
        val s = SidebarState()
        assertEquals("", s.query)
        s.search.edit { append("wr") }
        assertEquals("wr", s.query)
        s.search.edit { append(" angler") }
        assertEquals("wr angler", s.query)
        // Raw, never trimmed — trimming is 49's `searchTrim`, applied in
        // `sidebarListContent` and the search model only.
        s.search.edit { append("  ") }
        assertEquals("wr angler  ", s.query)
        s.search.clearText()
        assertEquals("", s.query)
    }
}
