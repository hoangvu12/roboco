package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate rules (android-ui-parity #05), in order: vibrator, foreground,
 * the moment's own gap, then the global 25 ms light-gap rule. A skipped
 * event records nothing, so it does not start a gap.
 */
class FeedbackGateTest {

    private var now = 10_000L
    private val env = FakeEnv()
    private val gate = FeedbackGate(env) { now }

    private fun why(d: Decision) = (d as? Decision.Skip)?.why

    @Test
    fun aMomentPlaysAndACooldownFollows() {
        assertEquals(Decision.Play, gate.haptic(Haptic.Select))
        now += 20
        assertEquals(Skipped.Rate, why(gate.haptic(Haptic.Select)))
        now += 31
        assertEquals(Decision.Play, gate.haptic(Haptic.Select))
    }

    @Test
    fun aHigherPriorityCutsThroughTheGlobalGap() {
        assertEquals(Decision.Play, gate.haptic(Haptic.Select))
        now += 10
        assertEquals(Decision.Play, gate.haptic(Haptic.Confirm))
    }

    @Test
    fun theGlobalGapBlocksAnEquallyLightOrLighterHapticOnItsOwn() {
        // A fresh gate, so Select's own 50 ms gap cannot be the cause: Select
        // has never played; only rule 4 (priority 0 <= 1 inside 25 ms) skips.
        val fresh = FeedbackGate(env) { now }
        assertEquals(Decision.Play, fresh.haptic(Haptic.Confirm))
        now += 10
        assertEquals(Skipped.Rate, why(fresh.haptic(Haptic.Select)))
        now += 15
        assertEquals(Decision.Play, fresh.haptic(Haptic.Select))
    }

    @Test
    fun equalPrioritiesNeverStack() {
        assertEquals(Decision.Play, gate.haptic(Haptic.Confirm))
        now += 10
        assertEquals(Skipped.Rate, why(gate.haptic(Haptic.LongPress)))
        now += 20
        assertEquals(Decision.Play, gate.haptic(Haptic.LongPress))
    }

    @Test
    fun aBurstOfTheSameMomentAtOneInstantPlaysExactlyOnce() {
        val decisions = (1..6).map { gate.haptic(Haptic.Select) }
        assertEquals(1, decisions.count { it == Decision.Play })
        assertEquals(5, decisions.count { why(it) == Skipped.Rate })
    }

    @Test
    fun nothingPlaysWhileInactiveAndASkipRecordsNothing() {
        env.active = false
        assertEquals(Skipped.Inactive, why(gate.haptic(Haptic.Select)))
        env.active = true
        now += 1
        assertEquals(Decision.Play, gate.haptic(Haptic.Select))
    }

    @Test
    fun nothingPlaysWithoutAVibrator() {
        env.hasVibrator = false
        assertEquals(Skipped.NoVibrator, why(gate.haptic(Haptic.Select)))
    }

    @Test
    fun eachDecisionReadsEachEnvironmentPropertyAtMostOnce() {
        val counting = CountingEnv()
        val counted = FeedbackGate(counting) { now }
        counted.haptic(Haptic.Select)
        assertEquals(1, counting.activeReads)
        assertEquals(1, counting.vibratorReads)
    }
}

private class FakeEnv(
    override var active: Boolean = true,
    override var hasVibrator: Boolean = true,
) : FeedbackEnvironment

private class CountingEnv : FeedbackEnvironment {
    var activeReads = 0
    var vibratorReads = 0
    override val active: Boolean get() = true.also { activeReads++ }
    override val hasVibrator: Boolean get() = true.also { vibratorReads++ }
}
