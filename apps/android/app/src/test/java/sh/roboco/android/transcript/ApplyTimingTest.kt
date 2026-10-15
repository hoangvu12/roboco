package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * 117's `maxApplyMs` insertion: [TranscriptState.apply] wraps its whole
 * body in the `apply-frame` timing, mirroring iOS's `defer`
 * (`ios:Transcript/TranscriptListView.swift:293-298`), so the bench
 * (136) reads a real number. Uses 61's fake-poster constructor (a frame
 * with a fixed revision and one style, zero rows — apply still runs the
 * style sync, the runway height and the viewport rules).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApplyTimingTest {

    /** 61-style fake: fixed revision, one style, no rows. */
    private class TimingFrame : LayoutFrame(NoHandle) {
        override fun revision() = 1uL
        override fun styleCount() = 1u
        override fun styles() = listOf(StyleDesc(1u, FaceRole.SANS, 16f, false))
        override fun rowCount() = 0u
        override fun totalHeight() = 0f
    }

    /** A silent engine: nothing is pulled, only `close`'s calls matter. */
    private class TimingEngine : TranscriptView(NoHandle) {
        override fun shutdown() {}
        override fun close() {}
    }

    private fun makeState(): TranscriptState {
        val engine = TimingEngine()
        return TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> engine }
    }

    @Test
    fun applyRecordsMaxApplyMsAndResetZeroesIt() {
        val state = makeState()
        try {
            TranscriptPerf.reset()
            assertEquals(0.0, TranscriptPerf.maxApplyMs, 0.0)

            state.apply(TimingFrame())
            assertTrue(
                "maxApplyMs was not recorded: ${TranscriptPerf.maxApplyMs}",
                TranscriptPerf.maxApplyMs > 0.0,
            )
            assertEquals(1uL, state.frame?.revision())

            TranscriptPerf.reset()
            assertEquals(0.0, TranscriptPerf.maxApplyMs, 0.0)
        } finally {
            state.close()
        }
    }
}
