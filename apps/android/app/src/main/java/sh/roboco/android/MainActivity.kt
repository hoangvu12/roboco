package sh.roboco.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
import sh.roboco.android.design.GalleryLaunch
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.LocalShortcutSink
import sh.roboco.android.shell.ShellShortcuts
import sh.roboco.android.shell.ShortcutSink
import sh.roboco.android.ui.PlaceholderScreen

/**
 * The single activity: edge-to-edge, then the placeholder until the shell
 * tickets land. The content sits inside `FontGateHost` (ticket 03): a
 * failed startup font check replaces it with the fatal screen. Ticket 35
 * (app root) replaces the placeholder and must keep this wrapper.
 *
 * Lifecycle observers (ProcessLifecycleOwner hooks) are registered in
 * `RobocoApplication`, not here — #609 added one per `onCreate` (review
 * bug 2).
 *
 * Hardware shortcuts (android-ui-parity #33): the activity owns the one
 * [ShortcutSink]; the mounted shell registers on it, and the two overrides
 * below reach it from outside Compose — `onKeyShortcut` when no Compose
 * node has focus, `onProvideKeyboardShortcuts` for the system helper
 * (Meta+/). Ticket 35 must keep all three when it rewrites the content.
 */
class MainActivity : ComponentActivity() {

    /** One per activity: the bridge from the framework to the mounted shell. */
    private val shortcutSink = ShortcutSink()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalShortcutSink provides shortcutSink) {
                RobocoTheme {
                    // The flow, not the snapshot: a late FontGate.fail (ticket 60's
                    // loaders) recomposes this to the fatal screen without
                    // recreating the activity.
                    val fontStatus by FontGate.statusFlow.collectAsState()
                    FontGateHost(fontStatus) {
                        PlaceholderScreen((application as RobocoApplication).core)
                    }
                }
            }
        }
        // The debug design gallery (android-ui-parity #22): adb am start with
        // `--es route gallery` opens it on top of the app. Debuggable builds
        // only; the activity does not exist in release. Tickets 35/36 must keep
        // these two calls when they rewrite onCreate / add onNewIntent.
        if (savedInstanceState == null) GalleryLaunch.maybeStart(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GalleryLaunch.maybeStart(this, intent)
    }

    /**
     * Reached only when the view tree didn't consume a Ctrl combo — e.g.
     * nothing in Compose has focus (fresh launch, after `clearFocus()`),
     * because Compose dispatches key events only to the focused node and
     * its ancestors, so the shell root's `onPreviewKeyEvent` never fires.
     * The framework calls this for an unhandled `ACTION_DOWN` with Ctrl
     * held and `repeatCount == 0`, via `ViewRootImpl` →
     * `dispatchKeyShortcutEvent`. No double handling: a consumed
     * `onPreviewKeyEvent` makes `dispatchKeyEvent` return `true` and the
     * shortcut path is skipped.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent?): Boolean =
        (event != null && shortcutSink.dispatch(event)) || super.onKeyShortcut(keyCode, event)

    /** The system shortcut helper (Meta+/): the "Roboco" group while a shell is mounted. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(ShellShortcuts.helperGroups(this, shortcutSink))
    }
}
