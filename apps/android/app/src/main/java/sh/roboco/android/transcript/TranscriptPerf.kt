package sh.roboco.android.transcript

import java.util.Locale

/**
 * The transcript's cheap perf counters, the Compose analogue of iOS
 * `TranscriptPerf` (`ios:Debug/Signposts.swift:10-26`). Written on the main
 * thread only (layout, apply, builds); the transcript lab and bench read
 * them (tickets 135/136). The Rust section names are the iOS signposts:
 * `layout-rows` (host measure+place), `build-model-sync` (a synchronous
 * display build) — this object keeps the numbers that a signpost cannot
 * carry across the JNA boundary.
 *
 * Spec of android-ui-parity #117 (this file is created by #65 exactly as
 * #117 defines it — #117 then writes `maxApplyMs` and `asyncBuilds`).
 */
object TranscriptPerf {

    /** Longest host measure+place block (ms). Written by #65. */
    @Volatile var maxLayoutMs = 0.0

    /** Longest frame apply (`TranscriptState.apply`, ms). Written by #117. */
    @Volatile var maxApplyMs = 0.0

    /** Longest synchronous `display()` + `RowModel` build (ms). Written by #65/#117. */
    @Volatile var maxSyncBuildMs = 0.0

    /** Count of synchronous builds (`modelFor`'s fallback). */
    @Volatile var syncBuilds = 0

    /** Count of prefetch-thread builds. Written by #117. */
    @Volatile var asyncBuilds = 0

    /** Count of row hosts first composed for a key (the `RowView` analogue). */
    @Volatile var viewsCreated = 0

    /** Zeroes every counter (before a measured run; the host test uses this). */
    fun reset() {
        maxLayoutMs = 0.0
        maxApplyMs = 0.0
        maxSyncBuildMs = 0.0
        syncBuilds = 0
        asyncBuilds = 0
        viewsCreated = 0
    }

    /**
     * The iOS JSON shape, key order and `Locale.US` formatting
     * (`Signposts.swift:23-26`): the lab prints this into its diagnostics
     * pane, so the keys must stay stable.
     */
    val json: String
        get() = String.format(
            Locale.US,
            "{\"maxLayoutMs\":%.2f,\"maxApplyMs\":%.2f,\"maxSyncBuildMs\":%.2f," +
                "\"syncBuilds\":%d,\"asyncBuilds\":%d,\"viewsCreated\":%d}",
            maxLayoutMs,
            maxApplyMs,
            maxSyncBuildMs,
            syncBuilds,
            asyncBuilds,
            viewsCreated,
        )
}
