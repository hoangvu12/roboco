package sh.roboco.android.transcript

import android.icu.text.BreakIterator
import sh.roboco.android.core.FontChain

/**
 * Mono double-width clusters — the painter's half of ticket 60's measurer
 * rule. In Geist Mono runs a grapheme cluster whose first code point is
 * double-width occupies exactly two `"0"` cells (the width the measurer
 * reported to Rust, `pr621:apps/android/…/TranscriptListView.kt:568-574`
 * `drawMonoWide`), so code blocks and tables with CJK stay column-aligned
 * at the positions Rust laid out. [isDoubleWidth] delegates to
 * [FontChain.isDoubleWidth] — the measurer's own predicate — so the two
 * halves can never disagree.
 *
 * #621 ran a `BreakIterator` and allocated a `substring` per run, per
 * stretch and per cluster on every frame. [plan] keeps the geometry and
 * removes both: it runs once per (row model, run) — [RowModel.widePlan]
 * caches the result — and the painter slices the row string by index.
 */
internal object MonoWide {

    /** Shared "no double-width cluster" plan. Never mutate. */
    val NONE = IntArray(0)

    /**
     * [FontChain.isDoubleWidth] verbatim: East Asian wide minus the two
     * code points Geist Mono covers at a 1-cell (600/1000 em) advance
     * (U+3003 `〃`, U+301C `〜`). Rust shapes covered text itself
     * (`crates/text/src/font.rs` `covers`), so it lays those two out at one
     * cell and never asks the host measurer; the painter must not double
     * them or every later glyph on the line drifts one cell past its Rust
     * position. `MonoCoverageTest` pins the pair against the font files.
     */
    fun isDoubleWidth(cp: Int): Boolean = FontChain.isDoubleWidth(cp)

    private val breaks = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

    /**
     * The double-width clusters of `text[start, end)` as packed absolute
     * UTF-16 pairs `[s0, e0, s1, e1, …]`, or [NONE] when there are none.
     * Absolute (not run-relative) because the painter slices the row
     * string. Allocates — call once per (row model, run), never per frame.
     * The iterator is thread-local like the measurer's, so this is safe
     * from any thread; the returned array belongs to the caller.
     */
    fun plan(text: String, start: Int, end: Int): IntArray {
        // Cheap scan first: most mono runs have no wide code point at all.
        var i = start
        var any = false
        while (i < end) {
            val cp = text.codePointAt(i)
            if (isDoubleWidth(cp)) {
                any = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!any) return NONE
        val it = breaks.get()!!
        it.setText(text)
        val out = IntArray(2 * (end - start))
        var n = 0
        var s = start
        while (s < end) {
            var e = it.following(s)
            if (e == BreakIterator.DONE || e > end) e = end
            if (isDoubleWidth(text.codePointAt(s))) {
                out[n++] = s
                out[n++] = e
            }
            s = e
        }
        return if (n == 0) NONE else out.copyOf(n)
    }
}
