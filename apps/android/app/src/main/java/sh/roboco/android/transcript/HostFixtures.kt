package sh.roboco.android.transcript

import uniffi.roboco_core.DebugEntry

/**
 * The transcript host's fixture (ticket 65), shared by the gallery section
 * and the host tests: one user turn, one assistant turn rich enough to
 * cover a heading, a list, a fenced code block with a scroller and a
 * paragraph link, and a closing user turn. `u1`/`a1`/`u2` are the ids the
 * tests pin; every appended row uses a fresh id (never `a<n>` — `a1` is
 * already here).
 */
val HostFixture = listOf(
    DebugEntry("u1", true, "Can you summarize the plan?", false),
    DebugEntry(
        "a1",
        false,
        "## Plan\n\n1. Port the **layout core**.\n2. Paint rows at Rust coordinates.\n\n" +
            "```kotlin\nval band = floor((offset - 700f) / 256f) * 256f\n```\n\n" +
            "See [the spec](https://example.com/spec).",
        false,
    ),
    DebugEntry("u2", true, "Ship it.", false),
)

/**
 * 60 entries (20 × [HostFixture] with unique ids): tall enough to scroll —
 * 120 rows, `totalHeight()` well past one viewport.
 */
val LongFixture: List<DebugEntry> =
    (1..20).flatMap { n -> HostFixture.map { it.copy(id = "${it.id}_$n") } }

/**
 * The row-motion fixture (ticket 132), shared by the gallery section and
 * `RowMotionHostTest`: a 40-line pasted prompt — over the 8-line fold
 * limit (`mob:layout/rows.rs:137-138`), so `u1` starts collapsed to 6
 * lines with a "Show more" disclosure — the reply that slides down when
 * it expands, and a short follow-up. Three rows; the text is the Rust
 * test's (`mob:layout/tests.rs:146`).
 */
val MotionFixture = listOf(
    DebugEntry("u1", true, (0 until 40).joinToString("\n") { "line $it of a long pasted prompt" }, false),
    DebugEntry("a1", false, "This reply slides down when the prompt above expands.", false),
    DebugEntry("u2", true, "Short follow-up.", false),
)
