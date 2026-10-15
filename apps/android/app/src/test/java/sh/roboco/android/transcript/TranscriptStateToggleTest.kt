package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The ticket 120 toggle handlers ([TranscriptState.toggleRow] /
 * [TranscriptState.toggleDetail], iOS `TranscriptListView.swift:563-572`)
 * over a fake engine, ticket 61's `makeEngine` seam:
 *
 * - `toggleRow(K)` calls `engine.toggle(K)` exactly once;
 * - `toggleDetail(R, D, open)` calls `engine.toggleDetail(R, D, open)`
 *   exactly once, with the painted `open` passed through, never inverted
 *   (the idempotence the Rust worker relies on: two taps before the next
 *   frame land once);
 * - both set `pendingFold` **before** the engine call — the fake engine
 *   reads `state.pendingFold` inside its `toggle`/`toggleDetail`, exactly
 *   where ticket 132's consumer will find the mark — and with the row key
 *   for a detail toggle, never the detail key;
 * - after `close()`, both are no-ops: no engine call, no mark.
 *
 * Robolectric, like `TranscriptStateTest`: the relay references
 * `Choreographer` and `close()` walks the model pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranscriptStateToggleTest {

    /**
     * Records the engine calls, reading [TranscriptState.pendingFold]
     * **inside** each call so the tests see the mark as of the call, the
     * before-engine order the ticket's AC pins.
     */
    private class RecordingEngine : TranscriptView(NoHandle) {
        val toggles = ArrayList<ULong>()
        val detailToggles = ArrayList<Triple<ULong, ULong, Boolean>>()
        var foldAtToggle: ULong? = null
        var foldAtDetail: ULong? = null
        private lateinit var owner: TranscriptState

        fun bind(state: TranscriptState) {
            owner = state
        }

        override fun toggle(key: ULong) {
            toggles += key
            foldAtToggle = owner.pendingFold
        }

        override fun toggleDetail(row: ULong, detail: ULong, open: Boolean) {
            detailToggles += Triple(row, detail, open)
            foldAtDetail = owner.pendingFold
        }

        override fun setViewport(width: Float, textScale: Float) {}

        override fun frame(): LayoutFrame = error("no frame staged")

        override fun shutdown() {}

        override fun close() {}
    }

    private fun harness(): Pair<RecordingEngine, TranscriptState> {
        val engine = RecordingEngine()
        val state = TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> engine }
        engine.bind(state)
        return engine to state
    }

    @Test
    fun toggleRowCallsTheEngineOnceAndMarksTheFoldBeforeTheCall() {
        val (engine, state) = harness()

        state.toggleRow(7uL)

        assertEquals("engine.toggle exactly once", listOf(7uL), engine.toggles)
        assertEquals("pendingFold is set before the engine call", 7uL, engine.foldAtToggle)
        assertEquals(7uL, state.pendingFold)
    }

    @Test
    fun toggleDetailPassesThePaintedOpenAndMarksTheRowFold() {
        val (engine, state) = harness()

        state.toggleDetail(5uL, 9uL, true)

        assertEquals("engine.toggleDetail exactly once", listOf(Triple(5uL, 9uL, true)), engine.detailToggles)
        assertEquals("the fold mark is the row key, not the detail key", 5uL, engine.foldAtDetail)
        assertEquals(5uL, state.pendingFold)

        // `open` is passed as painted, never inverted: a second tap before
        // the next frame sends the same value again.
        state.toggleDetail(5uL, 9uL, true)
        assertEquals(
            listOf(Triple(5uL, 9uL, true), Triple(5uL, 9uL, true)),
            engine.detailToggles,
        )
    }

    @Test
    fun theLatestMarkWins() {
        val (_, state) = harness()

        state.toggleRow(1uL)
        state.toggleDetail(2uL, 3uL, false)

        assertEquals(2uL, state.pendingFold)
    }

    @Test
    fun afterCloseBothAreNoOps() {
        val (engine, state) = harness()
        state.close()

        state.toggleRow(7uL)
        state.toggleDetail(5uL, 9uL, true)

        assertTrue("no toggle after close", engine.toggles.isEmpty())
        assertTrue("no detail toggle after close", engine.detailToggles.isEmpty())
        assertNull("no fold mark after close", state.pendingFold)
    }
}
