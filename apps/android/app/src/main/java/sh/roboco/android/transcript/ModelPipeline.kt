package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowPlacement

/**
 * Where a model build came from (ticket 117): `SYNC` is 65's counted
 * fallback on the main thread, `UPGRADE` is an async streaming upgrade,
 * `PREFETCH` is the directional prefetch. Both background paths run on
 * the pipeline's worker, never on main.
 */
enum class BuildPath { SYNC, UPGRADE, PREFETCH }

/**
 * Test hook for the main-thread `display()` rule: [buildModel] invokes
 * the observer with the path and whether the current thread is main.
 * The rule: `onMain` is `true` only for [BuildPath.SYNC], and the number
 * of `SYNC` calls equals the growth of [TranscriptPerf.syncBuilds].
 * `null` in production.
 */
@VisibleForTesting
object ModelBuildHook {
    /** Called from any thread [buildModel] runs on. */
    @Volatile
    var observer: ((path: BuildPath, onMain: Boolean) -> Unit)? = null
}

/**
 * The **only** place in main sources that calls `LayoutFrame.display`
 * (iOS `model(for:)`, `upgradeAsync` and `prefetch`,
 * `ios:Transcript/TranscriptListView.swift:420-434/445-466/468-501`).
 * [frame] must be the frame that produced [p] — `p.index` is only valid
 * in that frame, and building from another frame's index lays out the
 * wrong row. Ticket 62's [RowModel] constructor touches no fonts, paints
 * or palette, so this is safe on any thread.
 */
internal fun buildModel(frame: LayoutFrame, p: RowPlacement, path: BuildPath): RowModel? {
    ModelBuildHook.observer?.invoke(path, Looper.getMainLooper().isCurrentThread)
    val d = frame.display(p.index) ?: return null
    return RowModel(d)
}

/**
 * The stale-row decision (iOS `TranscriptListView.swift:377-385`, inside
 * `layoutRows`): what to do with a realized row whose painted model no
 * longer matches its placement. `cur*` are the painted model's display
 * fields, `null` before the first build; width compares are exact
 * `Float` equality (both sides come from the same Rust `frame.width()`).
 */
internal enum class ModelAction {
    /** The painted model still matches: keep painting it. */
    KEEP,

    /** Stale but the new key is cached: swap in the cached model. */
    CACHED,

    /** Same row at the same width, new content, uncached: build off main, keep painting the old model. */
    UPGRADE,

    /** Every other stale case: 65's counted synchronous build. */
    SYNC,
}

/** See [ModelAction]. */
internal fun decideModel(
    curKey: ULong?,
    curVersion: ULong?,
    curWidth: Float?,
    key: ULong,
    version: ULong,
    width: Float,
    cached: Boolean,
): ModelAction {
    if (curKey == key && curVersion == version && curWidth == width) return ModelAction.KEEP
    if (cached) return ModelAction.CACHED
    if (curKey == key && curWidth == width) return ModelAction.UPGRADE
    return ModelAction.SYNC
}

/**
 * The prefetch ranges (iOS `:470-475`), `ahead = 1.5 x viewport`:
 * toward the tail, `[bandEnd, bandEnd + ahead)` first and
 * `[bandStart - ahead / 2, bandStart)` second; toward the top, the
 * mirror. A range is dropped when its upper end is `<= 0` (entirely
 * above the content top). Pure — the `viewport <= 0` stop lives in
 * [ModelPipeline.afterLayout].
 */
internal fun prefetchRanges(
    bandStart: Float,
    bandEnd: Float,
    viewport: Float,
    towardTop: Boolean,
): List<Pair<Float, Float>> {
    val ahead = 1.5f * viewport
    val behind = ahead / 2f
    val raw = if (towardTop) {
        listOf(bandStart - ahead to bandStart, bandEnd to bandEnd + behind)
    } else {
        listOf(bandEnd to bandEnd + ahead, bandStart - behind to bandStart)
    }
    return raw.filter { it.second > 0f }
}

/**
 * The pipeline's single worker (iOS's one serial `.userInitiated` queue,
 * `TranscriptListView.swift:23`): one thread named
 * `sh.roboco.transcript.prefetch` at [Process.THREAD_PRIORITY_DISPLAY]
 * (−4 — above default background work, below the boosted UI and render
 * threads). The core thread times out after 10 s idle.
 */
internal fun prefetchExecutor(): ThreadPoolExecutor =
    ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(
            { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); r.run() },
            "sh.roboco.transcript.prefetch",
        ).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

/**
 * The row-model pipeline (ticket 117): the [cache], the async upgrade
 * path ([requestUpgrade]), the directional prefetch ([afterLayout]) and
 * their single worker — the Compose port of iOS `TranscriptListView`'s
 * model machinery. Main thread only: [inflight], [cache], the dedupe
 * memory and [TranscriptPerf] are never touched on the worker; the
 * worker only calls [buildModel] and posts its completion back to main
 * through [postMain]. Completions that arrive after [close] do nothing
 * beyond the inflight and counter steps.
 */
class ModelPipeline internal constructor(
    /** One serial worker; tests inject a gated executor. */
    private val worker: ExecutorService = prefetchExecutor(),
    /** The main-thread hop for completions. */
    private val postMain: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
    /**
     * Called on main after an upgrade landed and was stored; the state
     * applies the version guard (Spec "Async upgrade" step 3.6).
     */
    private val onUpgradeBuilt: (p: RowPlacement, m: RowModel) -> Unit,
) {
    /** The insertion-ordered model cache: 500 entries, 100-at-once eviction. */
    val cache = RowModelCache()

    /**
     * Keys being built (upgrade or prefetch); stops a key from being
     * queued twice (iOS `inflight`, `TranscriptListView.swift:25`).
     */
    internal val inflight = HashSet<ModelKey>()

    /** The last placement pass's offset (dp): the direction probe's reference. */
    private var lastPrefetchOffset = 0f

    /** The dedupe memory (identity compared with `===`). */
    private var lastFrame: LayoutFrame? = null
    private var lastBandStart = Float.NaN
    private var lastViewport = Float.NaN
    private var lastTowardTop = false

    /** Written and read on main only. */
    private var closed = false

    /**
     * iOS `upgradeAsync` (`TranscriptListView.swift:445-466`), the apply
     * guard lifted into [onUpgradeBuilt]: the row keeps painting its old
     * model at the new placement while the build runs, and the result —
     * always stored — reaches the row only if the current frame's
     * placement still carries the built version. The task captures the
     * frame that produced `p` (`p.index` is only valid in that frame).
     * Idempotent while a build for the key is in flight: each layout
     * pass over a still-stale row just re-calls this. May be called from
     * composition; a discarded composition only causes a harmless cached
     * build.
     */
    fun requestUpgrade(frame: LayoutFrame, p: RowPlacement) {
        if (closed) return
        val k = ModelKey(p.key, p.version, frame.width())
        if (!inflight.add(k)) return
        worker.execute {
            val m = buildModel(frame, p, BuildPath.UPGRADE)
            postMain(Runnable {
                // Order per the Spec: free the key, count the build, then
                // store and (guarded) apply. iOS counts on the worker; the
                // port counts here so TranscriptPerf stays main-thread only.
                inflight.remove(k)
                if (m != null) {
                    TranscriptPerf.asyncBuilds++
                    if (!closed) {
                        cache.store(k, m)
                        onUpgradeBuilt(p, m)
                    }
                }
            })
        }
    }

    /**
     * The directional prefetch (iOS `:468-501`), called from the host's
     * placement block after every layout: rows just beyond the realized
     * band, in the direction of travel first, are built off main so they
     * arrive cached. Direction comes from the offset delta between
     * placement passes (moving up iff the offset dropped by more than
     * 0.1 dp — an unmoved offset counts as toward the tail, matching
     * iOS's zero finger velocity). The whole call is deduped on
     * (frame, bandStart, viewport, direction): the placement block runs
     * per scroll pixel but the band only moves in 256 dp steps, and the
     * early return precedes any FFI call. One worker task builds the
     * whole todo list in order and posts **one** main-thread batch;
     * prefetched models are never applied to rows.
     */
    fun afterLayout(frame: LayoutFrame, bandStart: Float, bandEnd: Float, viewport: Float, offset: Float) {
        if (closed) return
        val towardTop = offset < lastPrefetchOffset - DirectionEpsilonDp
        lastPrefetchOffset = offset
        if (frame === lastFrame && bandStart == lastBandStart &&
            viewport == lastViewport && towardTop == lastTowardTop
        ) {
            return
        }
        lastFrame = frame
        lastBandStart = bandStart
        lastViewport = viewport
        lastTowardTop = towardTop
        if (viewport <= 0f) return
        val w = frame.width()
        val todo = ArrayList<Pair<RowPlacement, ModelKey>>()
        for ((a, b) in prefetchRanges(bandStart, bandEnd, viewport, towardTop)) {
            for (p in frame.rowsIn(a, b)) {
                val k = ModelKey(p.key, p.version, w)
                if (cache[k] == null && inflight.add(k)) todo.add(p to k)
            }
        }
        if (todo.isEmpty()) return
        worker.execute {
            val built = ArrayList<Pair<ModelKey, RowModel?>>(todo.size)
            for ((p, k) in todo) built.add(k to buildModel(frame, p, BuildPath.PREFETCH))
            postMain(Runnable {
                if (!closed) {
                    for ((k, m) in built) {
                        inflight.remove(k)
                        if (m != null) cache.store(k, m)
                    }
                }
            })
        }
    }

    /**
     * A memory trim (iOS `didReceiveMemoryWarning`,
     * `TranscriptListView.swift:192-198`): `level` at or above
     * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] clears the cache —
     * a pure cache is the first thing to give back — and resets the
     * prefetch dedupe so the next layout re-prefetches. `RUNNING_MODERATE`
     * (5) does nothing. `inflight` and the realized rows' painted models
     * are not touched: visible rows keep theirs.
     */
    fun trim(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        cache.clear()
        lastFrame = null
    }

    /**
     * Teardown (61's `close` calls this before `engine.shutdown()`):
     * mark closed, stop the worker, drop the cache, the in-flight set
     * and the dedupe memory — no frame is kept alive after teardown.
     */
    fun close() {
        if (closed) return
        closed = true
        worker.shutdownNow()
        inflight.clear()
        cache.clear()
        lastFrame = null
    }

    /** Runs a task behind everything already queued (tests flush with this, then idle main). */
    @VisibleForTesting
    internal fun awaitWorkerIdle() {
        worker.submit(Runnable { }).get()
    }

    private companion object {
        /**
         * The direction threshold (dp): an offset drop bigger than this
         * is "toward the top" (mirrors `reportDistance`'s `movingDown`
         * test, `TranscriptListView.swift:233`).
         */
        const val DirectionEpsilonDp = 0.1f
    }
}
