package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * [TranscriptState] against the real host core (the same JNA setup as
 * `CoreLoadTest`/`TextEngineTest`): the production constructor on the main
 * looper, debug entries flowing through the `roboco-layout` worker as
 * frames relayed on the vsync, the viewport dedup and the append-only
 * style table, and a safe close that keeps the last frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptStateCoreTest {

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        // Build the shared engine off the main looper first, as the app
        // does (`TextEngine.await()`), so the state constructor only reads it.
        runBlocking { TextEngine.await() }
    }

    @Test
    fun theRealCoreDeliversFramesThroughTheRelay() {
        val state = TranscriptState()
        var lastRevision = 0uL
        try {
            state.engine.setDebugEntries(
                listOf(
                    DebugEntry(id = "u1", user = true, text = "hi", streaming = false),
                    DebugEntry(id = "a1", user = false, text = layoutFixtureMarkdown(), streaming = false),
                ),
                working = false,
            )
            state.setViewport(390f, 1f)

            // Robolectric's paused looper: drive the vsync in 20 ms steps.
            idleMainUntil(Duration.ofSeconds(5)) { state.frame != null }
            assertNotNull("no frame within 5 s", state.frame)
            val frame = state.frame!!
            assertTrue("rows: ${frame.rowCount()}", frame.rowCount() >= 2u)
            assertEquals(390f, frame.width(), 1e-6f)
            assertTrue("revision: ${frame.revision()}", frame.revision() >= 1uL)

            val styles = frame.styles()
            assertTrue("styles: ${styles.size}", styles.isNotEmpty())
            assertEquals(styles.size, state.fonts.count)
            val ids = styles.map { it.id }
            assertEquals("style ids must be unique", ids.size, ids.toSet().size)
            ids.forEach { assertNotNull("style ${it.toInt()} missing from the table", state.fonts.desc(it)) }

            // The identical viewport is deduped on the Kotlin side: no
            // pass, no new revision.
            val revision = frame.revision()
            state.setViewport(390f, 1f)
            idleMainFor(Duration.ofMillis(500))
            assertEquals(revision, state.frame?.revision())

            // A text-scale change re-interns every style at its new size:
            // the table only grows.
            state.setViewport(390f, 1.3f)
            idleMainUntil(Duration.ofSeconds(5)) { (state.frame?.revision() ?: 0uL) > revision }
            val rescaled = state.frame!!
            assertTrue("revision ${rescaled.revision()} vs $revision", rescaled.revision() > revision)
            assertTrue(
                "style count ${rescaled.styleCount()} did not grow past ${frame.styleCount()}",
                rescaled.styleCount() > frame.styleCount(),
            )
            assertEquals(rescaled.styleCount().toInt(), state.fonts.count)
            lastRevision = rescaled.revision()
        } finally {
            // Returns without exceptions, even mid-stream.
            state.close()
        }

        // After close: idling the looper throws nothing, and the frame
        // keeps the last revision (a screen animating out still paints).
        assertTrue(state.isClosed)
        val last = state.frame
        assertNotNull(last)
        idleMainFor(Duration.ofMillis(200))
        assertSame(last, state.frame)
        assertEquals(lastRevision, state.frame?.revision())
    }

    /** Runs 20 ms looper slices until [condition] holds or [deadline] passes. */
    private fun idleMainUntil(deadline: Duration, condition: () -> Boolean) {
        val end = System.nanoTime() + deadline.toNanos()
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(SLICE)
        }
    }

    /** Runs 20 ms looper slices for [duration]. */
    private fun idleMainFor(duration: Duration) {
        val end = System.nanoTime() + duration.toNanos()
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(SLICE)
        }
    }

    private companion object {
        val SLICE = Duration.ofMillis(20)
    }
}
