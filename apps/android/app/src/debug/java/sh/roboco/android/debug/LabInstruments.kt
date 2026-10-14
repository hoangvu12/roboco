package sh.roboco.android.debug

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The lab's perf instruments (android-ui-parity #136), passed as ticket
 * 135's `TranscriptLabScreen` overlay — the slot measured
 * `Constraints.fixed(W, H)` and placed last, so it draws above the
 * transcript, the header and the input row. The covering `Box` carries no
 * pointer modifiers: touches pass through to the transcript (the iOS
 * `UILabel`s sit in the view tree the same way).
 *
 * Owns the two `--ez` extras of the same names: `meter` composes the
 * [HitchMeter]; `bench` runs the [runLabBench] script 1.5 s after the
 * overlay first composes (only on a first launch — `runScripts == false`
 * after a recreation, like 135's scripts), after waiting for the first
 * frame with rows so a slow first 300-turn layout cannot make every
 * fling end instantly at `minOffset == maxOffset`.
 *
 * Always composes [ReportDrawnWhen]: the lab reports fully drawn once the
 * first frame has rows — the `waitUntilResponsive` analogue
 * `StartupTimingMetric` reads as time-to-fully-drawn. Nothing here calls
 * haptics; nothing changes under reduced motion (a measurement script
 * must run identically with "Remove animations" on — `withFrameNanos`
 * and Choreographer callbacks are not scaled by `MotionDurationScale`).
 */
@Composable
fun LabInstruments(controller: TranscriptLabController, options: LabOptions, runScripts: Boolean) {
    val activity = LocalActivity.current as ComponentActivity
    val budget = remember { FrameBudget(activity) }
    DisposableEffect(budget) { onDispose { budget.close() } }
    val rate = remember { RefreshRateRequest(activity) }
    ReportDrawnWhen { (controller.state.frame?.rowCount() ?: 0u) > 0u }
    var result by remember { mutableStateOf<String?>(null) }
    if (options.bench && runScripts) {
        LaunchedEffect(Unit) {
            delay(1500)
            snapshotFlow { controller.state.frame?.rowCount() ?: 0u }.first { it > 0u }
            result = "" // the label appears (empty) when the bench starts
            result = runLabBench(
                activity,
                controller.state.viewport.asBenchTarget(),
                controller::send,
                budget,
                rate,
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (options.meter) {
            HitchMeter(
                controller.state, budget, rate,
                // iOS: 4 pt below the nav bar's safe-area top, 12 pt in
                // from the trailing safe edge (135's title bar is 44 dp
                // below the status bar).
                Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(top = 44.dp + 4.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .padding(end = 12.dp),
            )
        }
        result?.let { BenchResultLabel(it) }
    }
}
