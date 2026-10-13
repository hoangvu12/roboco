package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.RowKind

/**
 * The virtualized host against the real core (ticket 65; the same JNA
 * setup as `TranscriptStateCoreTest`, `w411dp-h891dp-xhdpi` so d = 2):
 * tags and Rust-coordinate placement, the realized band, the
 * no-recomposition-within-a-band-step rule, the 280 ms easeOut new-row
 * fade-in (not gated on reduce motion), the counted sync model fallback,
 * `firstUserBubbleBounds`, taps and swipes through the Final-pass
 * observer, and the light/dark repaint without model rebuilds.
 *
 * Robolectric + Compose: the rule's clock drives the Choreographer, so
 * the relay's vsync hop runs while the clock advances (the fade test
 * freezes it with `autoAdvance = false` and steps frame by frame).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptHostTest {

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

    // ------------------------------------------------------------- helpers

    /**
     * Sets up a fed state (viewport first, then entries — a width-0 frame
     * never builds), waits for the frame, and resets the perf counters so
     * the test counts only what its own composition does.
     *
     * The frame reaches `state.frame` only through the relay's
     * Choreographer callback, which Robolectric's paused looper schedules
     * one frame delay (15 ms) ahead of its own clock. `compose.waitUntil`
     * only advances the compose test clock (a coroutine scheduler) and
     * sleeps — it never runs or advances the main looper — so it timed out
     * every time. Step the looper's clock instead, as
     * `TranscriptStateCoreTest` does.
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
     * Advances the frozen main clock one frame at a time until [condition]
     * holds (5 s wall). Each step first runs the paused main looper one
     * Choreographer frame delay ahead — the relay's Choreographer hop
     * (posted from the layout thread) is scheduled that far ahead of the
     * looper's clock, so a bare `idle()` never reaches it, and
     * `advanceTimeByFrame()` never touches the looper — then sends the apply
     * notification for the `frame` write that hop made (Compose's
     * GlobalSnapshotManager would otherwise forward it from yet another
     * main-looper post), so the next frame recomposes the realized band.
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

    /** All row keys of the state's current frame. */
    private fun frameKeys(state: TranscriptState): Set<ULong> =
        state.frame!!.rowsIn(0f, Float.MAX_VALUE).map { it.key }.toSet()

    /**
     * Appends one entry and drives the frozen clock until its row is both
     * laid out (a new frame) and realized (composed); returns the new key.
     */
    private fun appendAndRealize(state: TranscriptState, entries: MutableList<DebugEntry>, id: String): ULong {
        entries.add(DebugEntry(id, false, "New row.", false))
        state.engine.setDebugEntries(entries, working = false)
        val known = frameKeys(state)
        val expected = (known.size + 1).toUInt()
        var key: ULong? = null
        driveClock {
            val frame = state.frame ?: return@driveClock false
            if (frame.rowCount() != expected) return@driveClock false
            val newKey = frame.rowsIn(0f, Float.MAX_VALUE).firstOrNull { it.key !in known }?.key
                ?: return@driveClock false
            key = newKey
            state.realized[newKey] != null
        }
        return key ?: error("the appended row never arrived")
    }

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theHostTagsRowsAndPlacesThemAtRustCoordinates() {
        val state = fedState(HostFixture, expectedRows = 6u)
        try {
            host(state)
            compose.onNodeWithTag("transcript").assertExists()
            assertEquals(2, compose.onAllNodesWithTag("row-user").fetchSemanticsNodes().size)
            assertTrue(compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().isNotEmpty())

            // Placement at offset 0: top = round(p.y·d), height = round(p.height·d),
            // full host width (822 px at xhdpi). The host is the only root
            // child, so root-local and host-local coordinates agree.
            val users = compose.onAllNodesWithTag("row-user").fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.top }
            val expected = state.realized.values
                .filter { it.placement.kind == RowKind.USER }
                .sortedBy { it.topPx() }
            assertEquals(expected.size, users.size)
            expected.zip(users).forEach { (row, node) ->
                assertEquals(row.topPx().toFloat(), node.boundsInRoot.top, 1f)
                assertEquals(row.heightPx().toFloat(), node.boundsInRoot.height, 1f)
                assertEquals(822f, node.boundsInRoot.width, 1f)
            }
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theRealizedBandMatchesRowsInAndCoversTheViewport() {
        val state = fedState(LongFixture, expectedRows = 120u)
        try {
            assertTrue("tall enough to scroll", state.frame!!.totalHeight() > 4600f)
            host(state)
            state.viewport.setOffset(3000f)
            compose.waitForIdle()

            val start = bandStart(3000f)
            val end = bandEnd(start, 891f)
            assertEquals(2048f, start, 0f)
            // Every realized row's placement intersects [start, end).
            state.realized.values.forEach { row ->
                val p = row.placement
                assertTrue("row ${p.key} at y=${p.y} outside [$start, $end)", p.y < end && p.y + p.height > start)
            }
            // Every row intersecting iOS's band — [3000−700, 3000+891+700)
            // = [2300, 4591) — is realized.
            for (p in state.frame!!.rowsIn(2300f, 4591f)) {
                assertTrue("row ${p.key} in the viewport band is not realized", p.key in state.realized)
            }
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollingWithinOneBandStepNeverRecomposesTheHost() {
        val state = fedState(LongFixture, expectedRows = 120u)
        try {
            host(state)
            state.viewport.setOffset(3100f)
            compose.waitForIdle()

            val compositions = state.hostCompositions
            // `positionInRoot`, not `boundsInRoot.top`: the bounds are
            // clipped to every ancestor (`localBoundingBoxOf(clipBounds =
            // true)`), and the topmost realized user row sits in the 700 dp
            // overscan above the viewport (band 2304… vs offset 3100), so
            // its clipped top reads 0 at both offsets.
            val top = compose.onAllNodesWithTag("row-user").fetchSemanticsNodes()
                .minOf { it.positionInRoot.y }

            state.viewport.setOffset(3200f) // the same 256 dp step (bandStart = 2304 for both)
            compose.waitForIdle()
            assertEquals(bandStart(3100f), bandStart(3200f), 0f)
            assertEquals("scrolling within a step recomposed the host", compositions, state.hostCompositions)

            // The same realized set (no recomposition), so the minimum is the same row.
            val topAfter = compose.onAllNodesWithTag("row-user").fetchSemanticsNodes()
                .minOf { it.positionInRoot.y }
            assertEquals("the row did not move up by 100 dp = 200 px", top - 200f, topAfter, 1f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aNeverSeenKeyFadesInOver280msWithEaseOutEvenUnderReduceMotion() {
        val state = fedState(HostFixture, expectedRows = 6u)
        try {
            host(state)

            // The first non-empty frame pre-marked every key known: those
            // rows are at alpha 1 from the start.
            state.realized.values.forEach { assertEquals(1f, it.appearAlpha, 0f) }

            compose.mainClock.autoAdvance = false
            val entries = HostFixture.toMutableList()

            val key = appendAndRealize(state, entries, "a2")
            val row = state.realized[key]!!
            assertEquals("before advancing, the row is at alpha 0", 0f, row.appearAlpha, 0f)
            compose.mainClock.advanceTimeBy(140)
            val mid = row.appearAlpha
            assertTrue("alpha $mid at 140 ms is not in [0.62, 0.75]", mid in 0.62f..0.75f)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(1f, row.appearAlpha, 0f)

            // Reduce motion forced on: iOS does not gate this fade, and
            // neither does the host.
            ReduceMotion.debugOverride = true
            val reducedKey = appendAndRealize(state, entries, "a3")
            val reducedRow = state.realized[reducedKey]!!
            assertEquals(0f, reducedRow.appearAlpha, 0f)
            compose.mainClock.advanceTimeBy(140)
            assertTrue("alpha ${reducedRow.appearAlpha} not in [0.62, 0.75]", reducedRow.appearAlpha in 0.62f..0.75f)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(1f, reducedRow.appearAlpha, 0f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theSyncFallbackBuildsOneCountedModelPerRealizedRow() {
        val state = fedState(HostFixture, expectedRows = 6u)
        try {
            host(state)
            assertEquals(6, state.realized.size)
            assertEquals("one sync build per realized row (no cache yet)", 6, TranscriptPerf.syncBuilds)
            assertEquals("one row host composed per realized key", 6, TranscriptPerf.viewsCreated)
            assertTrue(TranscriptPerf.maxSyncBuildMs >= 0.0)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun firstUserBubbleBoundsReturnsTheFirstUserBubblesBoxInWindowPx() {
        val state = fedState(HostFixture, expectedRows = 6u)
        try {
            // Before any composition there are no realized rows: null.
            assertNull(state.firstUserBubbleBounds())

            host(state)
            val bounds = state.firstUserBubbleBounds()
            assertNotNull(bounds)

            val frame = state.frame!!
            val p0 = frame.placement(0u)!! // u1, the first user row
            assertEquals(RowKind.USER, p0.kind)
            val display = frame.display(0u)!!
            val box = display.boxes.first { it.color == ColorRole.USER_BUBBLE && it.scroller == null }
            val d = 2f
            // The window origin is measured independently (the root's own
            // bounds), so this checks the local→window transform too.
            val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
            assertEquals(root.top + (p0.y + box.y) * d, bounds!!.top, 1f)
            assertEquals(root.left + box.x * d, bounds.left, 1f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun anUnconsumedTapFiresOnTapOnceAndASwipeNeverDoes() {
        val state = fedState(LongFixture, expectedRows = 120u)
        val taps = AtomicInteger()
        try {
            host(state, TranscriptActions(onTap = { taps.incrementAndGet() }))

            // A tap on a markdown row's center: no widget or scroll
            // consumed it, so the Final-pass observer fires exactly once.
            val center = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes()
                .first().boundsInRoot.center
            val rootTopLeft = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onNodeWithTag("transcript").performTouchInput { click(center - rootTopLeft) }
            compose.waitForIdle()
            assertEquals(1, taps.get())

            // A swipe the scrollable consumed: no tap, and the offset grows.
            // The first frame now lands at the tail (68 R2), so move to the
            // top first — the swipe scrolls toward the tail from there.
            compose.runOnIdle { state.viewport.setOffset(0f) }
            val before = state.offset
            compose.onNodeWithTag("transcript").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals("a drag must not fire onTap", 1, taps.get())
            assertTrue("offset did not grow: $before → ${state.offset}", state.offset > before)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aThemeFlipRepaintsTheRowsWithoutRebuildingTheirModels() {
        val state = fedState(HostFixture, expectedRows = 6u)
        var dark by mutableStateOf(false)
        try {
            compose.setContent {
                RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                    }
                }
            }
            compose.waitForIdle()
            val builds = TranscriptPerf.syncBuilds
            val models = state.realized.values.map { it.model }

            compose.runOnIdle { dark = true }
            compose.waitForIdle()

            assertEquals("a theme flip rebuilt models", builds, TranscriptPerf.syncBuilds)
            state.realized.values.map { it.model }.forEachIndexed { i, m -> assertSame(models[i], m) }
        } finally {
            state.close()
        }
    }
}
