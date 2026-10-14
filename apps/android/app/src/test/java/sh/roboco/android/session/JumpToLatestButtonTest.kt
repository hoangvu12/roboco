package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.transcript.TranscriptViewport

/**
 * The jump button's Compose contract (android-ui-parity #131):
 * Robolectric + `createComposeRule` at `w412dp-h915dp-xxhdpi` (1 dp =
 * 3 px). The tap-through fixtures place a 200 × 200 dp clickable
 * under-box with the button offset to (80, 80) dp — its centre is
 * (100, 100) dp — so the same physical tap lands on whichever of the two
 * owns the pointer.
 *
 * Every test follows the freeze discipline: `mainClock.autoAdvance =
 * false`, a `Snapshot.sendApplyNotifications()` + frame(s) after each
 * state write (the show/hide chain costs three: the rule recomposition,
 * the collector hop, then the target recomposition), and the glass
 * fallback tier (Robolectric has no haze source anyway,
 * `Glass.forceFallback` pins it). The clock note for the 96 ms reads:
 * launching the spring costs a few frames, so the value at "96 ms of
 * clock time" is the ticket table's p at 64–96 ms — mid-flight either
 * way (the `SessionScaffoldTest` reveal-curve test set this envelope
 * precedent).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class JumpToLatestButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    /** Composes the content and settles the first two frames (the glass flag flip included). */
    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(Appearance.Light) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** State write + notification + one frame, the frozen-clock recipe. */
    private fun applyState() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * The show/hide rule chain at the frozen clock: the rule's
     * recomposition, the snapshotFlow collector's dispatch hop, then the
     * target recomposition — three frames covers every hop deterministically.
     */
    private fun applyRule() {
        applyState()
        applyState()
        applyState()
    }

    /**
     * The 0 → 1 step response of a unit-mass, zero-initial-velocity
     * underdamped spring (ζ < 1) at [tSec] — what Compose's SpringSpec
     * integrates for [stiffness] and [damping].
     */
    private fun underdampedStep(stiffness: Double, damping: Double, tSec: Double): Float {
        val omega = sqrt(stiffness)
        val decay = damping * omega
        val omegaD = omega * sqrt(1 - damping * damping)
        return (1 - exp(-decay * tSec) * (cos(omegaD * tSec) + decay / omegaD * sin(omegaD * tSec))).toFloat()
    }

    /** A tap at the button's centre (100, 100) dp, through the root's coordinates. */
    private fun tapTheButtonCentre() {
        compose.onRoot().performTouchInput { click(Offset(100.dp.toPx(), 100.dp.toPx())) }
        compose.waitForIdle()
    }

    /** The tap-through fixture: an under-box and the button at (80, 80) dp. */
    @Composable
    private fun TapThroughFixture(
        visible: Boolean,
        underTaps: () -> Unit,
        onClick: () -> Unit = {},
        navAlpha: () -> Float = { 1f },
        motion: JumpToLatestMotion = remember { JumpToLatestMotion(visible) },
    ) {
        Box(Modifier.size(200.dp, 200.dp).background(RobocoTheme.palette.background)) {
            Box(Modifier.matchParentSize().clickable(onClick = underTaps))
            JumpToLatestButton(
                visible = visible,
                onClick = onClick,
                modifier = Modifier.offset(80.dp, 80.dp),
                navAlpha = navAlpha,
                motion = motion,
            )
        }
    }

    // ---------------------------------------------------- hidden / shown

    /**
     * Hidden-settled: no node in the semantics tree (UIKit: model alpha 0
     * is not hit-testable and VoiceOver skips it), and the tap at the
     * button's centre reaches the under-box — the outer box has no
     * pointer input, and the glass is not composed at all.
     */
    @Test
    fun hiddenIsAbsentFromSemanticsAndPassesTapsThrough() {
        var under = 0
        var clicks = 0
        host { TapThroughFixture(visible = false, underTaps = { under++ }, onClick = { clicks++ }) }
        compose.onNodeWithTag(JumpToLatest.Tag).assertDoesNotExist()

        tapTheButtonCentre()
        assertEquals("the tap reaches the under-box", 1, under)
        assertEquals("the button's onClick never fires", 0, clicks)
    }

    /** Showing: the node pops into the tree with the label, the role and the tag. */
    @Test
    fun showingPopsTheNodeInWithLabelAndRole() {
        var visible by mutableStateOf(false)
        host { TapThroughFixture(visible, underTaps = {}) }
        compose.onNodeWithTag(JumpToLatest.Tag).assertDoesNotExist()

        visible = true
        applyRule()
        val config = compose.onNodeWithTag(JumpToLatest.Tag).fetchSemanticsNode().config
        assertEquals(JumpToLatest.Label, config[SemanticsProperties.ContentDescription].joinToString())
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(JumpToLatest.Tag, config[SemanticsProperties.TestTag])
    }

    // ------------------------------------------------------- the motion

    /**
     * The show spring at 96 ms of its own play time: mid-flight (≈ 0.779
     * ± 0.05 under IosSpring's settle-matched mapping; the ticket's 0.58
     * assumed ω0 = 2π/T), then settled at 1 after 600 ms more.
     */
    @Test
    fun theShowSpringIsMidFlightAt96msAndSettlesToOne() {
        val motion = JumpToLatestMotion(false)
        var visible by mutableStateOf(false)
        host { TapThroughFixture(visible, underTaps = {}, motion = motion) }

        // Sample at 96 ms of the SPRING's play time, not of clock time after
        // applyRule() (decision, round 31): applyRule's three fixed frames
        // could already run part of the spring, so p was read ~144 ms in
        // (0.864 on CI). Step single frames from the write until the spring
        // is running (`isRunning` flips when animateTo launches), then one
        // frame is its play time 0 (its first withFrameNanos), then 96 ms.
        visible = true
        Snapshot.sendApplyNotifications()
        var launchFrames = 0
        while (!motion.progress.isRunning) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("the show spring never started", ++launchFrames <= 5)
        }
        compose.mainClock.advanceTimeByFrame() // the spring's first frame: play time 0
        compose.mainClock.advanceTimeBy(96)
        val p = motion.progress.value
        // The expected value comes from the spring the app actually runs:
        // IosSpring maps (T 0.35, ζ 0.80) to the settle-matched
        // ω = ln(1000) / (ζ·T) ≈ 24.67 rad/s (stiffness ≈ 608.6, decision
        // FOUNDATION-D1), not the response model ω0 = 2π/T ≈ 17.95 behind
        // the ticket's 0.58. Under the real mapping p(96 ms) ≈ 0.779 — CI on
        // 4d59594c read 0.7793233, the analytic value to 7 digits, so the
        // sampling is exact. Same ±0.05 window as before.
        val expected = underdampedStep(Motion.JumpButton.stiffness.toDouble(), Motion.JumpButton.damping.toDouble(), 0.096)
        assertTrue(
            "mid-flight at 96 ms of play (was $p, expected $expected ± 0.05)",
            abs(p - expected) <= 0.05f,
        )
        assertTrue(motion.progress.isRunning)
        assertTrue(motion.target)

        compose.mainClock.advanceTimeBy(600)
        assertEquals(1f, motion.progress.value, 2e-3f)
        assertFalse(motion.progress.isRunning)
    }

    /**
     * The mid-flight hide: `.beginFromCurrentState` — the value keeps the
     * in-flight progress (no snap to 0), the node leaves the semantics
     * tree and taps fall through **while the hide runs** (the model-value
     * hit-testing rule), and it then falls to hidden within 600 ms with
     * no single-frame snap (the reversal keeps the old momentum, so the
     * curve may rise briefly first — UIKit's additive block animations do
     * the same).
     */
    @Test
    fun aMidFlightHideKeepsItsValueFallsToZeroAndDropsTheNode() {
        val motion = JumpToLatestMotion(false)
        var visible by mutableStateOf(false)
        var under = 0
        host { TapThroughFixture(visible, underTaps = { under++ }, motion = motion) }

        visible = true
        applyRule()
        compose.mainClock.advanceTimeBy(96)
        val mid = motion.progress.value
        assertTrue("mid-flight before the hide (was $mid)", mid > 0.5f)

        visible = false
        applyRule()
        compose.onNodeWithTag(JumpToLatest.Tag).assertDoesNotExist()
        assertTrue("no snap on the hide (was ${motion.progress.value})", motion.progress.value > 0.5f)
        assertFalse(motion.target)

        tapTheButtonCentre()
        assertEquals("taps fall through from the hide frame", 1, under)

        val samples = mutableListOf<Float>()
        repeat(38) { // 38 frames ≈ 608 ms
            compose.mainClock.advanceTimeByFrame()
            samples += motion.progress.value
        }
        assertEquals(0f, motion.progress.value, 2e-3f)
        assertFalse(motion.progress.isRunning)
        val biggestFall = samples.zipWithNext { a, b -> a - b }.maxOrNull() ?: 0f
        assertTrue("no single-frame snap (biggest fall $biggestFall)", biggestFall < 0.35f)
        assertTrue("the hide fell below the mid value", (samples.minOrNull() ?: 1f) < 0.5f)
    }

    // ------------------------------------------------------------ the tap

    /**
     * Tapping the shown button calls `scrollToBottom(animated = true)`:
     * `following` flips on and the follow spring is armed **while the
     * offset is still 400 dp from the tail** (no snap — the glide is
     * animated), and the rule turns false in the same snapshot, so the
     * button starts hiding right away.
     */
    @Test
    fun tappingTheShownButtonGlidesTheViewportBackAndHidesIt() {
        val viewport = TranscriptViewport()
        viewport.setTopInset(68f)
        viewport.setBottomInset(100f)
        viewport.setViewportHeight(800f)
        viewport.onFrameApplied(3000f, first = true)
        viewport.releaseFollow()
        viewport.setOffset(viewport.maxOffset - 400f)
        var under = 0
        host {
            Box(Modifier.size(200.dp, 200.dp).background(RobocoTheme.palette.background)) {
                Box(Modifier.matchParentSize().clickable(onClick = { under++ }))
                JumpToLatestButton(
                    viewport = viewport,
                    modifier = Modifier.offset(80.dp, 80.dp),
                )
            }
        }
        compose.onNodeWithTag(JumpToLatest.Tag).assertExists()

        compose.onNodeWithTag(JumpToLatest.Tag).performClick()
        compose.waitForIdle()
        assertTrue("following resumed", viewport.following)
        assertTrue("the follow spring (the animated glide) is armed", viewport.springRunning)
        assertEquals("still 400 away — the tap glides, it never snaps", 400f, viewport.distanceFromBottom, 1e-3f)

        applyRule()
        compose.onNodeWithTag(JumpToLatest.Tag).assertDoesNotExist()
        // The second tap at the same spot cannot re-fire the button — it
        // left the tree with the hide, so the tap falls through to the
        // under-box instead: scrollToBottom ran exactly once.
        tapTheButtonCentre()
        assertEquals("the second tap falls through, not into the button", 1, under)
    }

    // ----------------------------------------------------- the nav alpha

    /**
     * `navAlpha = 0` (85's pop fade): the layer draws nothing — the pixel
     * at the button's centre is the backdrop exactly — the node is absent
     * from semantics, the tap falls through, all while `target` is still
     * true and the spring is untouched at 1.
     */
    @Test
    fun navAlphaZeroClearsLayerTapsAndSemantics() {
        val motion = JumpToLatestMotion(true)
        var under = 0
        host { TapThroughFixture(visible = true, underTaps = { under++ }, navAlpha = { 0f }, motion = motion) }
        compose.onNodeWithTag(JumpToLatest.Tag).assertDoesNotExist()

        val px = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        val backdrop = px[(10f * d).toInt(), (10f * d).toInt()]
        val centre = px[(100f * d).toInt(), (100f * d).toInt()]
        assertEquals("layer alpha 0: the centre is the backdrop", backdrop, centre)

        tapTheButtonCentre()
        assertEquals("the tap reaches the under-box", 1, under)
        assertEquals("the spring is untouched", 1f, motion.progress.value, 1e-3f)
        assertTrue(motion.target)
    }

    /**
     * `navAlpha = 0.6` while shown: the same button twice over the same
     * backdrop — the faded one's pixels read translucent (a 0.6 blend of
     * the full-alpha button and the backdrop), and the springs stay
     * settled at 1 — the multiplier never re-targets them.
     */
    @Test
    fun navAlphaHalvesTheLayerWithoutTouchingTheSpring() {
        val full = JumpToLatestMotion(true)
        val faded = JumpToLatestMotion(true)
        host {
            Box(Modifier.size(400.dp, 200.dp).background(RobocoTheme.palette.background)) {
                JumpToLatestButton(
                    visible = true, onClick = {},
                    modifier = Modifier.offset(60.dp, 80.dp), motion = full,
                )
                JumpToLatestButton(
                    visible = true, onClick = {},
                    modifier = Modifier.offset(220.dp, 80.dp),
                    navAlpha = { 0.6f }, motion = faded,
                )
            }
        }

        val px = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        var maxDiff = 0f
        for (dx in -4..4) {
            for (dy in -4..4) {
                val a = px[(80f * d).toInt() + dx, (100f * d).toInt() + dy]
                val b = px[(240f * d).toInt() + dx, (100f * d).toInt() + dy]
                maxDiff = max(maxDiff, channelDistance(a, b))
            }
        }
        assertTrue("the 0.6 button reads translucent (max channel diff $maxDiff)", maxDiff > 0.05f)
        assertEquals(1f, full.progress.value, 1e-3f)
        assertEquals(1f, faded.progress.value, 1e-3f)
        assertFalse(faded.progress.isRunning)
    }

    // ---------------------------------------------------- reduced motion

    /**
     * iOS keeps this spring under Reduce Motion (ticket 04): with the
     * gallery's Force-reduce-motion override on, the show still takes
     * several frames — it is never snapped.
     */
    @Test
    fun reduceMotionDoesNotSnapTheShow() {
        ReduceMotion.debugOverride = true
        try {
            val motion = JumpToLatestMotion(false)
            var visible by mutableStateOf(false)
            host { TapThroughFixture(visible, underTaps = {}, motion = motion) }

            visible = true
            applyRule()
            assertTrue(
                "still mid-flight under reduce motion (was ${motion.progress.value})",
                motion.progress.value < 1f,
            )
            compose.mainClock.advanceTimeBy(600)
            assertEquals(1f, motion.progress.value, 2e-3f)
        } finally {
            ReduceMotion.debugOverride = null
        }
    }

    // ---------------------------------------------------------- geometry

    /**
     * The outer box is exactly 40 × 40 dp in every state, so 74 places it
     * at the case-A jump rect: 356, 781, 396, 821 (412 × 915 window, the
     * 24 dp bottom faked through `imeOverride`, composer-only stack).
     */
    @Test
    fun theButtonAnchorsFortyByFortyAtTheCaseAJumpRect() {
        host {
            val state = rememberBottomStackState()
            state.imeOverride = 24.dp
            SessionBottomStack(
                state = state,
                model = BottomStackModel(),
                transcript = { _ -> Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) },
                pill = {},
                queue = {},
                questions = {},
                composer = { Box(Modifier.fillMaxWidth().height(50.dp)) },
                jump = { JumpToLatestButton(visible = true, onClick = {}) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val jump = compose.onNodeWithTag(JumpToLatest.Tag).getBoundsInRoot()
        assertEquals(356f, jump.left.value, 0.5f)
        assertEquals(781f, jump.top.value, 0.5f)
        assertEquals(396f, jump.right.value, 0.5f)
        assertEquals(821f, jump.bottom.value, 0.5f)
    }

    // ------------------------------------------------------------- pixels

    private fun channelDistance(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))
}
