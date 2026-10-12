package sh.roboco.android.design

import android.os.VibrationEffect.Composition as C
import android.view.HapticFeedbackConstants as H
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine (android-ui-parity #05) against a fake [HapticOutput]: the
 * gate runs (nothing plays before ON_START), the plan is chosen from the
 * caps the output reports, and a refused view constant re-plans without the
 * view. No Android framework class is touched on this path.
 */
class AndroidFeedbackTest {

    private var now = 10_000L
    private val hardware = HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true)

    /** Records every perform call; optionally refuses view constants. */
    private class RecordingOutput(var allowView: Boolean = true) : HapticOutput {
        val performed = mutableListOf<HapticPlan>()
        override val hasView: Boolean = true
        override fun perform(plan: HapticPlan): Boolean {
            performed.add(plan)
            return !(plan is HapticPlan.ViewConstant && !allowView)
        }
    }

    private fun feedback(output: HapticOutput, log: ((String) -> Unit)? = null): AndroidFeedback =
        AndroidFeedback(hardware, output, clock = { now }, log = log)

    @Test
    fun nothingPerformsWhileNotForeground() {
        val output = RecordingOutput()
        val f = feedback(output)
        f.haptic(Haptic.Confirm)
        assertEquals(emptyList<HapticPlan>(), output.performed)
    }

    @Test
    fun aForegroundConfirmPerformsTheViewConstant() {
        val output = RecordingOutput()
        val f = feedback(output)
        f.setForeground(true)
        f.haptic(Haptic.Confirm)
        assertEquals(listOf(HapticPlan.ViewConstant(H.CONFIRM)), output.performed)
    }

    @Test
    fun aRefusedViewConstantRePlansWithoutTheView() {
        val output = RecordingOutput(allowView = false)
        val f = feedback(output)
        f.setForeground(true)
        f.haptic(Haptic.Confirm)
        assertEquals(
            listOf(HapticPlan.ViewConstant(H.CONFIRM), HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.65f)))),
            output.performed,
        )
    }

    @Test
    fun cuesAreStubsThatPerformNothingAndDoNotThrow() {
        val output = RecordingOutput()
        val f = feedback(output)
        f.setForeground(true)
        f.cue(Cue.Done)
        f.both(Haptic.Confirm, Cue.Done)
        assertEquals(1, output.performed.size) // only the haptic from both()
    }

    @Test
    fun theGateRateLimitsWhatTheEnginePerforms() {
        val output = RecordingOutput()
        val f = feedback(output)
        f.setForeground(true)
        f.haptic(Haptic.Select)
        now += 20
        f.haptic(Haptic.Select)
        assertEquals(1, output.performed.size)
    }

    @Test
    fun debugLogsSkipPlayAndCueStubLines() {
        val output = RecordingOutput()
        val lines = mutableListOf<String>()
        val f = feedback(output, log = { lines.add(it) })
        f.cue(Cue.Attention)
        f.haptic(Haptic.Confirm)
        f.setForeground(true)
        f.haptic(Haptic.Select)
        now += 20
        f.haptic(Haptic.Select)
        assertEquals(
            listOf(
                "cue Attention skip: no sound engine (android-app#28)",
                "haptic Confirm skip: app not in foreground",
                "haptic Select play View SEGMENT_TICK",
                "haptic Select skip: rate limited",
            ),
            lines,
        )
    }

    @Test
    fun hardwareCapsCarryTheProbeResults() {
        val caps = hardware.caps(hasView = true)
        assertEquals(34, caps.sdk)
        assertEquals(ALL_PRIMITIVES.toSet(), caps.primitives)
        assertEquals(true, caps.hasView)
        assertEquals(false, hardware.copy(hasVibrator = false).hasVibrator)
    }
}
