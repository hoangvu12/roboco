package sh.roboco.android.transcript

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
