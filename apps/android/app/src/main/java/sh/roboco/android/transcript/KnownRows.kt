package sh.roboco.android.transcript

import uniffi.roboco_core.LayoutFrame

/**
 * The set of row keys ever laid out (ticket 65): the first non-empty frame
 * marks every key known so the first paint shows rows at alpha 1, and a
 * row host claims its key when first composed — a `true` means the key was
 * never seen before, so the row fades in (iOS `knownKeys`,
 * `ios:Transcript/TranscriptListView.swift:300-321`).
 *
 * "First" means the previous frame was `null` **or empty**: after a cleared
 * or re-attached transcript (an empty frame), the next frame's keys are all
 * marked known again, so a re-attach does not re-fade the whole transcript.
 * The set is never cleared; it only grows.
 *
 * Main thread only (written from the frame relay and from composition).
 */
class KnownRows {

    private val keys = HashSet<ULong>()

    /**
     * iOS `layoutRows`'s first-frame marking: when [old] was `null` or
     * empty, every key of [new] is added — an O(n) `placement` walk.
     */
    fun onFrameApplied(old: LayoutFrame?, new: LayoutFrame) {
        // The sequence is lazy: the walk only runs when it is needed.
        onFrameApplied(old?.rowCount() ?: 0u) {
            (0u until new.rowCount()).asSequence().mapNotNull { i -> new.placement(i)?.key }
        }
    }

    /**
     * The pure core, for JVM tests: `oldCount == 0` covers both a null
     * previous frame and an empty one. [newKeys] is invoked only when the
     * marking actually happens.
     */
    internal fun onFrameApplied(oldCount: UInt, newKeys: () -> Sequence<ULong>) {
        if (oldCount != 0u) return
        keys.addAll(newKeys())
    }

    /**
     * Marks [key] known; `true` when it was never seen before (the row
     * starts its fade at alpha 0), `false` when it is already known.
     */
    fun claim(key: ULong): Boolean = keys.add(key)

    /** Test/support hook: bulk marking (the pure core's effect). */
    internal fun markAll(keys: Iterable<ULong>) {
        this.keys.addAll(keys)
    }
}
