package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry

/**
 * The streaming veil behind the real core and the real host (ticket 118;
 * the same JNA + Compose setup as `TranscriptHostTest`,
 * `w411dp-h891dp-xhdpi` so d = 2): a growing streaming row records its
 * chunk `[5, 11)` unstarted (the new words are never painted opaque
 * first), the chunk's clock starts at the first frame tick after the
 * recording frame and eases to mid-fade ~110 ms later, expiry drops it,
 * reduce motion records nothing, and a row that left the band and came
 * back starts over with a fresh, chunk-less veil.
 *
 * The frozen main clock is driven like `TranscriptHostTest.driveClock`:
 * each step runs the paused main looper one Choreographer frame delay
 * ahead (the relay's vsync hop and 117's upgrade completions are posted
 * there), flushes the apply notifications, then advances one compose
 * frame — the frame whose composition records the chunk and whose draw
 * paints it at alpha 0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VeilHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        // Build the shared engine off the main looper first, as the app
        // does (`TextEngine.await()`).
        runBlocking { TextEngine.await() }
    }

    @After
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- helpers

    /** The streaming tail: one user turn plus one live assistant row. */
    private fun streamEntries(text: String): List<DebugEntry> = listOf(
        DebugEntry("u1", true, "Can you summarize the plan?", false),
        DebugEntry("a1", false, text, true),
    )

    /** A fed state: viewport first (a width-0 frame never builds), then entries. */
    private fun fedState(entries: List<DebugEntry>, working: Boolean): TranscriptState {
        val state = TranscriptState()
        state.setViewport(411f, 1f)
        state.engine.setDebugEntries(entries, working = working)
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let { it.width() == 411f && it.rowCount() > 0u } != true) {
            check(System.nanoTime() < end) { "no frame at width 411 within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
        return state
    }

    private fun host(state: TranscriptState) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Frozen glyph clock: a Working row's trailer would otherwise
                // run the theme's endless withFrameNanos loop, and the
                // auto-advancing clock of the first waitForIdle never idles.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Steps the frozen main clock one frame at a time until [condition]
     * holds (5 s wall): the paused main looper first, then the apply
     * notifications, then one compose frame. The frame that makes the
     * condition true is also the frame whose composition recorded the
     * chunk (drawn, unstarted) — no further frame runs, so the chunk is
     * still [Veil.UNSTARTED] when the caller asserts.
     */
    private fun driveClock(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not met within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
    }

    /** Grows the streaming row's text and waits for its new model to be realized. */
    private fun growTo(state: TranscriptState, text: String) {
        val frameBefore = state.frame
        state.engine.setDebugEntries(streamEntries(text), working = true)
        driveClock {
            val f = state.frame
            f != null && f !== frameBefore &&
                state.realized.values.any { it.model?.display?.text == text }
        }
    }

    private fun streamingRow(state: TranscriptState, text: String): RealizedRow =
        state.realized.values.first { it.model?.display?.text == text }

    // ---------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aStreamingGrowVeilsTheNewChunkOnItsOwnClock() {
        val state = fedState(streamEntries("Hello"), working = true)
        try {
            host(state)
            compose.mainClock.autoAdvance = false

            // The row's first model only becomes the veil's baseline.
            val row = streamingRow(state, "Hello")
            assertTrue(row.veil!!.chunks.isEmpty())

            // v2: same key, "Hello world". The frame relays, 117's async
            // upgrade delivers the new model through a recomposition of
            // this row, and that composition records the chunk — before
            // the new words could ever be painted opaque.
            growTo(state, "Hello world")
            val veil = row.veil!!
            val chunk = veil.chunks.single()
            assertEquals(5, chunk.from)
            assertEquals(11, chunk.to)
            assertEquals(Veil.UNSTARTED, chunk.started)
            assertEquals("unstarted: the new words are not painted opaque first", 0, veil.alphaInt(chunk, 0L))

            // The clock starts at the next frame tick (UIKit's next
            // commit): one frame stamps the chunk.
            compose.mainClock.advanceTimeByFrame()
            assertTrue("the chunk was not stamped by the next frame", chunk.started != Veil.UNSTARTED)
            val stampMs = compose.mainClock.currentTime

            // ~110 ms of virtual time later the chunk is mid-fade.
            compose.mainClock.advanceTimeBy(110)
            val elapsedMs = compose.mainClock.currentTime - stampMs
            assertTrue("elapsed $elapsedMs ms is not ~110", elapsedMs in 90..130)
            val alpha = veil.alphaInt(chunk, chunk.started + elapsedMs * 1_000_000L)
            assertTrue("alpha $alpha is not in [150, 200]", alpha in 150..200)

            // 220 ms after its start the chunk is gone and the row paints
            // fully opaque again.
            compose.mainClock.advanceTimeBy(250)
            assertTrue("the chunk did not expire", veil.chunks.isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun reduceMotionRecordsNoChunk() {
        val state = fedState(streamEntries("Hello"), working = true)
        try {
            host(state)
            compose.mainClock.autoAdvance = false
            ReduceMotion.debugOverride = true

            growTo(state, "Hello world")
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(110)

            val row = streamingRow(state, "Hello world")
            assertTrue("a chunk was recorded under reduce motion", row.veil!!.chunks.isEmpty())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aReRealizedRowStartsWithAFreshVeil() {
        // 60 turns: tall enough that the tail leaves the realized band at
        // offset 0 (the band covers [−768, 1779) dp there).
        val entries = (1..60).flatMap { n ->
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
        val state = TranscriptState()
        try {
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(entries, working = false)
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.width() == 411f && it.rowCount() > 0u } != true) {
                check(System.nanoTime() < end) { "no frame at width 411 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            host(state)
            compose.mainClock.autoAdvance = false

            // To the tail: the last row is realized and its veil records
            // the streamed word.
            state.viewport.releaseFollow()
            state.viewport.setOffset(state.viewport.maxOffset)
            val lastKey = state.frame!!.placement(state.frame!!.rowCount() - 1u)!!.key
            driveClock { state.realized[lastKey] != null }
            val rowBefore = state.realized[lastKey]!!

            state.engine.setDebugEntries(
                entries.mapIndexed { i, e -> if (i == entries.lastIndex) e.copy(text = e.text + " Tail.") else e },
                working = true,
            )
            driveClock { rowBefore.veil?.hasChunks == true }

            // Out of the band: the row leaves composition, its veil goes
            // with it, and the row keeps growing while away.
            state.viewport.releaseFollow()
            state.viewport.setOffset(0f)
            driveClock { state.realized[lastKey] == null }
            val frameBefore = state.frame
            state.engine.setDebugEntries(
                entries.mapIndexed { i, e -> if (i == entries.lastIndex) e.copy(text = e.text + " Tail. More.") else e },
                working = true,
            )
            driveClock { state.frame !== frameBefore }

            // Back at the tail: the row is re-realized with a fresh veil
            // whose first model only becomes the baseline.
            state.viewport.releaseFollow()
            state.viewport.setOffset(state.viewport.maxOffset)
            driveClock { state.realized[lastKey] != null }
            val rowAfter = state.realized[lastKey]!!
            assertNotSame("the row was not re-realized", rowBefore, rowAfter)
            assertTrue("a re-realized row must start with no chunks", rowAfter.veil!!.chunks.isEmpty())
        } finally {
            state.close()
        }
    }
}
