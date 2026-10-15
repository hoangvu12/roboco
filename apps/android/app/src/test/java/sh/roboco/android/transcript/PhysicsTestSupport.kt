package sh.roboco.android.transcript

import androidx.compose.runtime.MonotonicFrameClock

/**
 * The physics suites' virtual display link (ticket 134's Verification
 * helper): every `withFrameNanos` advances the clock by [stepNanos] and
 * counts 1-based frames, so clock frame `n` is play time
 * `(n − 1)·step`. [onFrame] runs between the tick and the caller's
 * callback — a test injects a grab, a hold or a spring start there and
 * it lands between two bounce steps, exactly as a pointer event would.
 */
internal class StepFrameClock(
    private val stepNanos: Long,
    private val onFrame: (Int) -> Unit = {},
) : MonotonicFrameClock {

    /** The 1-based count of frames served so far. */
    var frame: Int = 0
        private set

    private var now = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        now += stepNanos
        this.onFrame(++frame)
        return onFrame(now)
    }
}
