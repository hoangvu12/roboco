package sh.roboco.android.transcript

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.Density
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.transcript.widgets.ToolRailMemory
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LayoutListener
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * A frame with a fixed revision and style list. Counts [styles] calls so
 * the style-table sync can prove it only copies the table when the count
 * moved. `rows`/`height` back 68's apply calls (`rowCount`, `totalHeight`)
 * — `NoHandle` fakes must override them or the FFI layer throws.
 */
private class FakeFrame(
    private val rev: ULong,
    private val styleList: List<StyleDesc>,
    private val rows: UInt = 0u,
    private val height: Float = 0f,
) : LayoutFrame(NoHandle) {
    var styleCalls = 0
        private set

    override fun revision() = rev

    override fun styleCount() = styleList.size.toUInt()

    override fun styles(): List<StyleDesc> {
        styleCalls++
        return styleList
    }

    override fun rowCount() = rows

    override fun totalHeight() = height

    // 65's knownRows listener walks every row's placement on a frame with
    // rows; the NoHandle binding would throw. No placements: no known keys.
    override fun placement(index: UInt): RowPlacement? = null
}

/** Records the engine calls [TranscriptState] makes; `frame()` serves [next]. */
private class FakeEngine : TranscriptView(NoHandle) {
    val setViewports = ArrayList<Pair<Float, Float>>()
    val calls = ArrayList<String>()
    var next: LayoutFrame? = null
    var frameCalls = 0
        private set

    override fun setViewport(width: Float, textScale: Float) {
        setViewports.add(width to textScale)
    }

    override fun frame(): LayoutFrame {
        frameCalls++
        return next ?: error("no frame staged")
    }

    override fun shutdown() {
        calls.add("shutdown")
    }

    override fun close() {
        calls.add("close")
    }
}

/** One [TranscriptState] over a [FakeEngine] and a manual poster, with the relay captured for direct hops. */
private class Harness {
    val engine = FakeEngine()
    val poster = ManualPoster()
    var relay: LayoutListener? = null
    val state = TranscriptState(TextSystem(NoHandle), poster) { _, r ->
        relay = r
        engine
    }
}

/**
 * The state's data behaviours with a fake engine: viewport dedup, the
 * style-table sync, the duplicate-revision skip, listener ordering and
 * removal, the main-thread construction check, snapshot state for
 * [TranscriptState.frame] and `uploadProgress`, and a safe, idempotent
 * close. Runs under Robolectric: the production constructor checks the
 * main looper, and [ManualPoster] references `Choreographer`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranscriptStateTest {

    private fun style(id: Int, face: FaceRole = FaceRole.SANS, size: Float = 16f): StyleDesc =
        StyleDesc(id.toUShort(), face, size, false)

    @Test
    fun setViewportOnlyCallsTheEngineWhenWidthOrScaleChanged() {
        val h = Harness()
        val state = h.state

        // Initial state is (0f, 1f): identical values make no engine call.
        state.setViewport(0f, 1f)
        assertTrue(h.engine.setViewports.isEmpty())

        state.setViewport(390f, 1f)
        state.setViewport(390f, 1f) // exact-equal duplicate
        assertEquals(listOf(390f to 1f), h.engine.setViewports)

        state.setViewport(390f, 1.15f) // the scale moved
        state.setViewport(412f, 1.15f) // the width moved
        assertEquals(
            listOf(390f to 1f, 390f to 1.15f, 412f to 1.15f),
            h.engine.setViewports,
        )
        assertEquals(412f, state.viewportWidthDp, 1e-6f)
        assertEquals(1.15f, state.textScale, 1e-6f)
        assertFalse(state.isClosed)
    }

    @Test
    fun fontsSyncFromAppliedFramesAndKeepStoredInstances() {
        val h = Harness()
        val state = h.state

        // Before any frame: empty table.
        assertEquals(0, state.fonts.count)
        assertNull(state.fonts.desc(style(1).id))

        val five = listOf(
            style(1),
            style(2, FaceRole.SANS_SEMIBOLD),
            style(3, FaceRole.MONO, 13.5f),
            style(4, FaceRole.SANS_ITALIC, 14f),
            style(5, FaceRole.SANS_BOLD, 17f),
        )
        val f1 = FakeFrame(1uL, five)
        state.apply(f1)
        assertEquals(5, state.fonts.count)
        five.forEach { assertSame(it, state.fonts.desc(it.id)) }

        // A newer frame with the same five styles: styles() is not fetched again.
        val f2 = FakeFrame(2uL, five)
        state.apply(f2)
        assertEquals(1, f1.styleCalls)
        assertEquals(0, f2.styleCalls)

        // Seven styles (the five plus two): one more fetch, the first five
        // keep their stored instances, count reaches 7.
        val seven = five + listOf(style(6, FaceRole.MONO, 13.5f), style(7, FaceRole.SANS, 12f))
        val f3 = FakeFrame(3uL, seven)
        state.apply(f3)
        assertEquals(1, f3.styleCalls)
        assertEquals(7, state.fonts.count)
        five.forEach { assertSame(it, state.fonts.desc(it.id)) }
        assertSame(seven[5], state.fonts.desc(style(6).id))
    }

    @Test
    fun anEqualRevisionIsSkippedEntirely() {
        val h = Harness()
        val state = h.state
        val f1 = FakeFrame(1uL, emptyList())
        state.apply(f1)
        val heard = AtomicInteger()
        state.addFrameListener { _, _ -> heard.incrementAndGet() }

        // A new wrapper instance with the same revision: the same frame.
        val duplicate = FakeFrame(1uL, emptyList())
        state.apply(duplicate)

        assertSame("the old wrapper instance must stay", f1, state.frame)
        assertEquals(0, duplicate.styleCalls)
        assertEquals(0, heard.get())
    }

    @Test
    fun listenersHearOldAndNewWithTheFrameAlreadyCurrent() {
        val h = Harness()
        val state = h.state
        val f1 = FakeFrame(1uL, emptyList())
        val f2 = FakeFrame(2uL, emptyList())

        val calls = ArrayList<Pair<LayoutFrame?, LayoutFrame>>()
        val remove = state.addFrameListener { old, new ->
            assertSame("frame must already be current when the listener runs", new, state.frame)
            calls.add(old to new)
        }
        state.apply(f1)
        state.apply(f2)
        assertEquals(2, calls.size)
        assertNull(calls[0].first)
        assertSame(f1, calls[0].second)
        assertSame(f1, calls[1].first)
        assertSame(f2, calls[1].second)

        // The remover stops further calls.
        remove()
        state.apply(FakeFrame(3uL, emptyList()))
        assertEquals(2, calls.size)
    }

    @Test
    fun listenersRunInRegistrationOrderAndMayRemoveThemselves() {
        val h = Harness()
        val state = h.state
        val order = ArrayList<Int>()
        state.addFrameListener { _, _ -> order.add(1) }
        state.addFrameListener { _, _ -> order.add(2) }
        state.apply(FakeFrame(1uL, emptyList()))
        assertEquals(listOf(1, 2), order)

        // Removing itself inside onFrame must not break the iteration.
        val seen = AtomicInteger()
        var remover: (() -> Unit)? = null
        remover = state.addFrameListener { _, _ ->
            seen.incrementAndGet()
            remover?.invoke()
        }
        state.apply(FakeFrame(2uL, emptyList()))
        state.apply(FakeFrame(3uL, emptyList()))
        assertEquals(1, seen.get())
        // The two ordered listeners kept running across all three applies.
        assertEquals(6, order.size)
    }

    @Test
    fun relayedHopsPullTheNewestFrameAndSkipDuplicates() {
        val h = Harness()
        val state = h.state
        val relay = h.relay!!

        val f1 = FakeFrame(1uL, emptyList())
        h.engine.next = f1
        relay.frameReady(1uL)
        h.poster.doFrame()
        assertSame(f1, state.frame)
        assertEquals(1, h.engine.frameCalls)

        val f2 = FakeFrame(2uL, emptyList())
        h.engine.next = f2
        relay.frameReady(2uL)
        h.poster.doFrame()
        assertSame(f2, state.frame)
        assertEquals(2, h.engine.frameCalls)

        // A duplicate hop pulls a same-revision frame: engine.frame() is
        // called, but nothing is applied.
        h.engine.next = FakeFrame(2uL, emptyList())
        val heard = AtomicInteger()
        state.addFrameListener { _, _ -> heard.incrementAndGet() }
        relay.frameReady(2uL)
        h.poster.doFrame()
        assertSame(f2, state.frame)
        assertEquals(3, h.engine.frameCalls)
        assertEquals(0, heard.get())
    }

    /**
     * Round 51 changed the contract this test pinned. It used to require the
     * production constructor to throw off the main thread, but composition
     * can legitimately build the state off main (CI 88b067d0: compose-ui-test
     * recomposed the session route on a Dispatchers.IO worker), so that throw
     * crashed a real flow. The constructor now resolves the MAIN looper's
     * Choreographer from any thread; this test pins exactly that resolution.
     * It no longer constructs the state off main: that would create the
     * native engine from a fake TextSystem.
     */
    @Test
    fun theMainChoreographerResolvesToTheMainLoopersInstanceFromAnyThread() {
        // On main: the main looper's own instance. Touching Compose's main
        // dispatcher here initialises it on main, so the worker below never
        // takes its runBlocking(Dispatchers.Main) path while main is parked
        // in join().
        val onMain = TranscriptState.mainChoreographer()
        assertSame(Choreographer.getInstance(), onMain)
        assertSame(onMain, (AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher).choreographer)

        val fromWorker = AtomicReference<Any?>()
        val thread = Thread {
            fromWorker.set(
                try {
                    TranscriptState.mainChoreographer()
                } catch (failure: Throwable) {
                    failure
                },
            )
        }
        thread.start()
        thread.join()

        assertSame("a worker resolves the main looper's Choreographer, never throws", onMain, fromWorker.get())
    }

    @Test
    fun closeShutsTheEngineDownOnceAndKeepsTheLastFrame() {
        val h = Harness()
        val state = h.state
        val f1 = FakeFrame(1uL, emptyList())
        state.apply(f1)

        // An outstanding relay hop when close() lands.
        h.relay!!.frameReady(1uL)
        val posted = h.poster.posted.single()

        state.close()
        state.close() // idempotent

        assertEquals("shutdown then close, exactly once each", listOf("shutdown", "close"), h.engine.calls)
        assertSame(f1, state.frame)

        // No engine calls of any kind after close.
        state.setViewport(500f, 2f)
        assertTrue(h.engine.setViewports.isEmpty())

        // The cancelled callback runs late: the relay is closed, nothing pulls.
        posted.doFrame(0L)
        assertEquals(0, h.engine.frameCalls)
        assertTrue(state.isClosed)
    }

    @Test
    fun uploadProgressIsSnapshotState() = runTest {
        val state = Harness().state
        val seen = ArrayList<Double?>()
        val job = launch {
            snapshotFlow { state.uploadProgress }.take(4).toList(seen)
        }
        advanceUntilIdle() // the collector starts with the current value: null

        state.uploadProgress = 0.4
        state.uploadProgress = 0.4 // equal write: no emission
        Snapshot.sendApplyNotifications()
        runCurrent()
        state.uploadProgress = 1.0
        Snapshot.sendApplyNotifications()
        runCurrent()
        state.uploadProgress = null
        Snapshot.sendApplyNotifications()
        runCurrent()
        job.join()

        assertEquals(listOf<Double?>(null, 0.4, 1.0, null), seen)
    }

    @Test
    fun frameIsSnapshotState() = runTest {
        val h = Harness()
        val state = h.state
        val seen = ArrayList<ULong?>()
        val job = launch {
            snapshotFlow { state.frame?.revision() }.take(3).toList(seen)
        }
        advanceUntilIdle() // the collector starts with the current value: null

        h.engine.next = FakeFrame(1uL, emptyList())
        h.relay!!.frameReady(1uL)
        h.poster.doFrame()
        Snapshot.sendApplyNotifications()
        runCurrent()
        h.engine.next = FakeFrame(2uL, emptyList())
        h.relay!!.frameReady(2uL)
        h.poster.doFrame()
        Snapshot.sendApplyNotifications()
        runCurrent()
        job.join()

        assertEquals(listOf<ULong?>(null, 1uL, 2uL), seen)
    }

    @Test
    fun bodyTextScaleMatchesTheComposeFontCurvesUnclamped() {
        // The four spec rows (tolerance 1e-4).
        assertEquals(1.0, TranscriptState.bodyTextScale(Density(2.625f, 1.0f)).toDouble(), 1e-4)
        assertEquals(0.85, TranscriptState.bodyTextScale(Density(2.0f, 0.85f)).toDouble(), 1e-4)
        assertEquals(1.229412, TranscriptState.bodyTextScale(Density(2.0f, 1.3f)).toDouble(), 1e-4)
        assertEquals(1.705882, TranscriptState.bodyTextScale(Density(2.0f, 2.0f)).toDouble(), 1e-4)
        // No clamp: #621 would have cut this to 1.6.
        assertTrue(TranscriptState.bodyTextScale(Density(2.0f, 2.0f)) > 1.6f)
    }

    // ------------------------------------------- the tool rail quiet window (121)

    @Test
    fun theToolRailQuietWindowOpensOnAttachAndAfterAnEmptyFrame() {
        ToolRailMemory.resetForTest()
        var now = 1_000L
        ToolRailMemory.clock = { now }
        val h = Harness()
        val state = h.state

        // The first frame: the previous frame was null.
        state.apply(FakeFrame(1uL, emptyList()))
        assertEquals(1_000L + ToolRailMemory.QuietWindowMs, ToolRailMemory.quietUntilMs)

        // A frame whose previous frame was empty (rowCount == 0) re-opens it —
        // groups arriving on that frame never animate either.
        now = 2_000L
        state.apply(FakeFrame(2uL, emptyList(), rows = 2u, height = 100f))
        assertEquals(2_000L + ToolRailMemory.QuietWindowMs, ToolRailMemory.quietUntilMs)

        // A frame after a non-empty frame does not.
        now = 3_000L
        state.apply(FakeFrame(3uL, emptyList(), rows = 3u, height = 150f))
        assertEquals(2_000L + ToolRailMemory.QuietWindowMs, ToolRailMemory.quietUntilMs)
    }
}
