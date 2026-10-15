package sh.roboco.android.debug

import androidx.compose.runtime.withFrameNanos
import sh.roboco.android.transcript.TranscriptViewport
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The Transcript Lab's scroll benchmark core (android-ui-parity #136) —
 * the port of iOS's `ScrollBench` (`upstream/main:apps/ios/Zeron/Debug/`
 * `TranscriptLabViewController.swift:225-297`): scripted, display-synced
 * flings through the transcript that measure hitch time per second of
 * scrolling (Apple's metric: < 5 ms/s good, > 10 critical).
 */

/**
 * What the bench scrolls: 68's viewport (offset in dp, the legal range,
 * the programmatic offset write and the follow release), reached through
 * a seam so the pure stepper and its JVM tests need no Compose state.
 * `setOffset` writes straight to the scroll model (iOS writes
 * `contentOffset`), which never re-latches follow.
 */
interface BenchScrollTarget {
    val offset: Float
    val minOffset: Float
    val maxOffset: Float
    fun setOffset(value: Float)
    fun releaseFollow()
}

/** [TranscriptViewport] as a [BenchScrollTarget] — a 1:1 delegation. */
fun TranscriptViewport.asBenchTarget(): BenchScrollTarget = object : BenchScrollTarget {
    override val offset: Float get() = this@asBenchTarget.offset
    override val minOffset: Float get() = this@asBenchTarget.minOffset
    override val maxOffset: Float get() = this@asBenchTarget.maxOffset
    override fun setOffset(value: Float) = this@asBenchTarget.setOffset(value)
    override fun releaseFollow() = this@asBenchTarget.releaseFollow()
}

/**
 * One phase's numbers. [json] is the iOS `Result.json` shape verbatim
 * (`ScrollBench` struct, `TranscriptLabViewController.swift:228-236`),
 * `Locale.ROOT` so every decimal is a dot.
 */
class BenchResult {
    var frames = 0
    var hitches = 0

    /** Total time frames were late beyond their deadline, ms. */
    var hitchMs = 0.0

    /** Total measured scrolling time, s. */
    var seconds = 0.0

    /** The slowest measured frame, ms. */
    var worstMs = 0.0

    val json: String
        get() = String.format(
            Locale.ROOT,
            "{\"frames\":%d,\"hitches\":%d,\"hitchRatioMsPerS\":%.2f,\"worstFrameMs\":%.1f,\"seconds\":%.2f}",
            frames,
            hitches,
            if (seconds > 0) hitchMs / seconds else 0.0,
            worstMs,
            seconds,
        )
}

/**
 * iOS `ScrollBench.tick`, pure — the whole fling script in one class:
 *
 *  - the run is [passes] × 2 flings; `direction` starts at −1 and
 *    [init] negates it, so the **first fling goes down** (+1, toward the
 *    tail). The lab sits at the tail when the bench starts (it was
 *    following), so the first measured tick clamps to `maxOffset` and
 *    that fling ends at once — iOS's quirk, kept on purpose so the frame
 *    counts are comparable;
 *  - velocity decays **before** the displacement:
 *    `v ← v · 0.998^(dt_ms)`, then `y ← offset + direction · v · dt`
 *    clamped with `min(max(y, min), max)` (never `coerceIn`, which
 *    throws when min > max);
 *  - a fling ends when `v < 60` or the clamped `y` reaches either end;
 *    the next fling starts at 5200 in the opposite direction;
 *  - per measured tick: `frames++`, `seconds += dt`; a hitch is
 *    `late = dt − budget > 0.5 · budget`, and it adds
 *    `hitchMs += late · 1000`; `worstMs = max(worstMs, dt · 1000)`;
 *  - the tick after the last fling returns `true` **without** updating
 *    `last` (iOS runs `done` before `last = link.timestamp`).
 *
 * Units: dp and dp/s (iOS pt 1:1); the math is `Double`, the target
 * offset is `Float`.
 */
class ScrollBenchStepper(private val target: BenchScrollTarget, passes: Int) {
    val result = BenchResult()
    private var remaining = passes * 2
    private var velocity = 0.0
    private var direction = -1.0
    private var last = 0L

    init {
        startFling()
    }

    /** iOS `startFling` — a hard flick. */
    private fun startFling() {
        velocity = 5200.0
        direction = -direction
    }

    /**
     * One display frame. The first call only records the clock; every
     * measured call runs the physics above and writes the offset.
     * Returns `true` when the run has finished.
     */
    fun tick(frameTimeNanos: Long, budgetNanos: Long): Boolean {
        if (last > 0L) {
            val dt = (frameTimeNanos - last) / 1e9
            val budget = budgetNanos / 1e9
            result.frames += 1
            result.seconds += dt
            val late = dt - budget
            if (late > budget * 0.5) {
                result.hitches += 1
                result.hitchMs += late * 1000
            }
            result.worstMs = max(result.worstMs, dt * 1000)
            // UIScrollView-like deceleration: decay, then displace.
            velocity *= 0.998.pow(dt * 1000)
            val minOffset = target.minOffset.toDouble()
            val maxOffset = target.maxOffset.toDouble()
            val y = min(max(target.offset + direction * velocity * dt, minOffset), maxOffset)
            target.setOffset(y.toFloat())
            if (velocity < 60.0 || y <= minOffset || y >= maxOffset) {
                remaining -= 1
                if (remaining <= 0) return true
                startFling()
            }
        }
        last = frameTimeNanos
        return false
    }
}

/**
 * Runs one bench phase on the composition's frame clock (the ADAPT of
 * iOS's `CADisplayLink`): every offset write happens **inside**
 * [withFrameNanos], so it lands in the same frame's layout — a raw
 * Choreographer callback can publish the snapshot write after the
 * traversal, which adds a one-frame lag that is not the app's fault.
 * [releaseFollow] runs synchronously here, before the first await: iOS's
 * `run(passes:done:)` calls it in the same main-thread turn that starts
 * the run, so a `send`'s follow spring never gets to tick.
 */
suspend fun runScrollBench(target: BenchScrollTarget, passes: Int, budgetNanos: () -> Long): BenchResult {
    target.releaseFollow()
    val stepper = ScrollBenchStepper(target, passes)
    while (!withFrameNanos { t -> stepper.tick(t, budgetNanos()) }) {
        // The run continues on the next display frame.
    }
    return stepper.result
}
