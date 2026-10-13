package sh.roboco.android.transcript

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import uniffi.roboco_core.DebugEntry

/**
 * The row-menu fixture (ticket 128), shared by `TranscriptRowMenuTest`
 * and `TranscriptRowMenuScreenshotTest` — like [HostFixture] it feeds the
 * real core through `setDebugEntries`, but with `working = true` (a
 * trailing Working row) and a two-block reply, so one long press covers
 * Copy, Copy Message and Select Text, and another covers the "no items"
 * rule.
 *
 * Expected rows (5 total): `u1` one user row, `a1` two markdown rows
 * (the two paragraphs), `a2` one markdown row (the fenced code block,
 * with its `CopyCode` widget), and the Working row.
 */
internal val RowMenuFixture = listOf(
    DebugEntry("u1", true, "Ship it.", false),
    DebugEntry("a1", false, "First paragraph.\n\nSecond paragraph.", false),
    DebugEntry("a2", false, "```\nval x = 1\n```", false),
)

/**
 * A [TranscriptState] fed the [RowMenuFixture] and waited to its 5-row
 * frame at width 411 (viewport first, then entries — a width-0 frame
 * never builds). The frame arrives through the relay's Choreographer
 * callback, which the paused main looper schedules one frame delay ahead;
 * `compose.waitUntil` never runs or advances that looper, so the wait
 * steps the looper instead (see `TranscriptHostTest`). The shared body of
 * both row-menu tests' setup.
 */
internal fun fedRowMenuState(): TranscriptState {
    val state = TranscriptState()
    state.setViewport(411f, 1f)
    state.engine.setDebugEntries(RowMenuFixture, working = true)
    val end = System.nanoTime() + 5_000_000_000L
    while (state.frame?.let { it.width() == 411f && it.rowCount() == 5u } != true) {
        check(System.nanoTime() < end) { "no 5-row frame at width 411 within 5 s" }
        shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
    }
    TranscriptPerf.reset()
    return state
}
