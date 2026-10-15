package sh.roboco.android.shell

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import sh.roboco.android.R

/**
 * Hardware keyboard shortcuts (android-ui-parity #33), the Android port of
 * `ios:Shell/SplitRootController.swift:53-67`'s `keyCommands`:
 *
 * | Keys | iOS | Title | Action |
 * |---|---|---|---|
 * | Ctrl+N | ⌘N | `New Session` | `router.presentNewSession(null)` |
 * | Ctrl+Shift+F | ⇧⌘F | `Search` | `router.showSearch()` |
 * | Ctrl+B | ⌘B | `Toggle Sidebar` | `shell.toggleSidebar()` (split only) |
 *
 * ⌘ adapts to Ctrl (the Android/ChromeOS convention); Meta (Win/Cmd)
 * combos are ignored. The commands sit on the shell root, so — like the
 * iOS responder chain from any focused view, the composer's text view
 * included — [Modifier.onPreviewKeyEvent] on the root sees them before the
 * focused field. Only matched combos are consumed: plain keys and other
 * Ctrl combos (Ctrl+A/C/V/Z, Ctrl+Enter) continue to the field.
 */

/** The three commands, in shortcut-helper order. */
enum class ShellShortcut(@StringRes val title: Int, val keyCode: Int, val shift: Boolean) {
    NewSession(R.string.shortcut_new_session, KeyEvent.KEYCODE_N, shift = false),
    Search(R.string.shortcut_search, KeyEvent.KEYCODE_F, shift = true),
    ToggleSidebar(R.string.shortcut_toggle_sidebar, KeyEvent.KEYCODE_B, shift = false);

    /** The modifiers shown in the system shortcut helper. */
    val metaState: Int
        get() = KeyEvent.META_CTRL_ON or (if (shift) KeyEvent.META_SHIFT_ON else 0)
}

object ShellShortcuts {

    /**
     * The one matcher every entry point uses (preview keys, the Activity
     * fallback, the helper). Requires:
     *
     *  - `ACTION_DOWN` **and** `repeatCount == 0` (holding Ctrl+B must not
     *    strobe the sidebar);
     *  - `isCtrlPressed`, no Alt, no Meta (Win/Cmd);
     *  - Shift exactly as the row requires — Ctrl+Shift+N, Ctrl+Shift+B and
     *    Ctrl+F do not match;
     *  - the row's key code.
     *
     * Everything else returns null and is not consumed.
     */
    fun match(e: KeyEvent): ShellShortcut? {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return null
        if (!e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return null
        return ShellShortcut.entries.firstOrNull { it.keyCode == e.keyCode && it.shift == e.isShiftPressed }
    }

    /** The Compose-key overload the preview handlers use. */
    fun match(e: androidx.compose.ui.input.key.KeyEvent): ShellShortcut? = match(e.nativeKeyEvent)

    /**
     * The system shortcut helper (Meta+/, `Activity.onProvideKeyboardShortcuts`)
     * entries: the `Roboco` group — "New Session" and "Search" whenever a
     * shell is mounted, plus "Toggle Sidebar" only while the split is up
     * (a compact phone would show a dead entry; iPad lists it even
     * collapsed, where it is a silent no-op). No shell (the pairing root)
     * → no group at all, matching `SignInViewController`, which has no
     * commands.
     */
    fun helperGroups(context: Context, sink: ShortcutSink): List<KeyboardShortcutGroup> {
        if (!sink.shellMounted) return emptyList()
        val items = listOf(ShellShortcut.NewSession, ShellShortcut.Search) +
            if (sink.splitMounted) listOf(ShellShortcut.ToggleSidebar) else emptyList()
        return listOf(
            KeyboardShortcutGroup(
                context.getString(R.string.shortcut_group),
                items.map { KeyboardShortcutInfo(context.getString(it.title), it.keyCode, it.metaState) },
            ),
        )
    }
}

/**
 * Runs [s]: returns true when handled (the event is then consumed).
 * Ctrl+B is only handled while the split is up — with the shell missing or
 * collapsed it falls through unconsumed.
 */
fun dispatchShellShortcut(s: ShellShortcut, router: AppRouter, shell: TabletShellState?): Boolean =
    when (s) {
        ShellShortcut.NewSession -> {
            router.presentNewSession(null)
            true
        }
        ShellShortcut.Search -> {
            router.showSearch()
            true
        }
        ShellShortcut.ToggleSidebar ->
            if (shell != null && !shell.isCollapsed) {
                shell.toggleSidebar()
                true
            } else {
                false
            }
    }

/**
 * One per [sh.roboco.android.MainActivity]: lets
 * `Activity.onKeyShortcut` reach the mounted shell when no Compose node
 * has focus (fresh launch, after `clearFocus()`). Compose dispatches key
 * events only to the focused node and its ancestors, so the root's
 * `onPreviewKeyEvent` never fires then; the framework instead routes the
 * unhandled Ctrl combo through `ViewRootImpl` → `dispatchKeyShortcutEvent`
 * → `Activity.onKeyShortcut`. No double handling: a consumed
 * `onPreviewKeyEvent` makes `dispatchKeyEvent` return `true`, and the
 * shortcut path is skipped.
 */
class ShortcutSink {
    /** The mounted shell's handler (the last one to mount wins). */
    internal var handler: ((ShellShortcut) -> Boolean)? = null

    /** True while `SplitRoot` is the mounted shell (adds Ctrl+B to the helper). */
    internal var splitMounted: Boolean = false

    /** True while any shell (tabs or split) is mounted. */
    val shellMounted: Boolean get() = handler != null

    /** `Activity.onKeyShortcut`'s entry point. */
    fun dispatch(e: KeyEvent): Boolean =
        ShellShortcuts.match(e)?.let { shortcut -> handler?.invoke(shortcut) } ?: false
}

/** The nearest activity's sink; null in previews, gallery sections and tests that don't provide one. */
val LocalShortcutSink = staticCompositionLocalOf<ShortcutSink?> { null }

/**
 * The shell-root modifier: preview-key handling plus registration with the
 * Activity's [ShortcutSink] (so `onKeyShortcut` and the helper reach this
 * shell). [SplitRoot] passes its `shell`; [MainTabShell] passes `null`, so
 * Ctrl+B falls through unconsumed there.
 */
@Composable
fun rememberShellShortcuts(router: AppRouter, shell: TabletShellState?): Modifier {
    val latest = rememberUpdatedState { shortcut: ShellShortcut ->
        dispatchShellShortcut(shortcut, router, shell)
    }
    val sink = LocalShortcutSink.current
    DisposableEffect(sink, shell != null) {
        val h: (ShellShortcut) -> Boolean = { latest.value(it) }
        sink?.handler = h
        if (shell != null) sink?.splitMounted = true
        onDispose {
            // sink is an immutable local: only the handler this shell
            // installed is cleared (a newer shell may have replaced it).
            if (sink != null) {
                if (sink.handler === h) sink.handler = null
                if (shell != null) sink.splitMounted = false
            }
        }
    }
    return remember {
        Modifier.onPreviewKeyEvent { e -> ShellShortcuts.match(e)?.let { latest.value(it) } ?: false }
    }
}
