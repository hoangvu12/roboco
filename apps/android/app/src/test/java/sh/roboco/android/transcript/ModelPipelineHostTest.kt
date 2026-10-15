package sh.roboco.android.transcript

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.Choreographer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.TranscriptView

/**
 * The model pipeline behind the real core and the real host (ticket 117,
 * the same JNA + Compose setup as `TranscriptHostTest`,
 * `w411dp-h891dp-xhdpi` so d = 2): prefetch removes sync builds over a
 * 120-turn scroll, `display()` never runs on main outside the counted
 * SYNC path, an async upgrade keeps the row painting its old model and a
 * stale build is stored but never applied, trims clear the cache without
 * touching realized rows, and `close()` is safe with a task pending.
 *
 * The worker is a [GatedExecutor]: one real background thread whose
 * upgrade/prefetch tasks (submitted through `execute`) wait for permits
 * from [GatedExecutor.release] while the gate is closed, so a completion
 * can be held back and released one task at a time;
 * [ModelPipeline.awaitWorkerIdle] waits behind the admitted tasks only
 * and can never deadlock on a permit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ModelPipelineHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val gate = GatedExecutor()
    private val hook = BuildRecorder()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        // Build the shared engine off the main looper first, as the app does.
        runBlocking { TextEngine.await() }
        TranscriptPerf.reset()
        ModelBuildHook.observer = hook.observer
    }

    @After
    fun tearDown() {
        ModelBuildHook.observer = null
    }

    // ------------------------------------------------------------- fixtures

    /** 120 turns (240 entries, ~360 rows): tall enough to scroll for 60 steps. */
    private fun turnFixture(turns: Int): List<DebugEntry> =
        (1..turns).flatMap { n ->
            listOf(
                DebugEntry("u$n", true, "Question $n", false),
                DebugEntry(
                    "a$n",
                    false,
                    "Answer $n.\n\n" + "A paragraph of three lines of prose. ".repeat(6),
                    false,
                ),
            )
        }

    /** The streaming tail: one user turn plus one live assistant row. */
    private fun streamFixture(assistantText: String): List<DebugEntry> = listOf(
        DebugEntry("stream-u", true, "Stream", false),
        DebugEntry("stream-a", false, assistantText, true),
    )

    /** A state over the real engine whose pipeline runs on [gate]. */
    private fun makeState(): TranscriptState =
        TranscriptState(
            TextEngine.shared,
            ChoreographerPoster(Choreographer.getInstance()),
            makeModels = { onUpgradeBuilt -> ModelPipeline(worker = gate, onUpgradeBuilt = onUpgradeBuilt) },
            makeEngine = ::TranscriptView,
        )

    // -------------------------------------------------------------- helpers

    /**
     * Steps the paused main looper until [ready] holds for the applied
     * frame (the relay's Choreographer hop lands one frame delay ahead of
     * the looper's clock, so a bare `idle()` never reaches it). 5 s wall.
     */
    private fun waitFrame(state: TranscriptState, ready: (LayoutFrame) -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let(ready) != true) {
            check(System.nanoTime() < end) { "frame not ready within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
    }

    private fun host(state: TranscriptState, actions: TranscriptActions = TranscriptActions()) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                TranscriptHost(state, actions, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    /**
     * The ticket's flush: let Compose lay out, drain the worker (its
     * non-gated sentinel task runs behind everything admitted so far), run
     * the posted completions on the paused main looper, then let Compose
     * recompose.
     */
    private fun flush(state: TranscriptState) {
        // Layout first: a pending placement pass (a scroll, a new frame)
        // queues its prefetch/upgrade only here — draining the worker
        // before it would leave that task's completion unposted.
        compose.waitForIdle()
        state.models.awaitWorkerIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** A programmatic scroll (dp), clamped to the legal range; never starts the spring. */
    private fun TranscriptState.scrollByDp(deltaDp: Float) {
        viewport.setOffset((viewport.offset + deltaDp).coerceIn(viewport.minOffset, viewport.maxOffset))
    }

    private fun userRowsOf(f: LayoutFrame): Int =
        f.rowsIn(0f, Float.MAX_VALUE).count { it.kind == RowKind.USER }

    private fun lastRowOf(f: LayoutFrame): RowPlacement = f.placement(f.rowCount() - 1u)!!

    /**
     * Admits gated tasks one at a time (flushing after each) until [k]'s
     * build has landed; returns how many builds were admitted. Every
     * `setDebugEntries` allocates fresh entries, so the core rebuilds the
     * unchanged user row too (`RowBuilder` reuses an entry's rows only for
     * the same `Arc`) and that row's upgrade queues ahead of the assistant
     * row's — a bare `release(1)` would admit the user row's build only.
     * A permit is fed only while [k] is still in flight, i.e. pending.
     */
    private fun releaseUntilLanded(state: TranscriptState, k: ModelKey): Int {
        var admitted = 0
        while (k in state.models.inflight) {
            check(admitted < 8) { "$k never landed" }
            gate.release(1)
            admitted++
            flush(state)
        }
        return admitted
    }

    // ---------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun prefetchRemovesSyncBuildsWhileScrolling() {
        val state = makeState()
        try {
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(turnFixture(120), working = false)
            waitFrame(state) { it.width() == 411f && it.rowCount() > 0u && userRowsOf(it) == 120 }

            // Put the viewport at the top before composing: the first
            // placement pass then prefetches toward the tail (an unmoved
            // offset counts as toward the tail, like iOS's zero finger
            // velocity), and follow is released first so 68's spring does
            // not pull the offset back.
            state.viewport.releaseFollow()
            state.viewport.setOffset(0f)
            host(state)
            flush(state)
            flush(state)
            val s0 = TranscriptPerf.syncBuilds
            assertTrue("the first composition built no models", s0 > 0)

            // The whole ahead range is cached after the first layout.
            val frame = state.frame!!
            val w = frame.width()
            val ahead = 1.5f * 891f
            val end = bandEnd(bandStart(0f), 891f)
            for (p in frame.rowsIn(end, end + ahead)) {
                assertTrue(
                    "row ${p.key} in the ahead range was not prefetched",
                    ModelKey(p.key, p.version, w) in state.models.cache,
                )
            }

            // 30 x 120 dp toward the tail: the entering rows arrive cached.
            repeat(30) { step ->
                state.scrollByDp(120f)
                flush(state)
                assertEquals("scroll step $step added a sync build", s0, TranscriptPerf.syncBuilds)
            }
            assertEquals(0, TranscriptPerf.asyncBuilds)

            // Back up: after the first upward step the top-first range ran.
            state.scrollByDp(-120f)
            flush(state)
            val offset = state.offset
            val top = bandStart(offset)
            for (p in state.frame!!.rowsIn(top - ahead, top)) {
                assertTrue(
                    "row ${p.key} in the toward-top range was not prefetched",
                    ModelKey(p.key, p.version, w) in state.models.cache,
                )
            }
            repeat(29) { step ->
                state.scrollByDp(-120f)
                flush(state)
                assertEquals("upward scroll step $step added a sync build", s0, TranscriptPerf.syncBuilds)
            }

            // The main-thread display() rule: on main only via SYNC, and
            // the SYNC call count equals the sync-builds counter.
            val records = hook.records
            assertTrue(
                "a non-SYNC build ran on main: " + records.filter { it.second && it.first != BuildPath.SYNC },
                records.all { !it.second || it.first == BuildPath.SYNC },
            )
            val syncCalls = records.count { it.first == BuildPath.SYNC }
            assertEquals(syncCalls, TranscriptPerf.syncBuilds)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun asyncUpgradesKeepTheOldModelAndApplyOnlyTheNewest() {
        val state = makeState()
        try {
            // v1: the row is painted by a counted sync build, open mode.
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(streamFixture("First words."), working = false)
            waitFrame(state) { it.rowCount() > 0u && userRowsOf(it) == 1 }
            host(state)
            flush(state)
            val frame1 = state.frame!!
            val key = lastRowOf(frame1).key
            val v1 = lastRowOf(frame1).version
            val w = frame1.width()
            val row = state.realized[key]!!
            assertEquals(v1, row.model!!.display.version)
            val syncBaseline = TranscriptPerf.syncBuilds
            assertTrue(syncBaseline > 0)

            // v2: the row keeps painting v1 while the gated build runs.
            gate.open = false
            state.engine.setDebugEntries(streamFixture("First words. More words."), working = false)
            waitFrame(state) { f ->
                f.placement(f.rowCount() - 1u)?.let { it.key == key && it.version != v1 } ?: false
            }
            compose.waitForIdle()
            val v2 = lastRowOf(state.frame!!).version
            assertEquals("the row still paints v1 while the build is in flight", v1, row.model!!.display.version)
            assertEquals("the upgrade path never sync-builds", syncBaseline, TranscriptPerf.syncBuilds)
            assertTrue(ModelKey(key, v2, w) in state.models.inflight)

            // The user row's identical rebuild may queue ahead (see
            // releaseUntilLanded): every admitted build is counted once.
            val firstBuilds = releaseUntilLanded(state, ModelKey(key, v2, w))
            assertEquals("the new model was applied", v2, row.model!!.display.version)
            assertEquals(firstBuilds, TranscriptPerf.asyncBuilds)
            assertNotNull("the upgrade was stored", state.models.cache[ModelKey(key, v2, w)])
            val upgrades = hook.records.filter { it.first == BuildPath.UPGRADE }
            assertEquals(firstBuilds, upgrades.size)
            assertTrue("an upgrade build ran on main", upgrades.none { it.second })

            // v3 then v4, both gated: the stale v3 lands first and is
            // stored, not applied; v4 applies.
            state.engine.setDebugEntries(streamFixture("First words. More words. Third."), working = false)
            waitFrame(state) { f ->
                f.placement(f.rowCount() - 1u)?.let { it.key == key && it.version != v2 } ?: false
            }
            compose.waitForIdle()
            val v3 = lastRowOf(state.frame!!).version
            assertTrue(ModelKey(key, v3, w) in state.models.inflight)

            state.engine.setDebugEntries(streamFixture("First words. More words. Third. Fourth."), working = false)
            waitFrame(state) { f ->
                f.placement(f.rowCount() - 1u)?.let { it.key == key && it.version != v3 } ?: false
            }
            compose.waitForIdle()
            val v4 = lastRowOf(state.frame!!).version
            assertTrue("both keys are in flight", ModelKey(key, v4, w) in state.models.inflight)

            // The v3 build lands first: stored, not applied.
            val staleBuilds = releaseUntilLanded(state, ModelKey(key, v3, w))
            assertNotNull("the stale build is still stored", state.models.cache[ModelKey(key, v3, w)])
            assertEquals("a stale build never rolls the row back", v2, row.model!!.display.version)

            // The v4 build lands: the current version, applied.
            val currentBuilds = releaseUntilLanded(state, ModelKey(key, v4, w))
            assertEquals(v4, row.model!!.display.version)
            assertEquals(
                "every admitted upgrade build is counted once",
                firstBuilds + staleBuilds + currentBuilds,
                TranscriptPerf.asyncBuilds,
            )
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun trimsClearTheCacheAndPrefetchRefillsIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val state = makeState()
        var hostVisible by mutableStateOf(true)
        try {
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(turnFixture(120), working = false)
            waitFrame(state) { it.width() == 411f && it.rowCount() > 0u && userRowsOf(it) == 120 }
            state.viewport.releaseFollow()
            state.viewport.setOffset(0f)
            compose.setContent {
                RobocoTheme(Appearance.Light) {
                    if (hostVisible) TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                }
            }
            compose.waitForIdle()
            flush(state)
            assertTrue("the first composition filled the cache", state.models.cache.size > 0)
            val size = state.models.cache.size

            // RUNNING_MODERATE (5) does nothing.
            app.onTrimMemory(5)
            assertEquals(size, state.models.cache.size)

            // RUNNING_LOW (10) clears the pure cache; realized rows keep painting.
            app.onTrimMemory(10)
            assertEquals(0, state.models.cache.size)
            for (row in state.realized.values) {
                assertNotNull("row ${row.key} lost its painted model", row.model)
            }

            // The dedupe reset let prefetch run again on the next placement.
            hook.clear()
            state.scrollByDp(120f)
            flush(state)
            assertTrue("prefetch did not refill the cache", state.models.cache.size > 0)
            assertTrue("no PREFETCH build was recorded", hook.records.any { it.first == BuildPath.PREFETCH })

            // onLowMemory is the same as the highest level.
            app.onLowMemory()
            assertEquals(0, state.models.cache.size)

            // Leaving composition unregisters the callback.
            val deliveries = state.trimDeliveries
            compose.runOnIdle { hostVisible = false }
            compose.waitForIdle()
            app.onTrimMemory(80)
            assertEquals("a trim reached the state after disposal", deliveries, state.trimDeliveries)
        } finally {
            state.close()
        }
    }

    @Test
    fun closeShutsTheWorkerDownAndLateCompletionsDoNothing() {
        val state = makeState()
        try {
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(streamFixture("First words."), working = false)
            waitFrame(state) { it.rowCount() > 0u && userRowsOf(it) == 1 }
            val frame = state.frame!!
            val p = lastRowOf(frame)

            gate.open = false
            state.models.requestUpgrade(frame, p)
            assertTrue(ModelKey(p.key, p.version, frame.width()) in state.models.inflight)

            state.close() // no exception; the worker is shut down
            assertTrue(state.isClosed)

            gate.release(1) // the shut-down executor drops the pending task
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("a late completion touched the cache", 0, state.models.cache.size)
        } finally {
            state.close()
        }
    }

    // --------------------------------------------------------------- tools

    /**
     * The test's stand-in for the pipeline's single-thread worker: one
     * real background thread plus a gate. Tasks submitted through
     * [execute] (every upgrade/prefetch task the pipeline submits) are
     * held in a pending list until a permit from [release] admits each
     * one, or the gate is [open] (open mode skips the gate and flushes
     * the pending list). The [submit] overrides bypass the gate, so
     * [ModelPipeline.awaitWorkerIdle] waits behind the admitted tasks
     * only and can never deadlock on a permit. `shutdownNow` propagates
     * to the real thread; a late [release] on a shut-down executor drops
     * its task instead of crashing.
     */
    private class GatedExecutor : AbstractExecutorService() {
        private val real = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        ) { r -> Thread(r, "roboco-test-model-pipeline").apply { isDaemon = true } }

        private val lock = Any()
        private val pending = ArrayDeque<Runnable>()
        private var permits = 0

        /** Open mode: execute'd tasks skip the gate entirely. */
        var open = true
            set(value) {
                field = value
                if (value) drain()
            }

        override fun execute(command: Runnable) {
            if (open) {
                real.execute(command)
            } else {
                synchronized(lock) { pending.addLast(command) }
                drain()
            }
        }

        /** Feeds [n] permits; each admits exactly one pending task. */
        fun release(n: Int) {
            synchronized(lock) { permits += n }
            drain()
        }

        private fun drain() {
            while (true) {
                val next = synchronized(lock) {
                    when {
                        open && pending.isNotEmpty() -> pending.removeFirst()
                        permits > 0 && pending.isNotEmpty() -> {
                            permits -= 1
                            pending.removeFirst()
                        }
                        else -> null
                    }
                } ?: return
                try {
                    real.execute(next)
                } catch (rejected: RejectedExecutionException) {
                    // shutdownNow already stopped the real executor: the
                    // task is dropped, exactly the late-completion case.
                }
            }
        }

        // submit bypasses the gate: awaitWorkerIdle waits behind admitted
        // tasks, never behind a permit.
        override fun submit(task: Runnable): Future<*> = real.submit(task)

        override fun <T> submit(task: Runnable, result: T): Future<T> = real.submit(task, result)

        override fun <T> submit(task: Callable<T>): Future<T> = real.submit(task)

        override fun shutdown() = real.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = real.shutdownNow()

        override fun isShutdown(): Boolean = real.isShutdown

        override fun isTerminated(): Boolean = real.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            real.awaitTermination(timeout, unit)
    }

    /**
     * Records every [ModelBuildHook] invocation as
     * `(BuildPath, onMain)`; the worker thread and main both call in, so
     * the list is guarded.
     */
    private class BuildRecorder {
        private val list = ArrayList<Pair<BuildPath, Boolean>>()

        val observer: (BuildPath, Boolean) -> Unit = { path, onMain ->
            synchronized(this) { list.add(path to onMain) }
        }

        val records: List<Pair<BuildPath, Boolean>>
            get() = synchronized(this) { list.toList() }

        fun clear() = synchronized(this) { list.clear() }
    }
}
