package sh.roboco.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.roboco.android.ui.PlaceholderScreen

/**
 * The single activity: edge-to-edge, then the placeholder until the shell
 * tickets land. Lifecycle observers (ProcessLifecycleOwner hooks) are
 * registered in `RobocoApplication`, not here — #609 added one per
 * `onCreate` (review bug 2).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlaceholderScreen((application as RobocoApplication).core)
        }
    }
}
