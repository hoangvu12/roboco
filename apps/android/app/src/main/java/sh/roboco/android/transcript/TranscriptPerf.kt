package sh.roboco.android.transcript

import java.util.Locale

/**
 * Main-thread cost counters for the transcript (read by the lab bench,
 * ticket 136). Main thread only — layout, apply and builds all happen
 * there; the numbers a signpost cannot carry across the JNA boundary.
 * The Rust section names are the iOS signposts: `layout-rows` (host
 * measure+place, #65), `build-model-sync` (a synchronous display build,
 * #65/#117) and `apply-frame` (#117).
 */
object TranscriptPerf {

    /** Longest host measure+place block (ms). Written by #65. */
    var maxLayoutMs = 0.0

    /** Longest frame apply (`TranscriptState.apply`, ms). Written by #117. */
    var maxApplyMs = 0.0

    /** Longest synchronous `display()` + `RowModel` build (ms). Written by #65/#117. */
    var maxSyncBuildMs = 0.0

    /** Count of synchronous builds (`modelFor`'s fallback). */
    var syncBuilds = 0

    /** Count of completed async upgrade builds. Written by #117. */
    var asyncBuilds = 0

    /** Count of row hosts first composed for a key (the `RowView` analogue). */
    var viewsCreated = 0

    /** Zeroes every counter (before a measured run; the bench uses this). */
    fun reset() {
        maxLayoutMs = 0.0
        maxApplyMs = 0.0
        maxSyncBuildMs = 0.0
        syncBuilds = 0
        asyncBuilds = 0
        viewsCreated = 0
    }

    /**
     * The iOS JSON shape, key order and `%.2f` / `%d` format
     * (`ios:Debug/Signposts.swift:23-26`): the lab prints this into its
     * diagnostics pane, so the keys must stay stable. `Locale.US`, so a
     * German-locale device does not write `0,00`.
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
