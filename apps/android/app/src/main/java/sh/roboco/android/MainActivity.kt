package sh.roboco.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import sh.roboco.android.core.FontGate
import sh.roboco.android.design.FontGateHost
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
}
