package sh.roboco.android.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The Motion-lab model (android-ui-parity #22): group sizes, unique names,
 * and labels computed from ticket 04's `Motion`/`IosSpring` tokens — never
 * retyped literals. The label strings below are the ticket's worked examples
 * (except composerMorph's `k`, see [springLabelsFormatTheTokenValues]).
 */
class MotionLabTest {

    @Test
    fun groupsHaveTheTicketSizesAndUniqueNames() {
        // Ticket 22 listed 9; ui-parity#28 explicitly added TabBarMinimize to
        // Motion.allSprings and ui-parity#133 added ImeSettle (which
        // springs() mirrors, and MotionTest pins).
        assertEquals(11, MotionLab.springs().size)
        assertEquals(21, MotionLab.tweens().size)
        assertEquals(2, MotionLab.repeats().size)
        assertEquals(5, MotionLab.handoff(false).size)
        assertEquals(5, MotionLab.handoff(true).size)
        assertEquals(2, MotionLab.loops().size)

        val names = (
            MotionLab.springs() + MotionLab.tweens() + MotionLab.repeats() +
                MotionLab.handoff(false) + MotionLab.loops()
            ).map { it.name }
        assertEquals("duplicate lab row names", names.size, names.toSet().size)
    }

    @Test
    fun springLabelsFormatTheTokenValues() {
        val composerMorph = MotionLab.springs().first { it.name == "composerMorph" }
        // The ticket's example reads "k365.75": the settle-matched stiffness
        // is 365.745 as a decimal, but the token stores a Float
        // (365.7449951171875f), so `%.2f` renders "365.74". The format
        // (`spring ζ%.2f T%.2fs k%.2f`) is the spec; this is its exact output.
        assertEquals("spring ζ0.86 T0.42s k365.74", composerMorph.label)
        // Under-damped tokens keep their own ζ/T, not a shared one:
        assertEquals("spring ζ0.85 T0.40s k412.78", MotionLab.springs().first { it.name == "toastRise" }.label)
    }

    @Test
    fun tweenLabelsReadDurationDelayAndEasing() {
        val tweens = MotionLab.tweens()
        assertEquals("350ms EaseInOut", tweens.first { it.name == "rootSwap" }.label)
        assertEquals("250ms +120ms EaseOut", tweens.first { it.name == "accessoryContentReturn" }.label)
        assertEquals("360ms ExpoOut", tweens.first { it.name == "toolRowReveal" }.label)
        assertEquals("480ms QuintOut", tweens.first { it.name == "railConnector" }.label)
        assertEquals("140ms EaseOut", tweens.first { it.name == "fold" }.label)
    }

    @Test
    fun repeatLabelsReadTheTweenInsideTheInfiniteSpec() {
        assertEquals("∞ 750ms Linear", MotionLab.repeats().first { it.name == "spinnerCycle" }.label)
        assertEquals("∞ 3400ms Linear", MotionLab.repeats().first { it.name == "shimmerSweep" }.label)
    }

    @Test
    fun easingNamesAreResolvedByEquality() {
        assertEquals("Linear", MotionLab.easingName(LinearEasing))
        assertEquals("EaseInOut", MotionLab.easingName(Motion.EaseInOut))
        assertEquals("EaseOut", MotionLab.easingName(Motion.EaseOut))
        assertEquals("EaseIn", MotionLab.easingName(Motion.EaseIn))
        assertEquals("ExpoOut", MotionLab.easingName(Motion.ExpoOut))
        assertEquals("QuintOut", MotionLab.easingName(Motion.QuintOut))
        assertEquals("EaseOutQuart", MotionLab.easingName(Motion.EaseOutQuart))
        assertEquals("custom", MotionLab.easingName(CubicBezierEasing(0.1f, 0.2f, 0.3f, 0.4f)))
    }

    @Test
    fun handoffRowsCarryTheProfileNames() {
        assertEquals(
            listOf("handoff.dissolve", "handoff.glide", "handoff.composerFade", "handoff.transcriptReveal", "handoff.bubbleFade"),
            MotionLab.handoff(false).map { it.name },
        )
    }

    @Test
    fun handoffLabelsFollowTheReduceMotionProfile() {
        // Normal (DraftHandoff.swift:59): 320/550/180+360/350+120/140.
        assertEquals(
            listOf("320ms EaseOut", "550ms EaseOutQuart", "180ms +360ms EaseInOut", "350ms +120ms EaseOut", "140ms EaseInOut"),
            MotionLab.handoff(false).map { it.label },
        )
        // Reduced: 200/200/180/350/140 — no delays under Reduce Motion.
        assertEquals(
            listOf("200ms EaseOut", "200ms EaseOutQuart", "180ms EaseInOut", "350ms EaseOut", "140ms EaseInOut"),
            MotionLab.handoff(true).map { it.label },
        )
    }

    @Test
    fun frameLoopsLabelTheirStepMath() {
        val loops = MotionLab.loops()
        assertEquals(2, loops.size)
        assertTrue(loops.any { it.name == "followStep" && it.label == "frame loop · 1−e^(−16·dt)" })
        assertTrue(loops.any { it.name == "runwayStep" && it.label == "frame loop · 1−0.85^frames" })
    }

    @Test
    fun timersAreReadFromTheMotionConstants() {
        assertEquals(
            listOf(
                "ToastHoldMs" to 4000L,
                "SessionGiveUpMs" to 8000L,
                "CopyConfirmHoldMs" to 1400L,
                "StatusTickMs" to 1000L,
                "QuestionAutoAdvanceMs" to 220L,
                "MentionDebounceMs" to 120L,
                "RailQuietWindowMs" to 800L,
                "RefreshMinMs" to 600L,
                "RefreshMaxMs" to 3000L,
            ),
            MotionLab.timers,
        )
    }
}
