package sh.roboco.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import sh.roboco.android.design.GalleryLaunch
import sh.roboco.android.shell.AppRootState
import sh.roboco.android.shell.LocalShortcutSink
import sh.roboco.android.shell.RootInput
import sh.roboco.android.shell.RobocoAppContent
import sh.roboco.android.shell.ShellShortcuts
import sh.roboco.android.shell.ShortcutSink
import sh.roboco.android.shell.applyRobocoEdgeToEdge
import sh.roboco.android.shell.holdFirstFrame

/**
 * The single activity (android-ui-parity #35): edge-to-edge with
 * transparent system bars, then the app root — [RobocoAppContent], which
 * mounts `RobocoTheme`, the font gate, the overlay hosts and
 * [sh.roboco.android.shell.AppRoot] (pairing / phone tab shell / tablet
 * split, with the 350 ms cross-fade).
 *
 * The platform splash is held ([holdFirstFrame]) until the root input
 * leaves `Loading` (the engine list is known) or 2000 ms pass, so a
 * paired user never sees the pairing root flash.
 *
 * Lifecycle observers (ProcessLifecycleOwner hooks) and the network
 * callback are registered in `RobocoApplication`, never here — #609
 * added one observer per `onCreate` (review bug 2) and #621 redialed on
 * every rotation. **No `onStart`/`onStop` overrides** call the core.
 *
 * The activity declares `configChanges` (orientation … navigation), so
 * rotation, fold/unfold, resize and appearance changes do **not**
 * recreate it — iOS controllers survive size changes; the open sheet,
 * nav stacks and scroll offsets survive too (ticket 108's carry-over
 * depends on this).
 *
 * Hardware shortcuts (android-ui-parity #33): the activity owns the one
 * [ShortcutSink]; the mounted shell registers on it, and the two overrides
 * below reach it from outside Compose — `onKeyShortcut` when no Compose
 * node has focus, `onProvideKeyboardShortcuts` for the system helper
 * (Meta+/). `onNewIntent` and deep links are ticket 36's.
 */
class MainActivity : ComponentActivity() {

    /** The observed root (ticket 36 reads `kind` to gate deep links). */
    private val rootState = AppRootState()

    /** One per activity: the bridge from the framework to the mounted shell. */
    private val shortcutSink = ShortcutSink()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the first frame (the androidx edge-to-edge template):
        // transparent bars, icons following the effective night mode.
        // SystemBarsEffect re-applies it whenever the appearance changes
        // (ticket 96). The contrast scrims are off for good — see
        // applyRobocoEdgeToEdge.
        applyRobocoEdgeToEdge(isDark = resources.configuration.isNightModeActive)
        super.onCreate(savedInstanceState)
        val app = application as RobocoApplication
        setContent {
            CompositionLocalProvider(LocalShortcutSink provides shortcutSink) {
                RobocoAppContent(app, rootState)
            }
        }
        // The splash stays until the engine list is known (max 2000 ms).
        holdFirstFrame { app.rootInput.value !is RootInput.Loading }
        // The debug design gallery (android-ui-parity #22): adb am start with
        // `--es route gallery` opens it on top of the app. Debuggable builds
        // only; the activity does not exist in release. Ticket 36 keeps the
        // onNewIntent half of this pair.
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
