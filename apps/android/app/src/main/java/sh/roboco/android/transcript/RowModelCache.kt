package sh.roboco.android.transcript

import androidx.annotation.VisibleForTesting

/**
 * The model cache's key (ticket 117, moved here from 65's
 * `TranscriptState`): identity is (row, version, width) — a row's content
 * is identical in every frame with the same key, so a model built from an
 * **older** frame stays valid for a newer one as long as the key matches
 * (`ios:Transcript/TranscriptListView.swift:172-176`). `width` is
 * `frame.width()` — the width the engine laid out at, not the host's —
 * and `version` changes whenever the row's painted content may change
 * (`mob:layout/mod.rs:111`); a text-scale change rebuilds every core, so
 * no scale field is needed.
 */
data class ModelKey(val key: ULong, val version: ULong, val width: Float)

/**
 * A bounded map with **insertion**-ordered eviction (ticket 117, iOS
 * `store(_:for:)`, `ios:Transcript/TranscriptListView.swift:436-443`):
 * lookups and re-stores never move a key, and when the
 * [limit] + 1-st distinct key is stored the [evictBatch] oldest keys are
 * dropped at once. Not an LRU and not one-at-a-time: realized rows hold
 * their own model, so evicting a key that is on screen costs nothing
 * until its version or width changes. Main thread only.
 */
class InsertionOrderCache<K : Any, V : Any>(
    private val limit: Int = 500,
    private val evictBatch: Int = 100,
) {
    private val map = HashMap<K, V>()
    private val order = ArrayDeque<K>()

    /** A plain lookup; does **not** change the order. */
    operator fun get(k: K): V? = map[k]

    /** `true` when [k] is stored. Does **not** change the order. */
    operator fun contains(k: K): Boolean = map.containsKey(k)

    /** Number of entries. */
    val size: Int get() = map.size

    /**
     * Stores [v] under [k]: a new key is appended to the order, an
     * existing key keeps its place, and once the order grows past
     * [limit] the [evictBatch] oldest keys are removed from both the map
     * and the order (the 501st distinct key leaves 401).
     */
    fun store(k: K, v: V) {
        if (!map.containsKey(k)) order.addLast(k)
        map[k] = v
        if (order.size > limit) repeat(evictBatch) { map.remove(order.removeFirst()) }
    }

    /** Empties map and order. */
    fun clear() {
        map.clear()
        order.clear()
    }

    /** The insertion order, oldest first (tests assert eviction order). */
    @VisibleForTesting
    internal fun keysInOrder(): List<K> = order.toList()
}

/** The row-model cache as used by [ModelPipeline]: 500 entries, 100-at-once eviction. */
typealias RowModelCache = InsertionOrderCache<ModelKey, RowModel>
