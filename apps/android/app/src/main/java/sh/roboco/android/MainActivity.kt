package sh.roboco.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.composer.LocalDrafts
import sh.roboco.android.design.GalleryLaunch
import sh.roboco.android.notify.NotificationPermission
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.pairing.PairingLinks
import sh.roboco.android.pairing.ffiAcceptsPairingUrl
import sh.roboco.android.shell.AppRootState
import sh.roboco.android.shell.DeepLink
import sh.roboco.android.shell.DeepLinks
import sh.roboco.android.shell.LaunchOptions
import sh.roboco.android.shell.LocalShortcutSink
import sh.roboco.android.shell.RootInput
import sh.roboco.android.shell.RobocoAppContent
import sh.roboco.android.shell.ShellShortcuts
import sh.roboco.android.shell.ShortcutSink
import sh.roboco.android.shell.WallpaperTarget
import sh.roboco.android.shell.applyRobocoEdgeToEdge
import sh.roboco.android.shell.holdFirstFrame
import sh.roboco.android.shell.toExtrasMap

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
 * Launch extras and deep links (android-ui-parity #36): `launchMode` is
 * `singleTask`, so every warm link arrives through [onNewIntent] with no
 * second activity instance. The `route` extra and any
 * `roboco://…` VIEW data go to the app-wide
 * [sh.roboco.android.shell.DeepLinkHub] (never straight to a router —
 * the shell is not composed yet at cold start), which dispatches after
 * the shell's first frame and drops links while the pairing root is on
 * screen. The four debug extras (`wallpaper`, `wallpaper-effect`,
 * `harness`, `measureopen`) are parsed here **only**, in `onCreate`,
 * gated on the manifest's `FLAG_DEBUGGABLE` — never in `onNewIntent`.
 * Recreation and Recents re-delivery are guarded with
 * `savedInstanceState == null` + `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
 *
 * Hardware shortcuts (android-ui-parity #33): the activity owns the one
 * [ShortcutSink]; the mounted shell registers on it, and the two overrides
 * below reach it from outside Compose — `onKeyShortcut` when no Compose
 * node has focus, `onProvideKeyboardShortcuts` for the system helper
 * (Meta+/).
 */
class MainActivity : ComponentActivity() {

    /** The observed root (ticket 36 reads `kind` to gate deep links). */
    private val rootState = AppRootState()

    /** One per activity: the bridge from the framework to the mounted shell. */
    private val shortcutSink = ShortcutSink()

    private companion object {
        /** android-app#30: the `pair` hold's budget (the redeemer's own). */
        const val PAIR_HOLD_TIMEOUT_MS = 20_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the first frame (the androidx edge-to-edge template):
        // transparent bars, icons following the effective night mode.
        // SystemBarsEffect re-applies it whenever the appearance changes
        // (ticket 96). The contrast scrims are off for good — see
        // applyRobocoEdgeToEdge.
        applyRobocoEdgeToEdge(isDark = resources.configuration.isNightModeActive)
        super.onCreate(savedInstanceState)
        val app = application as RobocoApplication
        // Ticket 36: the launch extras, on every `onCreate` (after a
        // recreation the restored intent still carries them). The debug
        // gate is the manifest's FLAG_DEBUGGABLE — the installed APK's
        // property, not BuildConfig.DEBUG (a per-variant compile-time
        // constant).
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        LaunchOptions.current = LaunchOptions.parse(intent.extras.toExtrasMap(), debuggable)
        if (savedInstanceState == null && !intent.isFromHistory()) {
            // Fresh launch only: a rotation or a Recents relaunch must not
            // re-import the wallpaper (bumping the store's generation) or
            // re-open the chat the link already opened. Wallpaper first,
            // effect second (iOS order) — before `setContent`, like iOS
            // applies them before `showRoot`.
            if (debuggable) {
                LaunchOptions.current.applyWallpaper(wallpaperTarget(WallpaperStore.get(this)))
                // android-app#30: the flow rig's pairing URLs, redeemed
                // before the first frame is released (see the hold below).
                app.redeemLaunchPairs(LaunchOptions.current.pair)
            }
            handleIntent(intent)
        }
        setContent {
            CompositionLocalProvider(
                LocalShortcutSink provides shortcutSink,
                // Ticket 84: the app's one draft store, behind the seam the
                // composer reads; previews and hosts without the app use
                // the in-memory default.
                LocalDrafts provides app.drafts,
            ) {
                RobocoAppContent(app, rootState)
            }
        }
        // The splash stays until the engine list is known (max 2000 ms).
        // android-app#30: a launch that carried the `pair` extra (debug
        // only — `LaunchOptions.parse` empties it otherwise) waits for the
        // redeemed engines instead, so the flow rig never flashes the
        // pairing root: the hold bounds it at 20 s, the redeemer's own
        // startup budget, and the pairing root shows on failure (the
        // test's own waits then name the rig).
        val awaitingPair = LaunchOptions.current.pair.isNotEmpty()
        holdFirstFrame(timeoutMs = if (awaitingPair) PAIR_HOLD_TIMEOUT_MS else 2_000) {
            val root = app.rootInput.value
            root !is RootInput.Loading &&
                (!awaitingPair || root is RootInput.Ready && root.hasEngines)
        }
        // The debug design gallery (android-ui-parity #22): adb am start with
        // `--es route gallery` opens it on top of the app. Debuggable builds
        // only; the activity does not exist in release.
        if (savedInstanceState == null) GalleryLaunch.maybeStart(this, intent)
    }

    /**
     * The first-session ask's result (android-app#28): record whether the
     * denial was final (a second denial stops the system dialog from ever
     * showing again) — #99's Settings path reuses the same forwarder.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        // `Array<String>`, not `Array<out String>`: androidx.activity's
        // ComponentActivity is Kotlin (invariant `Array<String>`), and
        // `Array<String>` also matches a Java `String[]` declaration.
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NotificationPermission.REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        NotificationPermission.recordRequestResult(
            NotificationPrefs.get(this),
            granted,
            shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION),
        )
        // The run watch's notifications-on gate: an Allow here must reach it
        // now, not on the next foreground edge.
        (application as RobocoApplication).refreshNotificationsOn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Ticket 36: warm links only — the data URI and the `route` extra,
        // never LaunchOptions (no re-parse, no wallpaper re-import).
        if (!intent.isFromHistory()) handleIntent(intent)
        GalleryLaunch.maybeStart(this, intent)
    }

    /**
     * Ticket 36: a VIEW deep link and/or the `route` extra go to the
     * app-wide [sh.roboco.android.shell.DeepLinkHub] — never straight to
     * a router (the shell is not composed yet at cold start). An intent
     * carrying both submits both; the hub's single slot keeps the last
     * one, the route. Ticket 139 adds its `ACTION_SEND` branch here.
     */
    private fun handleIntent(intent: Intent) {
        val hub = (application as RobocoApplication).deepLinks
        if (intent.action == Intent.ACTION_VIEW) {
            val link = DeepLinks.parse(intent.dataString)
            if (link == null) {
                // Unknown link: ignored, logged. Never a `pair` link (the
                // `pair` host always parses), so the pairing token can
                // never land in a log line.
                Log.i("DeepLinks", "ignored ${intent.dataString}")
            } else {
                hub.submit(link)
            }
        }
        LaunchOptions.route(intent.extras.toExtrasMap())?.let(hub::submit)
        // Ticket 139: the share trampoline re-targets its surviving
        // candidates here as an explicit ACTION_SEND (never the raw
        // shared text). The FFI validation runs off the main thread; a
        // share without an accepted link just leaves the app open, with
        // a fixed-string log. The accepted link is re-wrapped as a pair
        // deep link so both paths share one handler and one hold queue.
        if (intent.action == Intent.ACTION_SEND) {
            // runCatching: a hostile parcel can throw BadParcelableException.
            val text = runCatching {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                lifecycleScope.launch {
                    val url = withContext(Dispatchers.Default) {
                        PairingLinks.urlFromSharedText(text, ::ffiAcceptsPairingUrl)
                    }
                    if (url == null) {
                        Log.i("PairingLinks", "share ignored: no pairing link")
                    } else {
                        hub.submit(DeepLink.Pair(PairingLinks.deepLinkFor(url)))
                    }
                }
            }
        }
    }

    /**
     * The real [WallpaperTarget] over ticket 20's store — the seam that
     * keeps `LaunchOptions.applyWallpaper`'s call order unit-testable.
     * The import runs on the store's own lane and its failure only warns
     * (iOS: "if the file can't be read, nothing happens"); never
     * `runBlocking`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun wallpaperTarget(store: WallpaperStore): WallpaperTarget = object : WallpaperTarget {
        override fun remove() {
            store.remove()
        }

        override fun set(path: File) {
            val import = store.set(path)
            import.invokeOnCompletion { cause ->
                // getCompleted is safe here: the job is complete.
                if (cause == null && !import.getCompleted()) {
                    Log.w("LaunchOptions", "wallpaper: could not read ${path.path}")
                }
            }
        }

        override fun setEffect(key: String): Boolean = store.setEffect(key)
    }

    /** A Relaunch from Recents re-delivers the original launch intent; ignore those. */
    private fun Intent.isFromHistory(): Boolean =
        (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

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
