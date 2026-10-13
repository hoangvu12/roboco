package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SessionScreenState] (android-ui-parity #75): the probe arithmetic
 * through [SessionScreenState.onFrameApplied] — the once-flag, the
 * `openedAtNanos` span, the `buildMicros` → ms conversion — and the lazy
 * transcript contract 76 relies on. Plain JUnit: the tests never read
 * [SessionScreenState.transcript], so the factory is never run.
 */
class SessionScreenStateTest {

    /** A state whose transcript factory must never be called. */
    private fun newState(openedAtNanos: Long = 0L): SessionScreenState = SessionScreenState(
        chatId = "chat-a",
        openedAtNanos = openedAtNanos,
        transcriptFactory = { error("the plain-JVM tests never touch the transcript") },
    )

    @Test
    fun anEmptyFrameReturnsNullAndDoesNotConsumeTheOnceFlag() {
        val state = newState()
        assertNull(state.onFrameApplied(rowCount = 0, buildMicros = 10, nowNanos = 5_000_000L))
        assertNull("the empty frame left the probe armed", state.onFrameApplied(0, 10, 5_000_000L))
        assertEquals(
            "so the next frame with rows still emits",
            "{\"openMs\":5.0,\"rows\":1,\"layoutPassMs\":0.01}",
            state.onFrameApplied(rowCount = 1, buildMicros = 10, nowNanos = 5_000_000L),
        )
    }

    @Test
    fun theFirstRowsFrameBuildsTheProbeJsonFromTheOpenedAtStamp() {
        val state = newState(openedAtNanos = 1_000_000_000L)
        assertEquals(
            "{\"openMs\":412.3,\"rows\":5,\"layoutPassMs\":2.50}",
            state.onFrameApplied(rowCount = 5, buildMicros = 2500, nowNanos = 1_412_340_000),
        )
    }

    @Test
    fun laterRowsFramesReturnNull() {
        val state = newState(openedAtNanos = 1_000_000_000L)
        assertNotNull("the first rows frame emitted the probe", state.onFrameApplied(5, 2500, nowNanos = 1_412_340_000))
        assertNull(state.onFrameApplied(6, 3000, nowNanos = 2_000_000_000L))
    }

    @Test
    fun theTranscriptIsLazyAndMainThreadOnly() {
        var factoryRuns = 0
        val state = SessionScreenState(
            chatId = "chat-a",
            openedAtNanos = 0L,
            transcriptFactory = {
                factoryRuns++
                error("not this test either")
            },
        )
        assertFalse("the factory does not run at construction", state.transcriptCreated)
        assertEquals(0, factoryRuns)
    }
}
