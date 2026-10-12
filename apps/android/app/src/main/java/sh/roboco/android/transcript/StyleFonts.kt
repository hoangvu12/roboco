package sh.roboco.android.transcript

import android.graphics.Paint
import android.util.Log
import sh.roboco.android.core.FontChain
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.StyleDesc

/**
 * style id → [StyleDesc] (data half, ticket 61) and style id → [Paint]
 * (paint side, ticket 62) for one transcript (ids are per layout view, so
 * one instance belongs to exactly one [TranscriptState] and is never
 * shared; iOS `StyleFonts`, `ios:Transcript/RowModel.swift:205-230`).
 *
 * The data half is thread-safe: [update] runs on the main thread from
 * `TranscriptState.apply` (the Rust style table is append-only per view,
 * so [update] only adds ids it has not seen and [count] is the number of
 * known ids) and [desc] is lock-protected. The paint half caches one
 * [Paint] per id, in px for the current density, its colour mutated while
 * painting: it is drawing (main) thread only — [useDensity]/[get] never
 * run off main and need no lock, and [RowModel] never touches this class
 * (Android draws straight from the row string, so nothing per run is
 * pre-built here, unlike iOS's CTLines). A style id the data half has
 * not synced yet means a row was drawn before the frame that introduced
 * it was applied: that is a bug, logged once per id.
 */
class StyleFonts(
    /**
     * Ticket 60's shared recipe (typeface, `textSize`, feature settings,
     * letter spacing). The default is [FontChain.applyTo] in lambda form
     * so its default `typeface` argument — the chain `Fonts.load(context)`
     * built, the same one the measurer uses — is the production source;
     * tests may inject a different typeface source.
     */
    private val configure: (Paint, FaceRole, Float, Boolean) -> Unit =
        { paint, role, size, ligatures -> FontChain.applyTo(paint, role, size, ligatures) },
) {
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

    // ---- paint side: ticket 62, drawing (main) thread only --------------

    /** One configured [Paint] plus the parts of its [StyleDesc] that built it. */
    class Entry(val paint: Paint, val face: FaceRole, val sizePt: Float, val ligatures: Boolean)

    // Rust style ids are dense from 0 (`StyleId(self.styles.len() as u16)`,
    // crates/text/src/font.rs:289), so an array indexed by id avoids boxing
    // a UShort key per run per draw.
    private var entries = arrayOfNulls<Entry>(64)
    private val warned = HashSet<Int>()

    /** Density the cached paints were built for (px per dp); 0 until the first draw. */
    var density = 0f
        private set

    /**
     * First statement of every `RowModel.draw`. Drops every paint when the
     * density changed (Android paints in px; #609's count-only sync never
     * rebuilt on a density change alone); otherwise one float compare, so
     * hosts need no density plumbing of their own.
     */
    fun useDensity(d: Float) {
        require(d > 0f) { "density must be > 0, was $d" }
        if (d != density) {
            entries.fill(null)
            density = d
        }
    }

    /**
     * The [Entry] for [id] at the current density, built lazily from
     * [desc] and then cached (entries are never replaced at one density,
     * as on iOS — the table is append-only). Null, logged once per id,
     * when the data half has not synced that id yet. The paint's colour is
     * mutated by the painters between calls.
     */
    operator fun get(id: UShort): Entry? {
        val i = id.toInt()
        if (i < entries.size) entries[i]?.let { return it }
        check(density > 0f) { "StyleFonts.useDensity(density) must run before get(id)" }
        val s = desc(id)
        if (s == null) {
            if (warned.add(i)) Log.w(TAG, "no style $i (count=$count)")
            return null
        }
        if (i >= entries.size) entries = entries.copyOf(maxOf(i + 1, entries.size * 2))
        return Entry(newPaint(s), s.face, s.size, s.ligatures).also { entries[i] = it }
    }

    /** Ticket 60's recipe at `size × density` px, unhinted and linear. */
    private fun newPaint(s: StyleDesc) =
        Paint(FontChain.TEXT_PAINT_FLAGS).apply {
            hinting = Paint.HINTING_OFF
            configure(this, s.face, s.size * density, s.ligatures)
        }

    private companion object {
        const val TAG = "RobocoTranscript"
    }
}
