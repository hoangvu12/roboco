package sh.roboco.android.debug

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import sh.roboco.android.RobocoApplication
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.toExtrasMap
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.DebugEntry

/**
 * The debug-only Transcript Lab activity (android-ui-parity #135) — the
 * port of iOS's `-lab` screen (`upstream/main:apps/ios/Zeron/Debug/`
 * `TranscriptLabViewController.swift`): the real transcript host (65/68)
 * fed fixture turns through `setDebugEntries`, a scripted stream, a
 * minimal input row, and the `turns`/`autostream`/`top`/`autoscroll`
 * launch extras. No engine, no pairing, no notifications; the whole
 * class lives in `src/debug`, so release builds never contain it.
 *
 * Reached by
 * `adb shell am start -n sh.roboco.android/.debug.TranscriptLabActivity`
 * (or the gallery's "Transcript Lab" section). The persisted appearance
 * (ticket 96) themes it, `--es appearance dark` overrides it, and the
 * preference is never written from here.
 */
class TranscriptLabActivity : ComponentActivity() {

    private lateinit var controller: TranscriptLabController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val options = LabOptions.fromExtras(intent.extras.toExtrasMap())
        // Main thread only (61): the Rust layout worker is spawned here.
        // The app's `TextEngine.prewarm()` has usually built the text
        // system already, so this does not block on the font load.
        // With `images` (137) the controller's fixture is the edge-case
        // markdown — so the seeded turns **and** the Send stream both use
        // it (the streaming check: no duplicate tiles while it streams).
        controller = if (options.images) {
            TranscriptLabController(TranscriptState(), fixture = LAB_IMAGES_EXTRAS_FIXTURE, scope = lifecycleScope)
        } else {
            TranscriptLabController(TranscriptState(), scope = lifecycleScope)
        }
        if (options.images) {
            // The pr817 demo chat: the prompt, then the reply with the
            // served screenshot, its download link and the missing one.
            // `--ei turns 1` keeps the list short.
            controller.seed(
                options.turns,
                extra = listOf(
                    DebugEntry("img-u", true, LAB_IMAGES_PROMPT, false),
                    DebugEntry("img-a", false, LAB_IMAGES_FIXTURE, false),
                ),
            )
        } else {
            controller.seed(options.turns)
        }
        // Rotation and dark-mode changes are absorbed (configChanges in
        // the manifest), so the entries, a live stream and this controller
        // survive; the scripts only run on a fresh launch.
        val runScripts = savedInstanceState == null
        setContent {
            RobocoTheme(options.appearance ?: appDefaultAppearance()) {
                val dark = RobocoTheme.isDark
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // Ticket 136: the root publishes every test tag below as a
                // resource id (`hitch-meter`, `bench-result`), so UiAutomator
                // and Macrobenchmark find them with By.res — 135 did not set
                // this, and the lab's own tags were invisible to uiautomator.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    TranscriptLabScreen(
                        controller, options, runScripts,
                        overlay = { LabInstruments(controller, options, runScripts) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Mandatory (61): the Rust engine holds the relay. A late ticker
        // tick is ignored by the closed state.
        controller.close()
        super.onDestroy()
    }
}

/**
 * The app's persisted appearance (ticket 96's `Prefs.appearance`,
 * `System` until the disk read lands) — the lab's theme when the
 * `appearance` extra is absent, exactly what iOS's window style override
 * does (`SceneDelegate.swift:14`). Never writes the preference; a
 * non-Roboco host (tests) reads `System`.
 */
@Composable
private fun appDefaultAppearance(): Appearance {
    val app = LocalContext.current.applicationContext as? RobocoApplication
        ?: return Appearance.System
    return app.prefs.appearance.collectAsState().value
}
