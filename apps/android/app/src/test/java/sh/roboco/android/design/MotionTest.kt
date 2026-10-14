package sh.roboco.android.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Motion tokens (ticket 04): every spring, curve, duration and delay ported
 * from the iOS app, pinned here against worked reference values.
 *
 * The reference-value table below was computed from the Swift solver in
 * `ios:Transcript/ToolViews.swift` and matched by exact bisection; the spring
 * values are the settle-matched stiffnesses of decision FOUNDATION-D1.
 */
class MotionTest {

    // ------------------------------------------------------------- curves

    @Test
    fun bezierCurvesMatchTheIosReferenceTable() {
        // t, quintOut, expoOut, easeOut, easeInOut, easeIn — from the ticket's
        // reference table (exact bisection of the same control points).
        val table = listOf(
            0.10 to listOf(0.40110, 0.49439, 0.16057, 0.01972, 0.01703),
            0.25 to listOf(0.76486, 0.82562, 0.37814, 0.12916, 0.09346),
            0.50 to listOf(0.96138, 0.97178, 0.68464, 0.50000, 0.31536),
            0.75 to listOf(0.99689, 0.99768, 0.90654, 0.87084, 0.62186),
            0.90 to listOf(0.99984, 0.99988, 0.98297, 0.98028, 0.83943),
        )
        val curves = listOf(Motion.QuintOut, Motion.ExpoOut, Motion.EaseOut, Motion.EaseInOut, Motion.EaseIn)
        for ((t, expected) in table) {
            for (i in curves.indices) {
                assertEquals(
                    "curve ${curves[i]} at t=$t",
                    expected[i],
                    curves[i].transform(t.toFloat()).toDouble(),
                    1e-3,
                )
            }
        }
    }

    @Test
    fun easeOutQuartMatchesTheIosReferenceTable() {
        // The exact polynomial 1 - (1-t)^4 (DraftHandoff.swift:164-165), not a bezier.
        assertEquals(0.34390, Motion.EaseOutQuart.transform(0.10f).toDouble(), 1e-6)
        assertEquals(0.68359375, Motion.EaseOutQuart.transform(0.25f).toDouble(), 1e-6)
        assertEquals(0.9375, Motion.EaseOutQuart.transform(0.50f).toDouble(), 1e-6)
        assertEquals(0.99609375, Motion.EaseOutQuart.transform(0.75f).toDouble(), 1e-6)
        assertEquals(0.9999, Motion.EaseOutQuart.transform(0.90f).toDouble(), 1e-6)
        assertEquals(0.3439, Motion.easeOutQuart(0.10), 1e-9)
        assertEquals(0.68359375, Motion.easeOutQuart(0.25), 1e-9)
        assertEquals(0.9375, Motion.easeOutQuart(0.5), 1e-9)
        assertEquals(0.99609375, Motion.easeOutQuart(0.75), 1e-9)
        assertEquals(0.9999, Motion.easeOutQuart(0.9), 1e-9)
    }

    @Test
    fun quintOutSolverMatchesTheReferenceTable() {
        // The Newton port of ToolViews.swift:21-34, within 1e-4 of the table.
        assertEquals(0.40110, Motion.quintOut(0.10), 1e-4)
        assertEquals(0.76486, Motion.quintOut(0.25), 1e-4)
        assertEquals(0.96138, Motion.quintOut(0.50), 1e-4)
        assertEquals(0.99689, Motion.quintOut(0.75), 1e-4)
        assertEquals(0.99984, Motion.quintOut(0.90), 1e-4)
    }

    @Test
    fun quintOutSolverAgreesWithTheQuintOutBezier() {
        for (t in listOf(0.10, 0.25, 0.50, 0.75, 0.90)) {
            assertEquals(
                "quintOut(t) vs QuintOut.transform(t) at t=$t",
                Motion.quintOut(t),
                Motion.QuintOut.transform(t.toFloat()).toDouble(),
                1e-3,
            )
        }
    }

    @Test
    fun quintOutEndpointsAreExact() {
        assertEquals(0.0, Motion.quintOut(0.0), 0.0)
        assertEquals(1.0, Motion.quintOut(1.0), 0.0)
    }

    // ------------------------------------------------------------- springs

    @Test
    fun springStiffnessIsSettleMatched() {
        // FOUNDATION-D1: omega = ln(1000) / (zeta * T) for zeta < 1; for the
        // critically damped branch (zeta >= 1, ImeSettle) omega = 9.2334 / T,
        // the root of (1 + omega*T)*e^(-omega*T) = 0.001.
        for (spring in Motion.allSprings) {
            val omega = if (spring.damping < 1f) {
                ln(1000.0) / (spring.damping * spring.durationSec)
            } else {
                9.2334 / spring.durationSec
            }
            assertEquals("${spring.name}: omega squared", omega * omega, spring.stiffness.toDouble(), 1e-3)
        }
    }

    @Test
    fun criticallyDampedSpringsUseTheCriticalTailRoot() {
        // zeta >= 1 branch: omega = 9.2334 / T, root of (1 + omega*T)*e^(-omega*T) = 0.001.
        assertEquals(341.02, IosSpring("test", 0.50, 1.0f).stiffness.toDouble(), 0.01)
        assertEquals(532.85, IosSpring("test", 0.40, 1.0f).stiffness.toDouble(), 0.01)
        // ui-parity#133: the interactive IME dismiss settle, 0.25 s.
        assertEquals(1364.09, Motion.ImeSettle.stiffness.toDouble(), 0.01)
    }

    @Test
    fun springStiffnessMatchesTheIosTable() {
        val expected = mapOf(
            "composerMorph" to 365.75,
            "actionButton" to 728.10,
            "toastRise" to 412.78,
            "handoffBubble" to 258.07,
            "handoffBubbleReduced" to 1612.94,
            "jumpButton" to 608.64,
            "bottomStackReflow" to 446.80,
            "accessoryRelayout" to 480.90,
            "sectionChevron" to 733.83,
            // ui-parity#28: the tab bar minimize morph (system
            // `tabBarMinimizeBehavior`, values from android-app#19).
            "tabBarMinimize" to 412.78,
            // ui-parity#133: the interactive IME dismiss settle (iOS's
            // 0.25 s keyboard duration, critically damped).
            "imeSettle" to 1364.09,
        )
        for (spring in Motion.allSprings) {
            assertEquals("${spring.name}: table stiffness", expected.getValue(spring.name), spring.stiffness.toDouble(), 0.01)
        }
    }

    @Test
    fun tabBarMinimizeMatchesTheSystemMinimizeSpec() {
        // ios:Shell/MainTabController.swift:22 sets the behaviour; the numbers
        // are android-app#19's: 0.40 s total, ζ 0.85 — the same settle-matched
        // stiffness as ToastRise (a different iOS site, so a different token).
        assertEquals(0.85f, Motion.TabBarMinimize.damping, 0f)
        assertEquals(0.40, Motion.TabBarMinimize.durationSec, 0.0)
        assertEquals(412.78, Motion.TabBarMinimize.stiffness.toDouble(), 0.01)
    }

    @Test
    fun imeSettleIsTheIosKeyboardSettle() {
        // Ticket 133: IosSpring("imeSettle", 0.25, 1) — critically damped
        // (never oscillates), the iOS keyboard duration mapped by
        // FOUNDATION-D1's zeta >= 1 branch.
        assertEquals(1f, Motion.ImeSettle.damping, 0f)
        assertEquals(0.25, Motion.ImeSettle.durationSec, 0.0)
        assertEquals(1364.09, Motion.ImeSettle.stiffness.toDouble(), 0.01)
    }

    /**
     * /code-review F9 (FOUNDATION-D1: no pasted stiffness): the glass press
     * pairs and the transcript bounce moved into tokens with motion
     * unchanged, bit for bit. Zero tolerance on purpose: 600f/380f are what
     * the former in-place back-solve produced, and 20f is the former
     * `BOUNCE_OMEGA` literal (LargeTitleSnap's 0.4617 s would give 19.9987).
     */
    @Test
    fun theGlassPressAndTranscriptBounceTokensKeepTheirNumbersExactly() {
        assertEquals(600f, Motion.GlassPressDown.stiffness, 0f)
        assertEquals(0.7f, Motion.GlassPressDown.damping, 0f)
        assertEquals(380f, Motion.GlassPressRelease.stiffness, 0f)
        assertEquals(0.55f, Motion.GlassPressRelease.damping, 0f)
        assertEquals(20f, Motion.TranscriptBounce.naturalFrequency.toFloat(), 0f)
        assertEquals(1f, Motion.TranscriptBounce.damping, 0f)
    }

    @Test
    fun allSpringsListsEveryNamedToken() {
        assertEquals(11, Motion.allSprings.size)
        assertEquals(
            listOf(
                Motion.ComposerMorph, Motion.ActionButton, Motion.ToastRise, Motion.HandoffBubble,
                Motion.HandoffBubbleReduced, Motion.JumpButton, Motion.BottomStackReflow,
                Motion.AccessoryRelayout, Motion.SectionChevron, Motion.TabBarMinimize,
                Motion.ImeSettle,
            ),
            Motion.allSprings,
        )
    }

    @Test
    fun springSpecCarriesTheSameDampingAndStiffness() {
        for (spring in Motion.allSprings) {
            val spec: SpringSpec<Float> = spring.spec()
            assertEquals("${spring.name}: damping", spring.damping, spec.dampingRatio, 0f)
            assertEquals("${spring.name}: stiffness", spring.stiffness, spec.stiffness, 0f)
        }
    }

    // ------------------------------------------------------- tween factories

    private fun assertTween(spec: TweenSpec<*>, durationMs: Int, delayMs: Int, easing: Easing, what: String) {
        assertEquals("$what: durationMillis", durationMs, spec.durationMillis)
        assertEquals("$what: delay", delayMs, spec.delay)
        assertSame("$what: easing", easing, spec.easing)
    }

    @Test
    fun tweenFactoriesMatchTheIosTable() {
        assertTween(Motion.rootSwap<Float>(), 350, 0, Motion.EaseInOut, "rootSwap")
        assertTween(Motion.glassIn<Float>(), 350, 0, Motion.EaseInOut, "glassIn")
        assertTween(Motion.glassOut<Float>(), 250, 0, Motion.EaseInOut, "glassOut")
        assertTween(Motion.mentionList<Float>(), 220, 0, Motion.EaseOut, "mentionList")
        assertTween(Motion.thumbRemove<Float>(), 250, 0, Motion.EaseInOut, "thumbRemove")
        assertTween(Motion.composerTextHeight<Float>(), 180, 0, Motion.EaseInOut, "composerTextHeight")
        assertTween(Motion.questionPage<Float>(), 200, 0, Motion.EaseInOut, "questionPage")
        assertTween(Motion.toastOut<Float>(), 250, 0, Motion.EaseInOut, "toastOut")
        assertTween(Motion.wallpaperFadeIn<Float>(), 120, 0, Motion.EaseInOut, "wallpaperFadeIn")
        assertTween(Motion.imageTileCrossfade<Float>(), 200, 0, Motion.EaseInOut, "imageTileCrossfade")
        assertTween(Motion.sessionReveal<Float>(), 180, 0, Motion.EaseOut, "sessionReveal")
        assertTween(Motion.accessoryContentReturn<Float>(), 250, 120, Motion.EaseOut, "accessoryContentReturn")
        assertTween(Motion.sidebarToggle<Float>(), 300, 0, Motion.EaseInOut, "sidebarToggle")
        assertTween(Motion.veil<Float>(), 220, 0, Motion.EaseOut, "veil")
        assertTween(Motion.rowFadeIn<Float>(), 280, 0, Motion.EaseOut, "rowFadeIn")
        assertTween(Motion.uploadRingIn<Float>(), 200, 0, Motion.EaseInOut, "uploadRingIn")
        assertTween(Motion.uploadRingOut<Float>(), 250, 0, Motion.EaseInOut, "uploadRingOut")
        assertTween(Motion.fold<Float>(), 140, 0, Motion.EaseOut, "fold")
        assertTween(Motion.toolChevron<Float>(), 140, 0, Motion.EaseOut, "toolChevron")
        assertTween(Motion.toolRowReveal<Float>(), 360, 0, Motion.ExpoOut, "toolRowReveal")
        assertTween(Motion.railConnector<Float>(), 480, 0, Motion.QuintOut, "railConnector")
    }

    @Test
    fun infiniteRepeatablesMatchTheIosSites() {
        val spinner: InfiniteRepeatableSpec<Float> = Motion.spinnerCycle()
        assertEquals(RepeatMode.Restart, spinner.repeatMode)
        assertTrue("spinnerCycle wraps a tween", spinner.animation is TweenSpec<*>)
        val spinnerTween = spinner.animation as TweenSpec<*>
        assertEquals(750, spinnerTween.durationMillis)
        assertEquals(0, spinnerTween.delay)
        assertSame(LinearEasing, spinnerTween.easing)

        val shimmer: InfiniteRepeatableSpec<Float> = Motion.shimmerSweep()
        assertEquals(RepeatMode.Restart, shimmer.repeatMode)
        assertTrue("shimmerSweep wraps a tween", shimmer.animation is TweenSpec<*>)
        val shimmerTween = shimmer.animation as TweenSpec<*>
        assertEquals(3400, shimmerTween.durationMillis)
        assertEquals(0, shimmerTween.delay)
        assertSame(LinearEasing, shimmerTween.easing)
    }

    @Test
    fun iosMotionEnumConstantsAndGeometryOffsets() {
        // ios:Transcript/ToolViews.swift:3-15.
        assertEquals(140, Motion.FoldMs)
        assertEquals(360, Motion.RowRevealMs)
        assertEquals(480, Motion.ConnectorMs)
        assertEquals(65, Motion.StaggerMs)
        assertEquals(90, Motion.LeadInMs)
        // ios:Composer/ComposerBar.swift:356-367 and ios:Design/Toast.swift:94,108-111.
        assertEquals(8f, Motion.MentionListOffsetDp, 0f)
        assertEquals(16f, Motion.ToastRiseFromDp, 0f)
        assertEquals(12f, Motion.ToastOutToDp, 0f)
    }

    // ------------------------------------------------------ handoff timings

    @Test
    fun handoffTimingsMatchDraftHandoffNormal() {
        val timings = Motion.handoff(false)
        assertEquals(50L, timings.startDelayMs)
        assertEquals(320, timings.dissolveMs)
        assertEquals(0.985, timings.dissolveScale.toDouble(), 1e-6)
        assertEquals(550, timings.glideMs)
        assertEquals(180, timings.composerFadeMs)
        assertEquals(360, timings.composerFadeDelayMs)
        assertEquals(350, timings.transcriptRevealMs)
        assertEquals(120, timings.transcriptRevealDelayMs)
        assertEquals(258.07, timings.bubbleSpring.stiffness.toDouble(), 0.01)
        assertEquals(140, timings.bubbleFadeMs)
        assertEquals(16L, timings.bubblePollMs)
        assertEquals(20, timings.bubblePollTries)
        assertSame(Motion.HandoffBubble, timings.bubbleSpring)
    }

    @Test
    fun handoffTimingsMatchDraftHandoffReduced() {
        val timings = Motion.handoff(true)
        assertEquals(50L, timings.startDelayMs)
        assertEquals(200, timings.dissolveMs)
        assertEquals(1.0, timings.dissolveScale.toDouble(), 1e-6)
        assertEquals(200, timings.glideMs)
        assertEquals(180, timings.composerFadeMs)
        assertEquals(0, timings.composerFadeDelayMs)
        assertEquals(350, timings.transcriptRevealMs)
        assertEquals(0, timings.transcriptRevealDelayMs)
        assertEquals(1612.94, timings.bubbleSpring.stiffness.toDouble(), 0.01)
        assertEquals(140, timings.bubbleFadeMs)
        assertEquals(16L, timings.bubblePollMs)
        assertEquals(20, timings.bubblePollTries)
        assertSame(Motion.HandoffBubbleReduced, timings.bubbleSpring)
    }

    // ------------------------------------------------------- timers, delays

    @Test
    fun timerConstantsMatchTheIosSites() {
        assertEquals(4000L, Motion.ToastHoldMs)
        assertEquals(8000L, Motion.SessionGiveUpMs)
        assertEquals(1400L, Motion.CopyConfirmHoldMs)
        assertEquals(1000L, Motion.StatusTickMs)
        assertEquals(220L, Motion.QuestionAutoAdvanceMs)
        assertEquals(120L, Motion.MentionDebounceMs)
        assertEquals(800L, Motion.RailQuietWindowMs)
        assertEquals(600L, Motion.RefreshMinMs)
        assertEquals(3000L, Motion.RefreshMaxMs)
    }

    // ---------------------------------------------------------- frame loop

    @Test
    fun frameLoopConstantsMatchTranscriptListView() {
        assertEquals(16.0, Motion.FollowRate, 0.0)
        assertEquals(0.85, Motion.GlideRetain, 0.0)
        assertEquals(8.0, Motion.MaxGlideFrames, 0.0)
        assertEquals(1.0 / 30.0, Motion.MaxFrameDtSec, 0.0)
        assertEquals(0.5f, Motion.FollowSnapPx, 0f)
    }

    @Test
    fun followStepMatchesTheIosFrameLoop() {
        // 100 * (1 - e^(-16/60)) = 23.41
        assertEquals(23.41, Motion.followStep(100f, 1.0 / 60.0).toDouble(), 0.01)
        // A 1 s dt clamps to 1/30: 100 * (1 - e^(-16/30)) = 41.34
        assertEquals(41.34, Motion.followStep(100f, 1.0).toDouble(), 0.01)
        // Negative dt clamps to 0: no movement.
        assertEquals(0.0, Motion.followStep(100f, -1.0).toDouble(), 0.0)
    }

    @Test
    fun runwayStepMatchesTheIosRunway() {
        // frames = min(8, (1/60)/(1/60)) = 1: 100 * (1 - 0.85) = 15
        assertEquals(15.0, Motion.runwayStep(100f, 1.0 / 60.0).toDouble(), 0.01)
        // frames = min(8, (1/30)/(1/60)) = 2: 100 * (1 - 0.85^2) = 27.75
        assertEquals(27.75, Motion.runwayStep(100f, 1.0 / 30.0).toDouble(), 0.01)
    }
}
