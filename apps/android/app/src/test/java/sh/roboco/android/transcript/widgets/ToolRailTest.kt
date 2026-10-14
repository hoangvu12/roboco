package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme

/**
 * The tool rail's composition behaviour (ticket 121; the Compose rule at
 * 393 dp × xxhdpi → d = 3, clock frozen so the `withFrameMillis` loop runs
 * only on the frames the test advances — no assertion waits for idle while
 * a draw-in is running):
 *
 * - the plan ([ToolRailMemory.lastPlan]) for every row of the state table:
 *   quiet window, brand-new live group, unknown non-live group, the same
 *   count with a new token, a grown count, reduce motion;
 * - [RailPaths] on [RailFixture3]: the settled path's bounds and the
 *   branch's arc length at density 3, and the last row's missing
 *   continuation;
 * - the covers: a brand-new live group's row content is the pure
 *   background while its cover is opaque, and revealed after the draw-in
 *   finishes;
 * - the rail consumes no pointer input: a tap through it reaches 120's
 *   [ToolToggleTarget] underneath.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ToolRailTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        ToolRailMemory.resetForTest()
        // Past the quiet window (quietUntilMs = 0) and frozen, like the
        // main clock: only the frames the test advances change anything.
        ToolRailMemory.clock = { 10_000L }
    }

    // ------------------------------------------------------------- fixtures

    /** The rail's inputs as snapshot state: a write recomposes the rail. */
    private class RailStage(
        initialKey: ULong,
        initialCount: Int,
        initialLive: Boolean,
        initialReduce: Boolean,
        initialToken: Any?,
    ) {
        var key by mutableStateOf(initialKey)
        var count by mutableIntStateOf(initialCount)
        var live by mutableStateOf(initialLive)
        var reduce by mutableStateOf(initialReduce)
        var token by mutableStateOf(initialToken)
    }

    /**
     * Composes the rail at the fixture geometry: a 390 dp row, the rail box
     * placed at x = 18 as `WidgetBox` would place it. One frame is advanced
     * by the caller before reading [ToolRailMemory.lastPlan]: the plan is
     * made in composition, never on a frame.
     */
    private fun composeStage(stage: RailStage) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalFeedback provides NoFeedback,
                    LocalReduceMotion provides stage.reduce,
                ) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Box(Modifier.absoluteOffset(x = 18.dp, y = 0.dp).size(63.dp, 240.dp)) {
                            ToolRail(
                                groupKey = stage.key,
                                spec = railDemoSpec(stage.count),
                                rowWidthDp = 372f,
                                live = stage.live,
                                buildToken = stage.token,
                            )
                        }
                    }
                }
            }
        }
    }

    /** A state write made outside composition while the clock is frozen. */
    private fun settleAfterWrite(write: () -> Unit) {
        compose.runOnUiThread {
            write()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    // ------------------------------------------------- the plan (state table)

    @Test
    fun aBrandNewLiveGroupDrawsInFromRow0() {
        composeStage(RailStage(500uL, 2, true, false, Any()))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RailPlan(0, true), ToolRailMemory.lastPlan[500uL])
    }

    @Test
    fun anUnknownNonLiveGroupIsStatic() {
        composeStage(RailStage(501uL, 3, false, false, Any()))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RailPlan(null, true), ToolRailMemory.lastPlan[501uL])
    }

    @Test
    fun theQuietWindowMakesEvenALiveGroupStatic() {
        ToolRailMemory.onTranscriptAttached()
        composeStage(RailStage(502uL, 2, true, false, Any()))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RailPlan(null, true), ToolRailMemory.lastPlan[502uL])
    }

    @Test
    fun theSameCountWithANewTokenIsStatic() {
        val stage = RailStage(503uL, 2, true, false, Any())
        composeStage(stage)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RailPlan(0, true), ToolRailMemory.lastPlan[503uL])
        // A re-realized row or a new model with the same count: fully drawn.
        settleAfterWrite { stage.token = Any() }
        assertEquals(RailPlan(null, false), ToolRailMemory.lastPlan[503uL])
    }

    @Test
    fun aGrownCountAnimatesOnlyTheNewRows() {
        val stage = RailStage(504uL, 2, true, false, Any())
        composeStage(stage)
        compose.mainClock.advanceTimeByFrame()
        settleAfterWrite {
            stage.count = 3
            stage.token = Any()
        }
        assertEquals(RailPlan(2, false), ToolRailMemory.lastPlan[504uL])
    }

    @Test
    fun underReduceMotionThePlanIsStaticButTheMemoryStillUpdates() {
        val stage = RailStage(505uL, 2, true, true, Any())
        composeStage(stage)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RailPlan(null, true), ToolRailMemory.lastPlan[505uL])
        // `revealed` was still written under reduce motion.
        settleAfterWrite {
            stage.reduce = false
            stage.token = Any()
        }
        assertEquals(RailPlan(null, false), ToolRailMemory.lastPlan[505uL])
    }

    // ---------------------------------------------------- RailPaths geometry

    @Test
    fun railFixture3PathsBoundsAndBranchLength() {
        val paths = RailPaths(RailFixture3, density = 3f)
        val b: Rect = paths.settled(3).getBounds()
        // x ∈ [14.0625, 31.5] dp, y ∈ [31.5, 181.5] dp — ±1 px at d = 3.
        assertEquals(14.0625f * 3f, b.left, 1f)
        assertEquals(31.5f * 3f, b.right, 1f)
        assertEquals(31.5f * 3f, b.top, 1f)
        assertEquals(181.5f * 3f, b.bottom, 1f)
        // The last row has no continuation: nothing extends below its branch.
        assertNull(paths.continuation(2))
        // The branch: quad ≈ 10.957 dp + line 10.6875 dp = 21.644 dp ± 0.05.
        // ±0.25 px (decision, round 31): PathMeasure flattens the quad into
        // segments and comes out ~0.21 px short of the analytic length.
        assertEquals(21.644f * 3f, paths.length(RailPart.Branch, 0), 0.25f)
    }

    // ------------------------------------------------ the covers (draw order)

    @Test
    fun theCoversHideTheRowContentUntilItsBranchDraws() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Box(Modifier.testTag("rail-host")) {
                        ToolRailFixture(railDemoSpec(2), live = true, key = 507uL, token = Any())
                    }
                }
            }
        }
        // Frame 0: the effect's t0. elapsed = 0, so both covers are opaque —
        // row 1's stand-in icon is the pure background, never a flash.
        compose.mainClock.advanceTimeByFrame()
        val hidden = compose.onNodeWithTag("rail-host").captureToImage().toPixelMap()
        assertPx("row 1's icon is covered at elapsed 0", hidden[189, 256], Palette.Light.background)

        // Past the end (635 ms + slack): the loop has finished, the covers
        // are gone and the row content is revealed.
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        val shown = compose.onNodeWithTag("rail-host").captureToImage().toPixelMap()
        assertPx("row 1's icon is revealed after the draw-in", shown[189, 256], Palette.Light.secondary)
    }

    // ------------------------------------------------------- pointer input

    @Test
    fun aTapInsideTheRailReachesTheToolToggleUnderneath() {
        var toggles = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides NoFeedback) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        // 120's toggle over a 36 dp tool row, then the rail
                        // composed after it — painted on top of it. The
                        // toggle's centre sits inside the rail's 63 dp box.
                        Box(Modifier.absoluteOffset(x = 18.dp, y = 40.dp).size(63.dp, 36.dp)) {
                            ToolToggleTarget(open = false, onToggle = { toggles++ })
                        }
                        Box(Modifier.absoluteOffset(x = 18.dp, y = 31.5.dp).size(63.dp, 199.5.dp)) {
                            ToolRail(groupKey = 506uL, spec = RailFixture3, rowWidthDp = 372f, live = false, buildToken = Any())
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("transcript-tool-toggle").performClick()
        assertEquals("the rail consumes no pointer input", 1, toggles)
    }

    // -------------------------------------------------------------- helpers

    /** Per-channel colour assertion; [tolerance] is in 8-bit steps. */
    private fun assertPx(what: String, actual: Color, expected: Color, tolerance: Int = 2) {
        val t = tolerance / 255f
        assertTrue(
            "$what: rgb(${(actual.red * 255f).toInt()}, ${(actual.green * 255f).toInt()}, " +
                "${(actual.blue * 255f).toInt()}), expected $expected ± $tolerance",
            abs(actual.red - expected.red) <= t &&
                abs(actual.green - expected.green) <= t &&
                abs(actual.blue - expected.blue) <= t,
        )
    }
}
