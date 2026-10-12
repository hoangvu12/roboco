package sh.roboco.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
import sh.roboco.android.design.GalleryLaunch
import sh.roboco.android.design.RobocoTheme
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
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RobocoTheme {
                FontGateHost(FontGate.status) {
                    PlaceholderScreen((application as RobocoApplication).core)
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
}
