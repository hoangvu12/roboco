package sh.roboco.android.session

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.Motion
import sh.roboco.android.transcript.TranscriptViewport

/**
 * The jump button's pure rules (android-ui-parity #131), plain JUnit — no
 * Robolectric and no core: the constants, the visibility table, the layer
 * formulas, the spring fixture (`TargetBasedAnimation` over
 * `Motion.JumpButton`), the rule over a real [TranscriptViewport] (68's
 * snapshot state needs no Android framework), and the target-guard of
 * [JumpToLatestMotion] over a virtual frame clock.
 *
 * The viewport geometry is 68's worked example: `topInset 68`,
 * `bottomInset 100`, `viewportHeight 800`, first-frame content 3000 →
 * `maxOffset 2300`.
 */
@OptIn(ExperimentalTestApi::class)
class JumpToLatestRuleTest {

    // --------------------------------------------------------- the rule

    /** Every row of the ticket's §Visibility table. */
    @Test
    fun theVisibilityTableMatchesTheSpec() {
        assertTrue("141, not following", JumpToLatest.isVisible(141f, following = false))
        assertTrue("140.01, not following", JumpToLatest.isVisible(140.01f, following = false))
        assertFalse("140 is a strict > threshold", JumpToLatest.isVisible(140f, following = false))
        assertFalse("following (spring or runway glide in travel)", JumpToLatest.isVisible(500f, following = true))
        assertFalse("past the tail (rubber band)", JumpToLatest.isVisible(-20f, following = false))
        assertFalse("0 at the held runway position", JumpToLatest.isVisible(0f, following = false))
        assertEquals(140f, JumpToLatest.ShowDistance, 1e-6f)
        assertEquals(0.6f, JumpToLatest.HiddenScale, 1e-6f)
        assertEquals(40f, JumpToLatest.Size.value, 1e-6f)
        assertEquals(15f, JumpToLatest.IconPt, 1e-6f)
        assertEquals("Jump to latest", JumpToLatest.Label)
        assertEquals("jump-to-latest", JumpToLatest.Tag)
    }

    /** The layer formulas: clamped alpha, unclamped scale. */
    @Test
    fun theLayerFormulasMatchTheSpec() {
        assertEquals(0.6f, JumpToLatest.scale(0f), 1e-6f)
        assertEquals(1f, JumpToLatest.scale(1f), 1e-6f)
        // The 1.5 % show overshoot: scale ≈ 1.0061 at p = 1.0152.
        assertEquals(1.0061f, JumpToLatest.scale(1.0152f), 1e-4f)
        // Alpha clamps both ends of the overshoot and folds the multiplier.
        assertEquals(1f, JumpToLatest.alpha(1.0152f, 1f), 1e-6f)
        assertEquals(0f, JumpToLatest.alpha(-0.01f, 1f), 1e-6f)
        assertEquals(0.6f, JumpToLatest.alpha(1f, 0.6f), 1e-6f)
        // The nav multiplier clamps on its own side too.
        assertEquals(0f, JumpToLatest.alpha(1f, -0.01f), 1e-6f)
        assertEquals(1f, JumpToLatest.alpha(1f, 1.5f), 1e-6f)
    }

    // ------------------------------------------------------ the spring

    /** The ticket's reference table, exact for the settle-matched spring. */
    @Test
    fun theSpringMatchesTheTicketTable() {
        val animation = TargetBasedAnimation(
            animationSpec = Motion.JumpButton.spec(visibilityThreshold = 0.001f),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = 1f,
        )
        val table = listOf(
            16L to 0.0630,
            32L to 0.2034,
            48L to 0.3688,
            64L to 0.5288,
            96L to 0.7793,
            128L to 0.9244,
            160L to 0.9908,
            200L to 1.0144,
            212L to 1.0152, // the peak
            240L to 1.0127,
            300L to 1.0042,
            400L to 0.9998,
        )
        for ((ms, expected) in table) {
            assertEquals(
                "p at $ms ms",
                expected,
                animation.getValueFromNanos(ms * 1_000_000L).toDouble(),
                0.002,
            )
        }
    }

    /** FOUNDATION-D1: settle-matched stiffness 608.64, damping 0.8 — never a pasted literal. */
    @Test
    fun theSpringTokenIsTheIosOne() {
        assertEquals(608.64f, Motion.JumpButton.stiffness, 0.01f)
        assertEquals(0.8f, Motion.JumpButton.damping, 1e-6f)
        assertEquals(0.35, Motion.JumpButton.durationSec, 1e-9)
    }

    // -------------------------------------------------- the rule over 68

    /** 68's worked example: landed → hidden, 141 up → shown, 140 → hidden. */
    @Test
    fun theRuleOverTheLandedViewport() {
        val vp = landedViewport()
        // After the landing: at the tail, following — hidden.
        assertEquals(0f, vp.distanceFromBottom, 1e-3f)
        assertFalse(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))

        vp.releaseFollow()
        vp.setOffset(vp.maxOffset - 141f)
        assertEquals(141f, vp.distanceFromBottom, 1e-3f)
        assertTrue(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))

        vp.setOffset(vp.maxOffset - 140f)
        assertFalse("140 is strictly below the threshold", JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))
    }

    /** `scrollToBottom(true)`: hidden at once, while still 141 away (the glide is animated). */
    @Test
    fun scrollToBottomHidesItAtOnceWhileStillFarAway() {
        val vp = landedViewport()
        vp.releaseFollow()
        vp.setOffset(vp.maxOffset - 141f)
        assertTrue(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))

        vp.scrollToBottom(animated = true)
        assertTrue(vp.following)
        assertTrue("the follow spring (the animated glide) is armed", vp.springRunning)
        assertEquals("still 141 away — no snap", 141f, vp.distanceFromBottom, 1e-3f)
        assertFalse(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))
    }

    /**
     * The runway (69): while it holds, the *held position* is the bottom —
     * `setContentHeight(H)` with `maxOffset == offset` keeps the rule
     * false, and a 141 dp drag above it shows the button even though the
     * natural content is far longer.
     */
    @Test
    fun theRunwayHeldPositionIsTheBottom() {
        val vp = TranscriptViewport()
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.setViewportHeight(800f)
        // A long chat: the natural content is 8000 dp, the tail at 7300.
        vp.onFrameApplied(8000f, first = true)
        vp.releaseFollow()
        // 69 replaces the content height with the runway reserve: the held
        // position (prompt at the top) becomes the scroll range's end.
        vp.setOffset(2300f)
        vp.setContentHeight(3000f)
        assertEquals(2300f, vp.maxOffset, 1e-3f)
        assertEquals(0f, vp.distanceFromBottom, 1e-3f)
        assertFalse(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))

        // A 141 dp drag above the held position: shown — the distance is
        // measured to the reserved bottom, not to the natural 7300 one.
        vp.setOffset(2300f - 141f)
        assertEquals(141f, vp.distanceFromBottom, 1e-3f)
        assertTrue(JumpToLatest.isVisible(vp.distanceFromBottom, vp.following))
    }

    // ------------------------------------------- the motion's target guard

    /**
     * SVC:472's guard: `set(true)` × 10 mid-flight are no-ops — the
     * progress curve is bit-identical to a single show, because the rule
     * fires on every scroll step on iOS and must never restart the spring.
     * Virtual time + [TestMonotonicFrameClock], so the frame times are
     * deterministic.
     */
    @Test
    fun repeatedIdenticalTargetsNeverRestartTheSpring() = runTest {
        val scope = CoroutineScope(coroutineContext + TestMonotonicFrameClock(this))
        val plain = JumpToLatestMotion(false)
        val repeated = JumpToLatestMotion(false)
        plain.set(true, scope)
        repeated.set(true, scope)

        advanceTimeBy(48)
        runCurrent()
        // Ten identical retargets mid-flight: the guard makes each a no-op.
        repeat(10) { repeated.set(true, scope) }
        assertTrue("mid-flight at 48 ms: ${repeated.progress.value}", repeated.progress.value in 0.1f..0.5f)

        var now = 48L
        for (ms in longArrayOf(96, 160, 212, 400)) {
            advanceTimeBy(ms - now)
            now = ms
            runCurrent()
            assertEquals(
                "progress at $ms ms matches a single show",
                plain.progress.value,
                repeated.progress.value,
                1e-6f,
            )
        }
        assertTrue("settled shown by 400 ms", repeated.progress.value > 0.998f)
        assertFalse(repeated.progress.isRunning)
    }

    // ------------------------------------------------------------- helpers

    /** 68's worked example, landed on the first frame (content 3000 → offset 2300). */
    private fun landedViewport(): TranscriptViewport {
        val vp = TranscriptViewport()
        vp.setTopInset(68f)
        vp.setBottomInset(100f)
        vp.setViewportHeight(800f)
        vp.onFrameApplied(3000f, first = true)
        return vp
    }
}
