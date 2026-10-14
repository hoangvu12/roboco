package sh.roboco.android.debug

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.TranscriptPerf
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The lab bench's runtime (android-ui-parity #136): the frame budget, the
 * display-mode request, the JankStats cross-check, the combined JSON and
 * the `bench.json` write — the port of iOS's `runBench`
 * (`upstream/main:apps/ios/Zeron/Debug/TranscriptLabViewController.swift:150-180`).
 */

/** The `RobocoLab` log tag (`NSLog("BENCH …")` becomes `Log.i` here). */
internal const val LAB_LOG_TAG = "RobocoLab"

/**
 * The frame budget source (the ADAPT of `targetTimestamp − timestamp`):
 * `1e9 / refreshRate` for the activity display's current rate, cached and
 * refreshed from [DisplayManager.DisplayListener.onDisplayChanged] for
 * that display id — never a binder read per frame. Main thread only,
 * except [hz], which is `@Volatile` (the JankStats listener thread reads
 * nothing here, but the bench may be inspected from anywhere).
 */
internal class FrameBudget(activity: Activity) {

    @Volatile
    var hz: Float = 60f
        private set

    private val displayManager: DisplayManager? = activity.getSystemService(DisplayManager::class.java)
    private val displayId: Int = activity.display?.displayId ?: Display.INVALID_DISPLAY

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == this@FrameBudget.displayId) refresh()
        }
    }

    init {
        activity.display?.refreshRate?.let { if (it > 1f) hz = it }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    /** Re-reads the rate from the display manager (the display-change path). */
    fun refresh() {
        val rate = displayManager?.getDisplay(displayId)?.refreshRate ?: return
        if (rate > 1f) hz = rate
    }

    /** The frame budget in ns: `1e9 / hz` (8 333 333 at 120 Hz). */
    val nanos: Long
        get() = (1e9 / hz).toLong()

    fun close() {
        displayManager?.unregisterDisplayListener(listener)
    }
}

/**
 * The ADAPT of `CADisplayLink.preferredFrameRateRange(preferred: 120)`:
 * ref-counted per activity, the request holds the display's highest-rate
 * mode at the current resolution (`preferredDisplayModeId`) while
 * acquired. Every change **reassigns** `window.attributes` — mutating the
 * returned `LayoutParams` alone does nothing. The first [acquire] saves
 * the current mode id; the last [release] restores it. Main thread only.
 */
internal class RefreshRateRequest(private val activity: Activity) {
    private var refs = 0
    private var savedModeId = 0

    fun acquire() {
        if (refs > 0) {
            refs++
            return
        }
        val display = activity.display ?: return // refs stays 0: nothing to undo
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return
        refs = 1
        val window = activity.window
        savedModeId = window.attributes.preferredDisplayModeId
        window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
    }

    fun release() {
        if (refs <= 0) return
        if (--refs > 0) return
        val window = activity.window
        window.attributes = window.attributes.also { it.preferredDisplayModeId = savedModeId }
    }
}

/**
 * The JankStats cross-check (`"jankstats"` in the result JSON): per-phase
 * frames, janky frames and worst frame duration, tallied from the
 * JankStats listener. The listener runs on JankStats' frame-metrics
 * thread (not main) and **reuses** its [FrameData] after the callback
 * returns, so [record] copies the fields immediately and the tally is
 * `synchronized`. The object stays flat (string → number), so the
 * iOS-style parse `Map<String, Map<String, Double>>` keeps working.
 */
internal class JankTally {

    enum class Phase { Idle, Streaming }

    @Volatile
    var phase = Phase.Idle

    private var idleFrames = 0
    private var idleJanky = 0
    private var idleWorstNanos = 0L
    private var streamFrames = 0
    private var streamJanky = 0
    private var streamWorstNanos = 0L

    /**
     * The listener entry point: reads `fd`'s fields immediately (JankStats
     * reuses the object). API 31+ frames carry `frameDurationTotalNanos`
     * (CPU + GPU); older ones fall back to the UI-thread duration.
     */
    fun record(fd: FrameData) {
        val jank = fd.isJank
        val nanos = (fd as? FrameDataApi31)?.frameDurationTotalNanos ?: fd.frameDurationUiNanos
        record(jank, nanos)
    }

    /** The pure tally entry point (also the JVM test seam). */
    fun record(isJank: Boolean, durationNanos: Long) {
        synchronized(this) {
            if (phase == Phase.Idle) {
                idleFrames += 1
                if (isJank) idleJanky += 1
                if (durationNanos > idleWorstNanos) idleWorstNanos = durationNanos
            } else {
                streamFrames += 1
                if (isJank) streamJanky += 1
                if (durationNanos > streamWorstNanos) streamWorstNanos = durationNanos
            }
        }
    }

    /** The flat `"jankstats"` JSON object, in the iOS key shape. */
    fun json(refreshHz: Float): String = synchronized(this) {
        String.format(
            Locale.ROOT,
            "{\"idleFrames\":%d,\"idleJanky\":%d,\"idleJankyPct\":%.2f,\"idleWorstMs\":%.1f," +
                "\"streamingFrames\":%d,\"streamingJanky\":%d,\"streamingJankyPct\":%.2f," +
                "\"streamingWorstMs\":%.1f,\"refreshHz\":%.1f}",
            idleFrames,
            idleJanky,
            pct(idleFrames, idleJanky),
            idleWorstNanos / 1e6,
            streamFrames,
            streamJanky,
            pct(streamFrames, streamJanky),
            streamWorstNanos / 1e6,
            refreshHz,
        )
    }

    private fun pct(frames: Int, janky: Int): Double =
        if (frames > 0) 100.0 * janky / frames else 0.0
}

/**
 * The combined result JSON: `{"idle":…,"streaming":…,"idlePerf":…,
 * "streamingPerf":…,"jankstats":…}` in that key order, with no
 * whitespace (the iOS string interpolation shape, plus Roboco's
 * `jankstats`).
 */
fun benchJson(idle: BenchResult, streaming: BenchResult, idlePerf: String, streamingPerf: String, jank: String): String =
    "{\"idle\":${idle.json},\"streaming\":${streaming.json},\"idlePerf\":$idlePerf," +
        "\"streamingPerf\":$streamingPerf,\"jankstats\":$jank}"

/**
 * iOS's bench sequence (`runBench`, `TranscriptLabViewController.swift:155-180`):
 * reset the counters, the idle bench (3 passes), snapshot `idlePerf`,
 * reset, `send("Stream the plan again while I scroll.")`, the streaming
 * bench (2 passes) — **no suspension between the send and the bench**,
 * so `runScrollBench`'s `releaseFollow()` stops the follow spring before
 * it ever ticks — then log, write `filesDir/bench.json`, return the JSON
 * (the caller fills the label). JankStats tracks the whole run and the
 * display-mode request is held while it runs.
 */
internal suspend fun runLabBench(
    activity: ComponentActivity,
    viewport: BenchScrollTarget,
    send: (String) -> Unit,
    budget: FrameBudget,
    rate: RefreshRateRequest,
): String {
    rate.acquire()
    val tally = JankTally()
    val jank = JankStats.createAndTrack(activity.window) { fd -> tally.record(fd) }
    try {
        TranscriptPerf.reset()
        val idle = runScrollBench(viewport, passes = 3, budget::nanos)
        val idlePerf = TranscriptPerf.json
        TranscriptPerf.reset()
        send("Stream the plan again while I scroll.")
        tally.phase = JankTally.Phase.Streaming
        val streaming = runScrollBench(viewport, passes = 2, budget::nanos)
        val json = benchJson(idle, streaming, idlePerf, TranscriptPerf.json, tally.json(budget.hz))
        Log.i(LAB_LOG_TAG, "BENCH $json")
        withContext(Dispatchers.IO) { writeBenchJson(activity.filesDir, json) }
        return json
    } finally {
        jank.isTrackingEnabled = false
        rate.release()
    }
}

/**
 * Writes the JSON to `filesDir/bench.json` atomically (iOS writes
 * Documents and ignores errors with `try?`; here the write is logged and
 * continued). Roboco adaptation: `AtomicFile` keeps a partial file from
 * ever being read as a result.
 */
internal fun writeBenchJson(dir: File, json: String) {
    val file = AtomicFile(File(dir, "bench.json"))
    try {
        val out = file.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(LAB_LOG_TAG, "bench.json write failed", e)
        }
    } catch (e: IOException) {
        // startWrite failed (no space, IO error) — never fail the bench on it.
        Log.w(LAB_LOG_TAG, "bench.json write failed", e)
    }
}

/**
 * The bench result label (`bench-result`): iOS's `benchLabel` — a
 * left-aligned, vertically centred 10 pt monospace block at (12, 120) pt
 * from the **window top-left** (no insets), width = window − 24, height
 * 80, soft-wrapping and ellipsizing what does not fit, `UIColor.label`
 * colour (black light / white dark), no background. Composed when the
 * bench starts (empty) and filled when it ends; Compose exposes the full
 * JSON as the node's text even when the drawn text is ellipsized, so
 * UiAutomator reads it through `By.res("bench-result")`.
 */
@Composable
fun BenchResultLabel(json: String, modifier: Modifier = Modifier) {
    val typeScale = LocalTypeScale.current
    val dark = LocalIsDark.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .absoluteOffset(x = 12.dp, y = 120.dp)
                .size(maxWidth - 24.dp, 80.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                json,
                Modifier.testTag("bench-result"),
                style = TextStyle(
                    // The system monospace font, like iOS's debug instrument.
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = typeScale.fixed(10f),
                    color = if (dark) Color.White else Color.Black,
                ),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------ previews

@Preview
@Composable
private fun BenchResultLabelLightPreview() {
    BenchResultLabelPreviewBody(Appearance.Light)
}

@Preview
@Composable
private fun BenchResultLabelDarkPreview() {
    BenchResultLabelPreviewBody(Appearance.Dark)
}

@Composable
private fun BenchResultLabelPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            BenchResultLabel(FIXTURE_JSON)
        }
    }
}
