package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Status widgets" section of the debug design gallery (ticket 123,
 * registered in `Gallery.sections`): the deterministic fixture column
 * ([StatusWidgetsFixture] — the three ToolStatus states, the subagent-card
 * mock with its trailing spinner, and the three frozen Working rows), plus
 * the **live** tail row — [WorkingIndicator] over the real wall clock, so
 * the time advances once a second with no relayout, and the
 * "Toggle streaming" text flips the word Working ↔ Writing instantly.
 *
 * Under the Roborazzi gallery capture ([LocalGalleryCapture]) the live
 * row's clock is pinned to `sinceMs + 5 s` — the golden shows a constant
 * `Working…  5s`, byte-stable, like the status pill's frozen live row
 * (ticket 73); outside captures it ticks for real.
 *
 * Registered as `GallerySection("Status widgets")` — Scroll layout, default
 * shot height.
 */
@Composable
fun StatusWidgetsGallerySection() {
    val capture = LocalGalleryCapture.current
    var streaming by remember { mutableStateOf(false) }
    // sinceMs is read once: a live turn's start does not move while it runs.
    val sinceMs = remember { System.currentTimeMillis() - 5_000L }
    // Frozen at +5 s under capture; the wall clock otherwise.
    val clock: () -> Long = if (capture) {
        { sinceMs + 5_000L }
    } else {
        WallClock
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusWidgetsFixture()
        Box(Modifier.size(354.dp, 36.dp)) {
            WorkingIndicator(sinceMs = sinceMs, streaming = streaming, clock = clock)
        }
        // Foundation `clickable` on a BasicText — no Material controls.
        BasicText(
            "Toggle streaming",
            style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), RobocoTheme.palette.accent),
            modifier = Modifier
                .clickable(interactionSource = null, indication = null) { streaming = !streaming }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
