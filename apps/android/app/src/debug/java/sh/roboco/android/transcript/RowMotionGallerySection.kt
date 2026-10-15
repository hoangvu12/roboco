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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.RobocoTheme

/**
 * The row-motion section of the debug design gallery (ticket 132): a
 * full-bleed host over the `background` role, fed [MotionFixture] through
 * the real core (`setDebugEntries`) — a long pasted prompt that starts
 * folded with a "Show more" disclosure (120), the reply that slides down
 * when it expands, and a short follow-up. Tapping "Show more" tweens the
 * rows below over **140 ms easeOut** (the fold); tapping "Show less"
 * mid-way reverses it from the drawn position. The gallery's "Force
 * reduce motion" toggle (22) makes the same toggle jump, and the section's
 * own "Slow ×10" control multiplies **new** segments' durations by 10, so
 * the curve is visible by eye.
 *
 * Under capture (ticket 22's `LocalGalleryCapture` rule) the section never
 * feeds the engine: an unfed transcript has no rows, so the golden is the
 * deterministic pre-frame state (background + the control) — nothing is
 * visible at rest by design, and the motion itself is asserted numerically
 * in `RowMotionHostTest`. Tool-group growth cannot be produced by
 * `DebugEntry` (markdown/user only); it is checked in the Transcript Lab
 * (135) and in the unit tests.
 *
 * Build from foundation only, like 65's "Append row" control: `BasicText` +
 * `clickable` — no Material button, no Material switch.
 */
@Composable
fun RowMotionGallerySection() {
    val capture = LocalGalleryCapture.current
    val palette = RobocoTheme.palette
    val state = remember { TranscriptState() }
    DisposableEffect(state) { onDispose { state.close() } }
    LaunchedEffect(capture) {
        if (!capture) state.engine.setDebugEntries(MotionFixture, working = false)
    }

    var slow by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .clickable {
                    slow = !slow
                    state.rowMotion.debugTimeScale = if (slow) 10f else 1f
                }
                .padding(16.dp),
        ) {
            BasicText(
                if (slow) "Slow ×10: on" else "Slow ×10: off",
                style = TextStyle(color = palette.accent), // #5B43E8 / #8B7CF6
            )
        }
    }
}
