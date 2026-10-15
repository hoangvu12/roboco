package sh.roboco.android.design.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * The materialize/dematerialize animation, the hidden-state hit-testing
 * rule and the interactive press, through the test-only semantics keys
 * ([GlassProgressKey] / [GlassPressScaleKey]) — ticket 07's acceptance
 * criteria.
 *
 * Timing: the clock is frozen and advanced by whole 16 ms frames. The
 * animation's frame origin is calibrated per test — advance frames until
 * the value first moves (the first tick, 16 ms of playtime) — so the frame
 * counts afterwards map to known playtimes whatever the clock's alignment
 * or how many frames `waitForIdle` ran. 10 frames after the first tick is
 * 176 ms of playtime, the half of a 350 ms materialize; 7 frames is 128 ms,
 * the half of a 250 ms dematerialize (one frame off the exact 125 ms, and
 * easeInOut is at its steepest at the midpoint, so the out-tolerance is
 * widened by one frame's worth).
 *
 * Robolectric draws software, so every surface here is Fallback tier: these
 * tests exercise the animation and the interaction, not the blur itself
 * (the Roborazzi goldens cover the drawn material).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlassVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    private fun hasKey(key: SemanticsPropertyKey<Float>): SemanticsMatcher =
        SemanticsMatcher("has ${key.name}") { it.config.contains(key) }

    private fun progress(): Float {
        val nodes = compose.onAllNodes(hasKey(GlassProgressKey)).fetchSemanticsNodes()
        assertTrue("no GlassProgressKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[GlassProgressKey]
    }

    private fun pressScale(): Float {
        val nodes = compose.onAllNodes(hasKey(GlassPressScaleKey)).fetchSemanticsNodes()
        assertTrue("no GlassPressScaleKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[GlassPressScaleKey]
    }

    private fun pressScaleNodes() =
        compose.onAllNodes(hasKey(GlassPressScaleKey)).fetchSemanticsNodes()

    /**
     * Advances single frames until [still] stops holding — the animation's
     * first tick — leaving the clock at 16 ms of playtime.
     */
    private fun advanceToFirstTick(maxFrames: Int = 12, still: (Float) -> Boolean) {
        var frames = 0
        while (still(progress()) && frames < maxFrames) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("the animation never ticked within $maxFrames frames", frames < maxFrames)
    }

    /**
     * Delivers a state write to the frozen clock and runs the frame that
     * recomposes it. With `autoAdvance = false`, `waitForIdle` only sends the
     * snapshot apply notifications (ComposeIdlingResource.isIdleNow) — the
     * recomposition itself waits for the next frame of the test clock.
     */
    private fun recomposeFrame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** Advances [frames] whole 16 ms frames. */
    private fun advanceFrames(frames: Int) {
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    /** Taps the surface's centre (the surface sits at the root's top-start). */
    private fun tapSurface() {
        compose.onRoot().performTouchInput { click(Offset(80.dp.toPx(), 22.dp.toPx())) }
        compose.mainClock.advanceTimeByFrame()
    }

    @androidx.compose.runtime.Composable
    private fun Host(
        visible: Boolean,
        animated: Boolean = true,
        materializeOnEnter: Boolean = false,
        interactive: Boolean = false,
        interactionSource: MutableInteractionSource? = null,
        onClick: (() -> Unit)? = null,
    ) {
        RobocoTheme(Appearance.Light) {
            Box(Modifier.fillMaxSize()) {
                GlassSurface(
                    modifier = Modifier.size(160.dp, 44.dp),
                    interactive = interactive,
                    interactionSource = interactionSource,
                    visible = visible,
                    animateVisibility = animated,
                    materializeOnEnter = materializeOnEnter,
                ) {
                    // Parenthesized lambda: an unambiguously-parsed no-op
                    // default keeps the clickable in the tree for the taps.
                    val click: () -> Unit = if (onClick != null) onClick else ({})
                    Box(
                        Modifier
                            .testTag("content")
                            .fillMaxSize()
                            .clickable(onClick = click),
                    )
                }
            }
        }
    }

    // ------------------------------------------------- materialize / out

    @Test
    fun materializeReachesHalfAt175msAndOneAt350ms() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) }
        compose.waitForIdle()
        assertEquals(0f, progress(), 1e-4f)

        visible = true
        compose.waitForIdle()
        // Calibrate: the first tick is 16 ms of playtime.
        advanceToFirstTick { it <= 0.001f }
        // 10 more frames = 176 ms of playtime ≈ half of the 350 ms in.
        advanceFrames(10)
        assertEquals("p at half of a 350 ms materialize", 0.5f, progress(), 0.02f)
        // Past 350 ms the tween is finished: exactly 1.
        advanceFrames(25)
        assertEquals(1f, progress(), 1e-4f)
    }

    @Test
    fun dematerializeReachesHalfAt125msAndZeroAt250ms() {
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) }
        compose.waitForIdle()
        assertEquals(1f, progress(), 1e-4f)

        visible = false
        compose.waitForIdle()
        advanceToFirstTick { it >= 0.999f }
        // 7 more frames = 128 ms of playtime ≈ half of the 250 ms out (one
        // frame past the exact 125 ms; the midpoint slope is ≈1.7 per unit
        // time, so one frame is ≈0.028 of p).
        advanceFrames(7)
        assertEquals("p at half of a 250 ms dematerialize", 0.5f, progress(), 0.03f)
        advanceFrames(20)
        assertEquals(0f, progress(), 1e-4f)
    }

    @Test
    fun interruptionContinuesFromTheCurrentValueAndRunsTheFullNewDuration() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) }
        compose.waitForIdle()

        visible = true
        compose.waitForIdle()
        advanceToFirstTick { it <= 0.001f }
        // +100 ms of materialize: 5 frames past the first tick = 96 ms.
        advanceFrames(5)
        val atFlip = progress()
        assertTrue("p at the flip is mid-flight (0..1)", atFlip > 0f && atFlip < 1f)

        // Reverse mid-flight: `.beginFromCurrentState` — no snap to 0 or 1.
        // The frame that recomposes `visible = false` still ticks the
        // in-flight materialize once before its effect is cancelled, so the
        // reversal starts from the value after that frame.
        visible = false
        recomposeFrame()
        val atReverse = progress()
        advanceFrames(2)
        val afterFlip = progress()
        assertTrue("p decreases from the value it had (no jump up)", afterFlip < atReverse)
        assertTrue("p does not jump to 0", afterFlip > 0f)
        assertTrue("p does not jump to 1", afterFlip < 1f)

        // The reversal runs its full 250 ms from the flip, reaching 0.
        advanceFrames(20)
        assertEquals(0f, progress(), 1e-4f)
    }

    @Test
    fun reduceMotionSnapsToTheTargetOnTheNextFrame() {
        ReduceMotion.debugOverride = true
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) }
        compose.waitForIdle()
        assertEquals(0f, progress(), 1e-4f)

        // One frame recomposes the new `visible` and launches the effect,
        // whose snapTo lands outside that frame; the next frame shows it.
        visible = true
        recomposeFrame()
        recomposeFrame()
        assertEquals(1f, progress(), 1e-4f)

        visible = false
        recomposeFrame()
        recomposeFrame()
        assertEquals(0f, progress(), 1e-4f)
    }

    @Test
    fun animateVisibilityFalseSnaps() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible, animated = false) }
        compose.waitForIdle()
        assertEquals(0f, progress(), 1e-4f)

        // As in reduceMotionSnapsToTheTargetOnTheNextFrame: the recompose
        // frame launches the snap, the next frame shows it.
        visible = true
        recomposeFrame()
        recomposeFrame()
        assertEquals(1f, progress(), 1e-4f)
    }

    @Test
    fun materializeOnEnterStartsHiddenAndGrowsIn() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible = true, materializeOnEnter = true) }
        compose.waitForIdle()
        assertEquals("p is 0 on the first frame", 0f, progress(), 1e-4f)

        compose.mainClock.advanceTimeBy(500)
        assertEquals(1f, progress(), 1e-4f)
    }

    // -------------------------------------------------- hidden behaviour

    @Test
    fun hiddenSurfaceLeavesTheSemanticsTree() {
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) }
        compose.waitForIdle()
        compose.onNodeWithTag("content").assertExists()

        // The content leaves the tree the moment setVisible(false) runs —
        // the UIKit model-value rule — not when the fade finishes.
        // One frame (16 ms) is the recomposition that applies the write — far
        // inside the 250 ms fade.
        visible = false
        recomposeFrame()
        compose.onNodeWithTag("content").assertDoesNotExist()

        // And returns the moment setVisible(true) runs.
        visible = true
        recomposeFrame()
        compose.onNodeWithTag("content").assertExists()
    }

    @Test
    fun aDematerializingSurfaceSwallowsClicks() {
        var clicks = 0
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) { clicks++ } }
        compose.waitForIdle()

        // Mid-dematerialize: still fading on screen (p ≈ 0.5), but the
        // content is no longer interactive.
        visible = false
        compose.waitForIdle()
        advanceToFirstTick { it >= 0.999f }
        advanceFrames(7)
        val midOut = progress()
        assertTrue("still fading (p in 0.4..0.6)", midOut in 0.4f..0.6f)
        tapSurface()
        assertEquals("a dematerializing surface swallows the tap", 0, clicks)
    }

    @Test
    fun aMaterializingSurfaceDeliversClicks() {
        var clicks = 0
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible) { clicks++ } }
        compose.waitForIdle()

        visible = true
        compose.waitForIdle()
        advanceToFirstTick { it <= 0.001f }
        advanceFrames(10)
        val midIn = progress()
        assertTrue("still materializing (p in 0.4..0.6)", midIn in 0.4f..0.6f)
        tapSurface()
        assertEquals("the tap reaches the clickable child", 1, clicks)
    }

    // ------------------------------------------------------- interactive

    @Test
    fun interactiveSurfaceScalesWithThePressSpringAndBack() {
        val source = MutableInteractionSource()
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible = true, interactive = true, interactionSource = source) }
        compose.waitForIdle()

        val sizeBefore = compose.onNodeWithTag("content").fetchSemanticsNode().size
        assertEquals(1f, pressScale(), 1e-4f)

        val press = PressInteraction.Press(Offset(80f, 22f))
        source.tryEmit(press)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // the press spring settles in ≈0.4 s
        assertEquals(Glass.PressScale, pressScale(), 0.005f)

        // graphicsLayer scaling: the measured size never changes.
        assertEquals(sizeBefore, compose.onNodeWithTag("content").fetchSemanticsNode().size)

        source.tryEmit(PressInteraction.Release(press))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200) // release + the small overshoot
        assertEquals(1f, pressScale(), 0.005f)
        assertEquals(sizeBefore, compose.onNodeWithTag("content").fetchSemanticsNode().size)
    }

    @Test
    fun nonInteractiveSurfaceNeverScales() {
        compose.setContent { Host(visible = true) }
        compose.waitForIdle()
        assertTrue("a non-interactive surface exposes no press scale", pressScaleNodes().isEmpty())
    }

    @Test
    fun reduceMotionKeepsTheScaleAtOneWhilePressed() {
        ReduceMotion.debugOverride = true
        val source = MutableInteractionSource()
        compose.mainClock.autoAdvance = false
        compose.setContent { Host(visible = true, interactive = true, interactionSource = source) }
        compose.waitForIdle()

        source.tryEmit(PressInteraction.Press(Offset(80f, 22f)))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        assertEquals("no scale under reduce motion", 1f, pressScale(), 0.005f)
    }

    @Test
    fun theCallersClickableStillFiresOncePerTapOnAnInteractiveSurface() {
        var clicks = 0
        compose.setContent { Host(visible = true, interactive = true) { clicks++ } }
        compose.waitForIdle()
        // No shared interaction source: the internal non-consuming tracker
        // drives the press, and the caller's clickable still receives the tap.
        compose.onRoot().performTouchInput { click(Offset(80.dp.toPx(), 22.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }
}
