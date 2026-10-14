package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import uniffi.roboco_core.RowKind

/**
 * The row motion against the real core (ticket 132; the same JNA setup as
 * `TranscriptHostTest`, `w411dp-h891dp-xhdpi` so d = 2, over
 * [MotionFixture]): tapping "Show more" through 120's real handler picks
 * FOLD, keeps the reply row at its old top right after the apply (the
 * fraction-0 rule), carries it roughly two thirds of the way after 80 ms
 * of frames (the ticket's 0.55–0.85 band around EaseOut's 64–80 ms
 * samples), and lands it exactly after the 140 ms duration with no tracks
 * left; the user row's measured height is the final one again. Reduce
 * motion forced on makes the same toggle jump in the applying frame and
 * leaves the fold mark set. The fixture has no Tools row, so no row ever
 * clips — checked mid-tween through the row nodes' test-only `RowClipKey`
 * channel; the Tools clip itself is covered by `isAnimating` in the unit
 * tests and by eye in the Transcript Lab (135).
 *
 * Robolectric + Compose: the frame relay's Choreographer hop needs the
 * looper stepping (as `TranscriptHostTest.driveClock` does); the tick
 * loop's `withFrameNanos` runs on the frozen main clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RowMotionHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        runBlocking { TextEngine.await() }
    }

    @After
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- helpers

    /** xhdpi: the qualifiers pin d = 2. */
    private fun d(): Float = 2f

    /**
     * A fed state over [MotionFixture] (viewport first, then entries — a
     * width-0 frame never builds), waiting for the 3-row frame at width
     * 411: the folded long prompt (USER), the reply (MARKDOWN) and the
     * short follow-up (USER). Steps the looper's clock, as
     * `TranscriptHostTest.fedState` does.
     */
    private fun fedState(): TranscriptState {
        val state = TranscriptState()
        state.setViewport(411f, 1f)
        state.engine.setDebugEntries(MotionFixture, working = false)
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let { it.width() == 411f && it.rowCount() == 3u } != true) {
            check(System.nanoTime() < end) { "no 3-row frame at width 411 within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
        return state
    }

    private fun host(state: TranscriptState) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Advances the frozen main clock one frame at a time until [condition]
     * holds (5 s wall), stepping the paused main looper one Choreographer
     * frame delay first and sending the apply notification afterwards —
     * `TranscriptHostTest.driveClock`, verbatim.
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

    /**
     * Toggles [key] through 120's real handler and drives the relay hop
     * until the new frame is applied and composed: polls the engine for a
     * new revision from the test thread (the worker builds it off-main),
     * then steps the clock until `state.frame` carries it.
     */
    private fun toggleAndWait(state: TranscriptState, key: ULong) {
        val rev0 = state.frame!!.revision()
        state.toggleRow(key) // sets pendingFold, then engine.toggle
        val end = System.nanoTime() + 5_000_000_000L
        var newRev = 0uL
        while (newRev == 0uL) {
            check(System.nanoTime() < end) { "no new revision within 5 s" }
            val rev = state.engine.frame().revision()
            if (rev != rev0) newRev = rev else Thread.sleep(10)
        }
        driveClock { state.frame?.revision() == newRev }
    }

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun togglingShowMoreFoldsTheRowsBelowOver140MsEaseOut() {
        val state = fedState()
        try {
            host(state)

            // Row keys are Rust hashes, not the DebugEntry ids: u1 is
            // placement 0, the reply a1 placement 1.
            val frame = state.frame!!
            val pU1 = frame.placement(0u)!!
            val pA1 = frame.placement(1u)!!
            assertEquals(RowKind.USER, pU1.kind)
            assertEquals(RowKind.MARKDOWN, pA1.kind)
            assertTrue("the fixture's prompt must start folded", pU1.height < 300f)
            val rowB = state.realized[pA1.key]!!
            // The first frame landed at the tail, so the host follows it and
            // the fold apply would start 68's follow spring (R3), sliding the
            // offset under the row tween. Released, the apply keeps the
            // offset final (R4 + 130's anchor) and only the rows animate.
            state.viewport.releaseFollow()
            val oldTop = rowB.topPx()

            compose.mainClock.autoAdvance = false
            toggleAndWait(state, pU1.key)

            // The applied frame chose FOLD and built tracks for the realized
            // rows (the offset and content height jumped to their final
            // values in the same apply; only the rows animate).
            assertTrue("no tracks after the fold apply", state.rowMotion.hasTracks)
            assertEquals(RowMotionKind.FOLD, state.rowMotion.lastKind)
            assertNull(state.pendingFold)

            val newFrame = state.frame!!
            val pA1New = newFrame.placement(1u)!!
            val pU1New = newFrame.placement(0u)!!
            assertTrue("the fold must grow u1", pU1New.height > pU1.height + 300f)
            val newTop = (pA1New.y * d()).roundToInt() - (state.offset * d()).roundToInt()

            // Right after the apply the row still draws where it was: the
            // segment's fraction is 0 until the first tick.
            assertEquals(oldTop.toFloat(), rowB.topPx().toFloat(), 1f)

            // 80 ms of frames: roughly two thirds of the way (EaseOut at
            // 64–80 ms after the first tick = 0.637–0.758, plus one frame
            // of quantization slack).
            compose.mainClock.advanceTimeBy(80)
            val mid = rowB.topPx()
            val fraction = (mid - oldTop).toFloat() / (newTop - oldTop).toFloat()
            assertTrue("fraction $fraction at 80 ms not in [0.55, 0.85]", fraction in 0.55f..0.85f)

            // With no Tools row in the fixture, no row clips — mid-tween,
            // through the row nodes' test-only clip channel.
            val rowNodes = compose.onAllNodesWithTag("row-user").fetchSemanticsNodes() +
                compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes()
            // 129 moved the row-* tag to the full-size text child; RowClipKey stays
            // on the row root beside the layer clip it mirrors, so read it on the
            // tagged node's semantics parent (the root).
            assertTrue("each tagged node's parent must be a row root carrying RowClipKey", rowNodes.isNotEmpty() && rowNodes.all { it.parent?.config?.contains(RowClipKey) == true })
            assertTrue(
                "a non-Tools row must not clip",
                rowNodes.none { it.parent?.config?.getOrNull(RowClipKey) == true },
            )

            // 160 ms more: done, exactly at the new placement, no tracks.
            compose.mainClock.advanceTimeBy(160)
            compose.mainClock.advanceTimeByFrame() // the removal's measure pass
            assertFalse("tracks survive the fold duration", state.rowMotion.hasTracks)
            assertEquals(newTop.toFloat(), rowB.topPx().toFloat(), 1f)

            // The user row's node carries the final measured height (the
            // unclipped layout size; the row spans past the viewport).
            val users = compose.onAllNodesWithTag("row-user").fetchSemanticsNodes()
                .sortedBy { it.positionInRoot.y }
            assertEquals(2, users.size)
            assertEquals((pU1New.height * d()).roundToInt().toFloat(), users.first().size.height.toFloat(), 1f)
            assertEquals(0f, users.first().positionInRoot.y, 1f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun reduceMotionMakesTheSameToggleJumpAndKeepsTheFoldMark() {
        val state = fedState()
        try {
            host(state)
            val u1Key = state.frame!!.placement(0u)!!.key
            val pA1 = state.frame!!.placement(1u)!!
            val rowB = state.realized[pA1.key]!!
            // Not following: a following host springs its offset toward the
            // new tail after the apply (R3), moving the row a frame later.
            state.viewport.releaseFollow()

            compose.mainClock.autoAdvance = false
            ReduceMotion.debugOverride = true
            toggleAndWait(state, u1Key)

            assertFalse("reduce motion must not tween", state.rowMotion.hasTracks)
            assertNull(state.rowMotion.lastKind)
            assertEquals("a mark set under reduce motion is not cleared", u1Key, state.pendingFold)

            // The jump: the row is at its final placement in the applying
            // frame, not where it was.
            val newFrame = state.frame!!
            val pA1New = newFrame.placement(1u)!!
            assertTrue("the fold still happened in the model", pA1New.y > pA1.y + 300f)
            val finalTop = (pA1New.y * d()).roundToInt() - (state.offset * d()).roundToInt()
            assertEquals(finalTop.toFloat(), rowB.topPx().toFloat(), 1f)

            // And the running-motions rule holds trivially here: nothing
            // was running, so nothing settles later either.
            compose.mainClock.advanceTimeByFrame()
            assertFalse(state.rowMotion.hasTracks)
            assertEquals(finalTop.toFloat(), rowB.topPx().toFloat(), 1f)
        } finally {
            state.close()
        }
    }
}
