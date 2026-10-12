package sh.roboco.android.transcript

import uniffi.roboco_core.StyleDesc

/**
 * style id → [StyleDesc] for one transcript (iOS `StyleFonts`,
 * `ios:Transcript/RowModel.swift:205-230`). Style ids are interned per
 * layout view, so one instance belongs to exactly one [TranscriptState] and
 * is never shared. Thread-safe: row models read it off the main thread
 * (prefetch, ticket 117), while [update] runs on the main thread from
 * `TranscriptState.apply`.
 *
 * The data half of the iOS class only: the Rust style table is append-only
 * per view, so [update] adds ids it has not seen and [count] is the number
 * of known ids. Ticket 62 appends the paint side (`useDensity`/`get`),
 * building each `Paint` lazily from [desc] on the draw thread — the iOS
 * guarantee "every id a drawn row uses is in `fonts`" still holds, because
 * `apply` syncs before the frame becomes `state.frame`.
 */
class StyleFonts {
    private val lock = Any()
    private val descs = HashMap<UShort, StyleDesc>()

    /** The number of known style ids (read off the main thread). */
    @Volatile
    var count: Int = 0
        private set

    /**
     * Adds only ids not seen before (the Rust table is append-only per
     * view; a scale change interns new ids, so the table grows and is never
     * cleared). Main thread, from `TranscriptState.apply` step 4.
     */
    fun update(styles: List<StyleDesc>) = synchronized(lock) {
        for (s in styles) if (s.id !in descs) descs[s.id] = s
        count = descs.size
    }

    /** The stored [StyleDesc] for [id], or `null` if it has not been seen. */
    fun desc(id: UShort): StyleDesc? = synchronized(lock) { descs[id] }
}
