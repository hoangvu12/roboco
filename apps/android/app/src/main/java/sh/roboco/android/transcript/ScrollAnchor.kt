package sh.roboco.android.transcript

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import uniffi.roboco_core.LayoutFrame

/**
 * The row under the header's bottom edge and the offset's distance from
 * its top (iOS TLV:301-307). Captured before a frame swap while not
 * following, restored after the runway height — the reading position
 * survives appends, prepends, fold/tool growth and re-wraps.
 */
internal data class ScrollAnchor(val key: ULong, val delta: Float)

/** One row's identity and top y (dp) — the two facts anchoring needs. */
internal data class AnchorRow(val key: ULong, val y: Float)

/**
 * The two frame lookups anchoring needs: [LayoutFrame] in production, a
 * list in tests. Pure queries over immutable frame data, so JVM unit
 * tests can run with fake rows and never load the native library.
 */
internal interface AnchorRows {

    /** Row containing content y, clamped to the last row (Rust `index_at`); null when there are no rows. */
    fun rowAt(y: Float): AnchorRow?

    /** y of the row with [key] in this frame, or null when it is gone. */
    fun yOf(key: ULong): Float?
}

/** Adapts an applied [LayoutFrame] to [AnchorRows] — pure forwarding. */
internal fun LayoutFrame.anchorRows(): AnchorRows = object : AnchorRows {
    override fun rowAt(y: Float): AnchorRow? =
        indexAt(y)?.let { placement(it) }?.let { AnchorRow(it.key, it.y) }
    override fun yOf(key: ULong): Float? = indexOf(key)?.let { placement(it) }?.y
}

/**
 * The capture/restore math of ticket 130 (iOS
 * `TranscriptListView.apply`, TLV:301-330): keep the row under the
 * header's bottom edge at the same screen position across frames while
 * not following. Pure math over [TranscriptViewport] and [AnchorRows] —
 * the caller ([TranscriptState.apply]) owns the ordering (capture before
 * the swap, restore after 68's `onFrameApplied` and inside the same
 * main-thread pass). Never touches `following`, the spring or a fling.
 */
internal object ScrollAnchoring {

    /** iOS TLV:326: smaller corrections are ignored. */
    const val DEAD_BAND = 0.5f

    /**
     * A1–A3. Call before the new frame is swapped in: `following` must
     * be read before 69's runway height (a queued promotion can flip it
     * to true, and then there is no anchor to apply). The probe is
     * `offset + topInset` — the content y at the header's bottom edge —
     * clamped at 0, exactly iOS's `contentOffset.y + adjustedContentInset.top`.
     */
    fun capture(viewport: TranscriptViewport, old: AnchorRows?): ScrollAnchor? {
        if (viewport.following || old == null) return null
        val row = old.rowAt(max(0f, viewport.offset + viewport.topInset)) ?: return null
        return ScrollAnchor(row.key, viewport.offset - row.y)
    }

    /**
     * A5–A7. Call after `onFrameApplied` returned `needsAnchor` (not
     * first, still not following). Single-row lookup, no fallback — a
     * row that disappeared leaves everything alone. The move is a plain
     * `setOffset` write (state + report), clamped to the range spanned
     * by the current offset and the legal bounds: the
     * momentum-preserving equivalent of iOS's `bounds.origin.y` write,
     * which never cancels a running fling. The clamp includes the
     * current offset (ticket 134's widening of A7): the rubber band and
     * its bounce keep the offset past a bound, and an anchor shift must
     * not cut the band — moving back into range stays allowed, moving
     * further out is still refused (68's R14).
     *
     * Returns whether a correction was written (true even when the clamp
     * lands on the current offset, as in the ticket's clamp example).
     */
    fun restore(viewport: TranscriptViewport, anchor: ScrollAnchor, new: AnchorRows): Boolean {
        val y = new.yOf(anchor.key) ?: return false
        val target = y + anchor.delta
        if (abs(target - viewport.offset) <= DEAD_BAND) return false
        viewport.setOffset(
            target.coerceIn(
                min(viewport.offset, viewport.minOffset),
                max(viewport.offset, viewport.maxOffset),
            ),
        )
        return true
    }
}
