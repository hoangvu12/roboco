package sh.roboco.android.transcript

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.core.TextEngine
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * Scroll anchoring (ticket 130) against the real core and the lab fixture
 * entries — the iOS `TranscriptLabViewController` recipe
 * (`upstream/main:apps/ios/Zeron/Debug/TranscriptLabViewController.swift`):
 * 40 turns of the fixture markdown at 400 dp, scrolled up while not
 * following. Appends streaming in below, 20 prepended turns and a re-wrap
 * at a new width or text scale must all leave the row under the header at
 * the same screen y (within 1 dp), with the offset staying put (≤ 0.5 dp)
 * while content only grows below.
 *
 * CI-only: no emulator or device in the worker slots, so this class is
 * written but never run locally. Every mutation and every frame read runs
 * on the main thread (`runOnMainSync`); the layout worker relays its
 * frames on the vsync, so polling runs 20 ms slices for up to 10 s.
 */
@RunWith(AndroidJUnit4::class)
class ScrollAnchorLabTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The lab fixture markdown (`layout_fixture_markdown`): 173 words. */
    private val fixture: String = layoutFixtureMarkdown()

    /** `TranscriptLabViewController.swift:182-186`, verbatim. */
    private val prompts = listOf(
        "How does the layout engine avoid measuring text on the main thread?",
        "Show me the plan again with the code sample and the table.",
        "Can you explain why rows never jump when content streams in above the viewport?",
    )

    private lateinit var state: TranscriptState

    /** The lab's entry list — mutable, like the iOS view controller's. */
    private val entries = ArrayList<DebugEntry>()

    /** The anchored row's screen y (dp) for every applied frame of the running case. */
    private val samples = ArrayList<Float>()

    /** The row under the header's bottom edge for the running case; null while idle. */
    private var anchorKey: ULong? = null

    @Before
    fun setUp() {
        TextEngine.init(instrumentation.targetContext)
        val textSystem = runBlocking { TextEngine.await() }

        // The lab's 40 turns (`TranscriptLabViewController.swift:57-62`).
        for (i in 0 until 40) {
            entries += DebugEntry(id = "u$i", user = true, text = prompts[i % 3], streaming = false)
            entries += DebugEntry(id = "a$i", user = false, text = fixture, streaming = false)
        }

        runOnMain {
            state = TranscriptState(textSystem)
            state.setViewport(400f, 1f)
            state.viewport.setViewportHeight(800f)
            state.viewport.setTopInset(68f)
            state.viewport.setBottomInset(100f)
            state.addFrameListener { _, new -> onFrame(new) }
            state.engine.setDebugEntries(entries, working = false)
        }

        // The first frame with rows: the landing at the tail still works.
        assertTrue("no frame with rows within 10 s", pollUntil { (state.frame?.rowCount() ?: 0u) > 0u })
        runOnMain {
            assertTrue("the first frame lands while following", state.viewport.following)
            assertEquals("the landing sits at maxOffset", state.viewport.maxOffset, state.viewport.offset, 0.5f)
        }
    }

    @After
    fun tearDown() {
        if (::state.isInitialized) runOnMain { state.close() }
    }

    // ------------------------------------------------------------- helpers

    /** Runs [block] on the main thread and returns its result. */
    private fun <T> runOnMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    /** Runs [check] on main every 20 ms until it holds; false after 10 s. */
    private fun pollUntil(check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            if (runOnMain(check)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(20)
        }
    }

    /** Records the anchored row's screen y for an applied frame (the listener, main thread). */
    private fun onFrame(new: LayoutFrame) {
        val key = anchorKey ?: return
        val p = new.indexOf(key)?.let { new.placement(it) } ?: return
        samples += p.y - state.viewport.offset
    }

    /**
     * Anchors the row under the header's bottom edge — the row this
     * ticket keeps in place — and restarts the sample collection. Returns
     * that row's current screen y (dp), the case's baseline. Main thread
     * only: reads the applied frame and the viewport.
     */
    private fun captureAnchor(): Float {
        val vp = state.viewport
        val f = state.frame!!
        val key = f.anchorRows().rowAt(max(0f, vp.offset + vp.topInset))!!.key
        anchorKey = key
        samples.clear()
        return f.placement(f.indexOf(key)!!)!!.y - vp.offset
    }

    /** Asserts every sampled screen y stayed within 1 dp of [baseline]. */
    private fun assertAnchored(baseline: Float) {
        // Snapshot on main: the frame listener (also on main) must never
        // append while the test thread iterates.
        val snapshot = runOnMain { samples.toList() }
        assertTrue("no frames sampled for the case", snapshot.isNotEmpty())
        snapshot.forEachIndexed { index, y ->
            assertTrue(
                "frame $index moved the anchored row by ${y - baseline} dp",
                abs(y - baseline) <= 1f,
            )
        }
    }

    // --------------------------------------------------------------- tests

    @Test
    fun staysPutWhileStreaming() {
        // Scroll to maxOffset − 3000 and anchor the row under the header.
        val offsetAtStart = runOnMain {
            state.viewport.releaseFollow()
            state.viewport.setOffset(state.viewport.maxOffset - 3_000f)
            state.viewport.offset
        }
        val baseline = runOnMain { captureAnchor() }

        // The lab's `send` (`TranscriptLabViewController.swift:102-123`)
        // without its scrollToBottom: a new turn whose assistant entry
        // streams 3 words every 45 ms (iOS randomises 2…5; fixed for
        // determinism), stopped after 3 s or when the words run out.
        val n = entries.size
        entries += DebugEntry(id = "u$n", user = true, text = "Stream the plan again, slowly.", streaming = false)
        entries += DebugEntry(id = "a$n", user = false, text = "", streaming = true)
        val words = fixture.split(" ") // keep empty pieces, like iOS
        val deadline = SystemClock.elapsedRealtime() + 3_000
        var i = 0
        while (i < words.size && SystemClock.elapsedRealtime() < deadline) {
            val next = words.subList(i, minOf(words.size, i + 3)).joinToString(" ")
            i += 3
            val streaming = i < words.size
            val last = entries.last()
            entries[entries.lastIndex] = last.copy(
                text = last.text + (if (last.text.isEmpty()) "" else " ") + next,
                streaming = streaming,
            )
            runOnMain { state.engine.setDebugEntries(entries, working = streaming) }
            Thread.sleep(45)
        }
        Thread.sleep(300) // let the last tick's frame land

        runOnMain {
            assertEquals("the offset stays put while appends stream in below", offsetAtStart, state.viewport.offset, 0.5f)
            assertFalse("following must stay false", state.viewport.following)
        }
        assertAnchored(baseline)
    }

    @Test
    fun aPrependKeepsTheAnchoredRow() {
        val baseline = runOnMain {
            state.viewport.releaseFollow()
            state.viewport.setOffset(state.viewport.maxOffset / 2f)
            captureAnchor()
        }
        val rowsBefore = runOnMain { state.frame!!.rowCount() }

        // 20 turns (ids pu0…pu19 / pa0…pa19) before everything.
        val prefix = ArrayList<DebugEntry>(40)
        for (i in 0 until 20) {
            prefix += DebugEntry(id = "pu$i", user = true, text = prompts[i % 3], streaming = false)
            prefix += DebugEntry(id = "pa$i", user = false, text = fixture, streaming = false)
        }
        entries.addAll(0, prefix)
        runOnMain { state.engine.setDebugEntries(entries, working = false) }

        assertTrue("no larger frame within 10 s", pollUntil { (state.frame?.rowCount() ?: 0u) > rowsBefore })
        Thread.sleep(200) // let the frame settle
        assertAnchored(baseline)
    }

    @Test
    fun aRewrapAtANewWidthOrTextScaleKeepsTheAnchoredRow() {
        // 600 dp wide: every row re-wraps at the new width.
        val wideBaseline = runOnMain {
            state.viewport.releaseFollow()
            state.viewport.setOffset(state.viewport.maxOffset / 2f)
            captureAnchor()
        }
        val revWide = runOnMain { state.frame!!.revision() }
        runOnMain { state.setViewport(600f, 1f) }
        assertTrue("no 600-wide frame within 10 s", pollUntil { (state.frame?.revision() ?: 0uL) > revWide })
        assertEquals(600f, runOnMain { state.frame!!.width() }, 1e-4f)
        assertAnchored(wideBaseline)

        // Text scale 1.3 at 400 dp: every row re-wraps and re-sizes.
        val scaledBaseline = runOnMain { captureAnchor() }
        val revScaled = runOnMain { state.frame!!.revision() }
        runOnMain { state.setViewport(400f, 1.3f) }
        assertTrue("no 1.3-scale frame within 10 s", pollUntil { (state.frame?.revision() ?: 0uL) > revScaled })
        assertEquals(400f, runOnMain { state.frame!!.width() }, 1e-4f)
        assertAnchored(scaledBaseline)
    }
}
