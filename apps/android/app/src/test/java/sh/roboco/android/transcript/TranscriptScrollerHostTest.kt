package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry

/**
 * The real scrollers inside the real host against the real core (ticket
 * 116; the same JNA setup as `TranscriptHostTest`, `w411dp-h891dp-xhdpi`
 * so d = 2): one code block whose single line is far wider than the
 * screen — its viewport rect, the tap that still reaches 65's observer,
 * the horizontal swipe that scrolls only the scroller (the transcript
 * offset and the host composition count untouched), the offset kept
 * across a new streaming version, and the vertical swipe over the same
 * scroller that scrolls only the transcript.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptScrollerHostTest {

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
        TranscriptPerf.reset()
    }

    @After
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- fixture

    /**
     * The ticket's streaming code row: one fenced Kotlin block whose line
     * is [xs] characters wide, far past the 411 dp viewport, so its body
     * scroller overflows (the card, label and copy button are layer 0 and
     * a row-level widget).
     */
    private fun codeEntry(xs: Int): DebugEntry = DebugEntry(
        "c1",
        false,
        "```kotlin\nfun main() { println(\"" + "x".repeat(xs) + "\") }\n```",
        false,
    )

    // -------------------------------------------------------------- helpers

    /**
     * A fed state: viewport first, then entries — a width-0 frame never
     * builds — waiting for the frame through the relay's Choreographer hop
     * (Robolectric's paused looper, as `TranscriptHostTest` does;
     * `compose.waitForIdle` never runs the main looper).
     */
    private fun fedState(entries: List<DebugEntry>, expectedRows: UInt): TranscriptState {
        val state = TranscriptState()
        state.setViewport(411f, 1f)
        state.engine.setDebugEntries(entries, working = false)
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let { it.width() == 411f && it.rowCount() == expectedRows } != true) {
            check(System.nanoTime() < end) { "no $expectedRows-row frame at width 411 within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
        TranscriptPerf.reset()
        return state
    }

    private fun host(state: TranscriptState, actions: TranscriptActions = TranscriptActions()) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    TranscriptHost(state, actions, Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Drives a new engine frame home with the clock frozen (the
     * `driveClock` of `TranscriptHostTest`): one looper frame at a time
     * until [condition], so the relay applies the frame and the host
     * recomposes, re-measures and re-draws on the advanced frames.
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

    /** Sends new entries and drives the frozen clock until a new frame is applied. */
    private fun resend(state: TranscriptState, entries: List<DebugEntry>, condition: () -> Boolean) {
        compose.mainClock.autoAdvance = false
        state.engine.setDebugEntries(entries, working = false)
        driveClock(condition)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theScrollerIsPlacedTapsScrollsAndKeepsItsOffsetAcrossVersions() {
        val state = fedState(listOf(codeEntry(200)), expectedRows = 1u)
        val taps = AtomicInteger()
        try {
            host(state, TranscriptActions(onTap = { taps.incrementAndGet() }))
            val row = state.realized.values.single()
            val key = row.key
            val s = row.model!!.display.scrollers[0]
            val d = 2f

            // Phase 1: exactly one scroller node, at the display's viewport
            // rect offset by the row's top (px, ±1).
            val nodes = compose.onAllNodesWithTag(ScrollerDefaults.TestTag).fetchSemanticsNodes()
            assertEquals(1, nodes.size)
            val node = nodes[0]
            assertEquals(row.topPx() + s.y * d, node.boundsInRoot.top, 1f)
            assertEquals(s.x * d, node.boundsInRoot.left, 1f)
            assertEquals(s.w * d, node.boundsInRoot.width, 1f)
            assertEquals(s.h * d, node.boundsInRoot.height, 1f)

            // A tap inside the scroller still reaches the host's tap
            // observer: the scroller consumes drags only.
            compose.onAllNodesWithTag(ScrollerDefaults.TestTag)[0].performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals(1, taps.get())

            // A horizontal swipe scrolls only the scroller: the transcript
            // offset, the tap count and the host composition count are all
            // unchanged, and the live offset registry answers.
            val offsetBefore = state.offset
            val compositions = state.hostCompositions
            compose.onAllNodesWithTag(ScrollerDefaults.TestTag)[0].performTouchInput { swipeLeft() }
            compose.waitForIdle()
            val offset = state.realized[key]!!.scrollerOffsetDp(0)
            assertTrue("the scroller did not scroll: $offset", offset > 0f)
            assertEquals(offsetBefore, state.offset, 0f)
            assertEquals("a drag must not fire onTap", 1, taps.get())
            assertEquals("the swipe recomposed the host", compositions, state.hostCompositions)

            // A new version with one more character (same id → same key,
            // new version) keeps the offset: the iOS `configure` snap-back
            // is dropped, and the clamping rides the measure.
            val rev = state.frame!!.revision()
            resend(state, listOf(codeEntry(201))) { state.frame?.revision() != rev }
            assertEquals(offset, state.realized[key]!!.scrollerOffsetDp(0), 0.5f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aVerticalSwipeOnTheScrollerScrollsOnlyTheTranscript() {
        val state = fedState(listOf(codeEntry(200)), expectedRows = 1u)
        try {
            host(state)
            val key = state.realized.values.single().key

            // Give the c1 scroller an offset to protect.
            compose.onAllNodesWithTag(ScrollerDefaults.TestTag)[0].performTouchInput { swipeLeft() }
            compose.waitForIdle()
            val scrollerOffset = state.realized[key]!!.scrollerOffsetDp(0)
            assertTrue("the scroller did not scroll: $scrollerOffset", scrollerOffset > 0f)

            // Phase 2: the tall fixture (65's LongFixture: 120 more rows).
            // Follow released first: a following host would spring to the
            // new tail, c1 would leave the realized band and come back with
            // a fresh scroller at 0 (rows forget their scroll offsets when
            // they leave the band, by design). Not following, the apply
            // keeps c1 anchored at the top; the offset is then pinned to 0
            // so c1 is the top row.
            compose.runOnIdle { state.viewport.releaseFollow() }
            resend(state, listOf(codeEntry(201)) + LongFixture) { state.frame?.rowCount() == 121u }
            compose.runOnIdle { state.viewport.setOffset(0f) }
            compose.waitForIdle()

            // The first scroller node is c1's block — the long fixture's
            // code blocks add more, fitting, scrollers further down.
            val before = state.offset
            compose.onAllNodesWithTag(ScrollerDefaults.TestTag)[0].performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertTrue("the transcript did not scroll: $before → ${state.offset}", state.offset > before)
            assertEquals(
                "the vertical swipe moved the horizontal scroller",
                scrollerOffset,
                state.realized[key]!!.scrollerOffsetDp(0),
                0.5f,
            )
        } finally {
            state.close()
        }
    }
}
