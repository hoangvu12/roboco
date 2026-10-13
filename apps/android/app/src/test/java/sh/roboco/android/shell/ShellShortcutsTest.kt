package sh.roboco.android.shell

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hardware-shortcut layer (android-ui-parity #33): the matcher table
 * (the ticket's Matching rules), [dispatchShellShortcut] with a fake
 * router and `isCollapsed` both ways, [ShortcutSink.dispatch] with and
 * without a handler, and [ShellShortcuts.helperGroups] for no shell, tabs
 * only and the split.
 *
 * Robolectric for `android.view.KeyEvent` and `KeyboardShortcutGroup` (and
 * the string resources); no Compose UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellShortcutsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun key(
        action: Int = KeyEvent.ACTION_DOWN,
        code: Int,
        repeat: Int = 0,
        meta: Int = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
    ): KeyEvent = KeyEvent(0L, 0L, action, code, repeat, meta)

    private val ctrl = key(code = KeyEvent.KEYCODE_N)

    // ------------------------------------------------------------ the matcher

    @Test
    fun matchesTheThreeCommands() {
        assertSame(ShellShortcut.NewSession, ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N)))
        // Search is Ctrl+Shift+F (⇧⌘F); plain Ctrl+F is not a command (see
        // shiftMustMatchTheRowExactly).
        val ctrlShift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertSame(ShellShortcut.Search, ShellShortcuts.match(key(code = KeyEvent.KEYCODE_F, meta = ctrlShift)))
        assertSame(ShellShortcut.ToggleSidebar, ShellShortcuts.match(key(code = KeyEvent.KEYCODE_B)))
    }

    @Test
    fun matchesTheComposeKeyOverload() {
        // The preview-key handlers hand over the Compose KeyEvent wrapper;
        // it delegates to the native event's fields.
        val compose = androidx.compose.ui.input.key.KeyEvent(ctrl)
        assertSame(ShellShortcut.NewSession, ShellShortcuts.match(compose))
    }

    @Test
    fun plainKeysDoNotMatch() {
        // No modifiers at all: every plain key returns null.
        for (code in intArrayOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_B)) {
            assertNull(ShellShortcuts.match(key(code = code, meta = 0)))
        }
    }

    @Test
    fun shiftMustMatchTheRowExactly() {
        // Ctrl+Shift+N and Ctrl+Shift+B are not commands; Ctrl+F (Search
        // without shift) is not either.
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N, meta = shift)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_B, meta = shift)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_F)))

        // Ctrl+Shift+F is Search.
        assertSame(ShellShortcut.Search, ShellShortcuts.match(key(code = KeyEvent.KEYCODE_F, meta = shift)))
    }

    @Test
    fun altAndMetaCombosDoNotMatch() {
        // The Windows/Command key is ignored (the Android/ChromeOS
        // convention: ⌘ adapts to Ctrl, not Meta).
        val withAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val withMeta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val metaOnly = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N, meta = withAlt)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_F, meta = withAlt)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_B, meta = withAlt)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N, meta = withMeta)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_F, meta = withMeta)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_B, meta = withMeta)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N, meta = metaOnly)))
    }

    @Test
    fun otherKeysAndActionsDoNotMatch() {
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_A)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_ENTER)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_ENTER, meta = KeyEvent.META_CTRL_ON)))
        assertNull(ShellShortcuts.match(key(action = KeyEvent.ACTION_UP, code = KeyEvent.KEYCODE_N)))
        assertNull(ShellShortcuts.match(key(action = KeyEvent.ACTION_MULTIPLE, code = KeyEvent.KEYCODE_N)))
    }

    @Test
    fun repeatsDoNotMatch() {
        // Holding Ctrl+B must not strobe the sidebar: repeatCount >= 1 is
        // not a command.
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_B, repeat = 1)))
        assertNull(ShellShortcuts.match(key(code = KeyEvent.KEYCODE_N, repeat = 30)))
    }

    // -------------------------------------------------------------- dispatch

    private class RecordingRouter : AppRouter {
        val calls = mutableListOf<String>()
        override fun openSession(chatId: String) {
            calls.add("openSession($chatId)")
        }
        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls.add("openSession($chatId, handoff)")
        }
        override fun presentNewSession(prompt: String?) {
            calls.add("presentNewSession($prompt)")
        }
        override fun showSettings() {
            calls.add("showSettings()")
        }
        override fun showSearch() {
            calls.add("showSearch()")
        }
        override fun showSessionOpenError() {
            calls.add("showSessionOpenError()")
        }
        override fun popToFrontPage() {
            calls.add("popToFrontPage()")
        }
    }

    @Test
    fun newSessionAndSearchDispatchToTheRouter() {
        val router = RecordingRouter()
        assertTrue(dispatchShellShortcut(ShellShortcut.NewSession, router, shell = null))
        assertTrue(dispatchShellShortcut(ShellShortcut.Search, router, shell = null))
        assertEquals(listOf("presentNewSession(null)", "showSearch()"), router.calls)
    }

    @Test
    fun toggleSidebarNeedsAShell() {
        // No shell at all (MainTabShell passes null): not consumed.
        assertFalse(dispatchShellShortcut(ShellShortcut.ToggleSidebar, RecordingRouter(), shell = null))
    }

    @Test
    fun toggleSidebarIsIgnoredWhileCollapsed() {
        val shell = TabletShellState(null, true, DetailRoot.Empty)
        shell.isCollapsed = true
        assertFalse(dispatchShellShortcut(ShellShortcut.ToggleSidebar, RecordingRouter(), shell))
        assertTrue(shell.sidebarVisible)
    }

    @Test
    fun toggleSidebarFlipsWhileTheSplitIsUp() {
        val shell = TabletShellState(null, true, DetailRoot.Empty)
        shell.isCollapsed = false
        assertTrue(dispatchShellShortcut(ShellShortcut.ToggleSidebar, RecordingRouter(), shell))
        assertFalse(shell.sidebarVisible)
        assertTrue(dispatchShellShortcut(ShellShortcut.ToggleSidebar, RecordingRouter(), shell))
        assertTrue(shell.sidebarVisible)
    }

    // ---------------------------------------------------------------- sink

    @Test
    fun sinkDispatchWithoutHandlerDoesNothing() {
        val sink = ShortcutSink()
        assertFalse(sink.shellMounted)
        assertFalse(sink.dispatch(key(code = KeyEvent.KEYCODE_N)))
        assertTrue(ShellShortcuts.helperGroups(context, sink).isEmpty())
    }

    @Test
    fun sinkDispatchForwardsAMatchToTheHandler() {
        val sink = ShortcutSink()
        val seen = mutableListOf<ShellShortcut>()
        sink.handler = { shortcut ->
            seen.add(shortcut)
            dispatchShellShortcut(shortcut, RecordingRouter(), shell = null)
        }
        assertTrue(sink.shellMounted)
        assertTrue(sink.dispatch(key(code = KeyEvent.KEYCODE_N)))
        assertEquals(listOf(ShellShortcut.NewSession), seen)
        // A non-match never reaches the handler.
        assertFalse(sink.dispatch(key(code = KeyEvent.KEYCODE_F)))
        assertEquals(1, seen.size)
    }

    @Test
    fun sinkHandlerResultIsPassedThrough() {
        val sink = ShortcutSink()
        sink.handler = { false }
        assertFalse(sink.dispatch(key(code = KeyEvent.KEYCODE_B)))
    }

    // ------------------------------------------------------- helper groups

    @Test
    fun helperShowsTheRobocoGroupForTheTabShell() {
        val sink = ShortcutSink()
        sink.handler = { true } // a tab shell mounted
        sink.splitMounted = false
        val groups = ShellShortcuts.helperGroups(context, sink)
        assertEquals(1, groups.size)
        assertEquals("Roboco", groups[0].label.toString())
        val items = groups[0].items
        assertEquals(2, items.size)
        assertEquals("New Session", items[0].label.toString())
        assertEquals(KeyEvent.KEYCODE_N, items[0].keycode)
        assertEquals(KeyEvent.META_CTRL_ON, items[0].modifiers)
        assertEquals("Search", items[1].label.toString())
        assertEquals(KeyEvent.KEYCODE_F, items[1].keycode)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, items[1].modifiers)
    }

    @Test
    fun helperAddsToggleSidebarOnlyWhileTheSplitIsUp() {
        val sink = ShortcutSink()
        sink.handler = { true }
        sink.splitMounted = true
        val groups = ShellShortcuts.helperGroups(context, sink)
        val items = groups[0].items
        assertEquals(3, items.size)
        assertEquals("Toggle Sidebar", items[2].label.toString())
        assertEquals(KeyEvent.KEYCODE_B, items[2].keycode)
        assertEquals(KeyEvent.META_CTRL_ON, items[2].modifiers)
    }

    @Test
    fun helperShowsNothingWithoutAShell() {
        // The pairing root: no Roboco group, matching SignInViewController.
        val sink = ShortcutSink()
        assertTrue(ShellShortcuts.helperGroups(context, sink).isEmpty())
    }
}
