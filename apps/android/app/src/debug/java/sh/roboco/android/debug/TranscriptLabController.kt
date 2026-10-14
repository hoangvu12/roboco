package sh.roboco.android.debug

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * The Transcript Lab's owner of everything stateful (android-ui-parity
 * #135): one [TranscriptState] (ticket 61, created on the main thread by
 * the activity), the [LabStream] over the Rust fixture markdown, the
 * 45 ms streaming ticker and the teardown. The screen composes against
 * this; ticket 136's bench reaches in through [send], [stopStream],
 * [state] and [running].
 *
 * The ticker reproduces `Timer.scheduledTimer(withTimeInterval: 0.045,
 * repeats: true)`: fire times are `start + k × 45 ms`, and if the main
 * thread was late the missed fire times are skipped — never fired in a
 * burst. Unlike the iOS run-loop timer it keeps ticking while the user
 * touches the list (an iOS `Timer` pauses during tracking; streaming
 * under a finger is the case worth testing).
 */
class TranscriptLabController(
    /** The lab's transcript state (ticket 61). `close` is mandatory. */
    val state: TranscriptState,
    /** `layout_fixture_markdown()` by default — the same fixture the iOS lab streams. */
    fixture: String = layoutFixtureMarkdown(),
    /** The activity's `lifecycleScope` (Main.immediate), so the ticker dies with the screen. */
    private val scope: CoroutineScope,
) {
    /** The entry list and stream script; the push hands every change to the engine. */
    val stream = LabStream(fixture, push = { entries, working ->
        if (!state.isClosed) state.engine.setDebugEntries(entries, working)
    })

    /** The streaming ticker, `null` while idle. */
    private var ticker: Job? = null

    /** iOS `composer.running` — the Send/Stop button state. */
    val running: Boolean get() = stream.running

    /** The launch fixture: N turns (plus 137's `images`-mode entries) pushed once with `working = false`. */
    fun seed(turns: Int, extra: List<DebugEntry> = emptyList()) = stream.seed(turns, extra)

    /**
     * `send(_:)` (`TranscriptLabViewController.swift:102-114`):
     * stop any live stream, append the new pair (the push waits for the
     * first tick), glide to the bottom with 68's tail spring, then start
     * the fixed-rate 45 ms ticker. Main thread only.
     */
    fun send(text: String) {
        stopStream()
        stream.begin(text)
        state.viewport.scrollToBottom(animated = true)
        ticker = scope.launch {
            var next = SystemClock.uptimeMillis() + LabScript.TickMillis
            while (isActive) {
                delay((next - SystemClock.uptimeMillis()).coerceAtLeast(0))
                if (!stream.tick()) {
                    ticker = null
                    return@launch
                }
                next += LabScript.TickMillis
                val now = SystemClock.uptimeMillis()
                while (next <= now) next += LabScript.TickMillis // skip missed fires
            }
        }
    }

    /**
     * `stopStream()` (`:124-134`): cancel the ticker, `running` off, and
     * a live entry freezes with one `working = false` push.
     */
    fun stopStream() {
        ticker?.cancel()
        ticker = null
        stream.stop()
    }

    /** Teardown: cancel the ticker, then 61's `shutdown` + `close`. */
    fun close() {
        stopStream()
        state.close()
    }
}
