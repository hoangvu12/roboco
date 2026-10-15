package sh.roboco.android.core

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.MainActivity
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.debugLineStarts

/**
 * The shared text engine (Robolectric + the real host core through JNA,
 * as `CoreStartupTest`/`CoreLoadTest` set up): one `TextSystem` instance
 * across concurrent threads, a Rust line-break pass that proves the Sans
 * face is registered, a CJK mono run that goes through the platform
 * measurer, and creation off the main thread after a normal app start.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TextEngineTest {

    @Before
    fun init() {
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        // Build the engine off the main thread before any test reads it:
        // `shared` must never be created on the Robolectric main looper, or
        // `builtOnMain` would flip and poison the startup assertion below
        // (the prewarm daemon races this, but a race is not a guarantee).
        runBlocking { TextEngine.await() }
    }

    @Test
    fun sharedIsOneInstanceFromTenConcurrentThreads() {
        val seen = Collections.synchronizedList(ArrayList<TextSystem>())
        val threads = (1..10).map {
            Thread {
                seen.add(TextEngine.shared)
                seen.add(TextEngine.shared)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(20, seen.size)
        assertEquals(1, seen.toSet().size)
    }

    @Test
    fun awaitReturnsTheSharedInstance() {
        val engine = runBlocking { TextEngine.await() }
        assertSame(TextEngine.shared, engine)
        assertTrue(TextEngine.isReady)
    }

    @Test
    fun debugLineStartsWrapsTheFixtureMarkdown() {
        // A long paragraph at 16.5 pt in a 200 pt column: at least 2 line
        // starts proves the Sans face was registered — without it the
        // layout worker never runs a pass (`mob:layout/mod.rs:561`).
        val starts = debugLineStarts(
            TextEngine.shared,
            FaceRole.SANS,
            16.5f,
            200f,
            uniffi.roboco_core.layoutFixtureMarkdown(),
        )
        assertTrue("expected at least 2 line starts, got ${starts.size}", starts.size >= 2)
        assertEquals(0, starts.first().toInt())
    }

    @Test
    fun monoCjkGoesThroughThePlatformMeasurer() {
        // GeistMono has no CJK glyphs, so Rust asks the platform for the
        // run: the measurer's call counter moves. The memo is process-wide
        // and Robolectric reuses sandboxes, so a CJK string another class
        // already measured ("名称" was) never reaches the measurer (calls 0,
        // r746): use characters no other test sends.
        AndroidMeasurer.Stats.reset()
        val starts = debugLineStarts(TextEngine.shared, FaceRole.MONO, 13.5f, 1000f, "齉龘")
        assertTrue(starts.isNotEmpty())
        assertTrue(
            "measurer calls was ${AndroidMeasurer.Stats.calls.get()}",
            AndroidMeasurer.Stats.calls.get() > 0,
        )
    }

    @Test
    fun builtOffTheMainThreadAfterANormalAppStart() {
        // RobocoApplication.onCreate ran prewarm; await() runs on
        // Dispatchers.Default. Neither builds on the main looper.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            runBlocking { TextEngine.await() }
            assertFalse(TextEngine.builtOnMain)
        } finally {
            scenario.close()
        }
    }
}
