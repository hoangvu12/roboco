package sh.roboco.android.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry

/**
 * The transcript-host section of the debug design gallery (ticket 65):
 * a full-bleed host over the `background` role, fed the [HostFixture]
 * through the real core (`setDebugEntries`), with an "Append row" control
 * at the bottom-right that re-sends the whole list with one new entry —
 * the new row's key was never seen, so it fades in over 280 ms. Toggle
 * Light/Dark to watch the rows repaint without rebuilding their models;
 * scroll to see the realized band.

 * Under capture (ticket 22's `LocalGalleryCapture` rule) the section never
 * feeds the engine: an unfed transcript has no rows, so the golden is the
 * deterministic pre-frame state (background + the control). The row
 * goldens live in `TranscriptHostScreenshotTest`, which waits for the
 * frame; the gallery screenshot golden just pins this section's chrome.
 *
 * Build from foundation only: `BasicText` + `clickable` over the theme's
 * `NoIndication` — no Material button.
 */
@Composable
fun TranscriptHostGallerySection() {
    val capture = LocalGalleryCapture.current
    val palette = RobocoTheme.palette
    val state = remember { TranscriptState() }
    DisposableEffect(state) { onDispose { state.close() } }

    // Every call re-sends the whole list, so the list lives here and grows.
    var entries by remember { mutableStateOf(HostFixture) }
    var appended by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries, capture) {
        if (!capture) state.engine.setDebugEntries(entries, working = false)
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .clickable {
                    appended++
                    entries = entries + DebugEntry(
                        id = "x$appended",
                        user = false,
                        text = "Appended row $appended.",
                        streaming = false,
                    )
                }
                .padding(16.dp),
        ) {
            BasicText("Append row", style = TextStyle(color = palette.accent))
        }
    }
}
