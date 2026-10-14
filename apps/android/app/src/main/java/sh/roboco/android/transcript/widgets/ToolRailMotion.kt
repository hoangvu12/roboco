package sh.roboco.android.transcript.widgets

import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import sh.roboco.android.design.Motion

/**
 * What one tool-group rail draws when (ticket 121): the row the draw-in
 * starts from — `null` means fully drawn (static) — and whether the group
 * has never been seen before (the 90 ms first-row lead-in applies only to
 * a brand-new group).
 */
data class RailPlan(val animateFrom: Int?, val brandNew: Boolean)

/** ios:Transcript/ToolViews.swift:94-96, verbatim. */
fun planRail(known: Int?, count: Int, live: Boolean, quiet: Boolean): RailPlan = RailPlan(
    animateFrom = when {
        quiet -> null
        known != null -> if (known < count) known else null
        else -> if (live) 0 else null
    },
    brandNew = known == null,
)

/**
 * iOS `ToolRailView.revealed` + `quietUntil` (`ios:Transcript/ToolViews.swift:70-72, 64-99`):
 * process-wide, main thread only, never cleared in production — it grows by
 * one entry per tool group ever shown, exactly like the iOS statics (no
 * eviction was added on purpose). The quiet window is opened by
 * [onTranscriptAttached], which ticket 61 calls from `TranscriptState.apply`
 * on the first frame and on the first frame after an empty one (iOS opens
 * it inside the list view's `apply`, `TranscriptListView.swift:308-313`).
 *
 * Touched only from the main thread (composition and frame listeners), so
 * there is no locking.
 */
object ToolRailMemory {

    /** The 0.8 s quiet window (TranscriptListView.swift:312; [Motion.RailQuietWindowMs]). */
    val QuietWindowMs: Long = Motion.RailQuietWindowMs

    private val revealed = HashMap<ULong, Int>()

    /** Frames before this time are quiet: nothing animates, `revealed` still updates. */
    var quietUntilMs: Long = 0L
        private set

    /** Injected for tests; production reads [SystemClock.uptimeMillis] (iOS `CACurrentMediaTime`). */
    @VisibleForTesting
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** The last plan per key — a test hook only, never read by production code. */
    @VisibleForTesting
    val lastPlan = HashMap<ULong, RailPlan>()

    /** iOS `quietUntil = CACurrentMediaTime() + 0.8` (TranscriptListView.swift:310-313). */
    @MainThread
    fun onTranscriptAttached() {
        quietUntilMs = clock() + QuietWindowMs
    }

    /**
     * Decides one group's plan, once per model (iOS runs this in every
     * `configure`): records `max(known, count)` **even when quiet or under
     * reduce motion** — a group first seen during the quiet window is
     * recorded as fully revealed and never animates later, and a row is
     * never animated twice.
     */
    @MainThread
    fun plan(key: ULong, count: Int, live: Boolean, reduceMotion: Boolean): RailPlan {
        val known = revealed[key]
        revealed[key] = maxOf(known ?: 0, count)
        val quiet = clock() < quietUntilMs || reduceMotion
        return planRail(known, count, live, quiet).also { lastPlan[key] = it }
    }

    /** Tests only (the iOS app never clears anything). */
    @VisibleForTesting
    fun resetForTest() {
        revealed.clear()
        lastPlan.clear()
        quietUntilMs = 0L
        clock = { SystemClock.uptimeMillis() }
    }
}

/** One animated part of one row's draw-in. */
enum class RailPart { Continuation, Incoming, Branch, Cover }

/**
 * One animated part. [row] is the row whose geometry it uses — the
 * continuation drawn for row `i` uses row `i − 1`'s geometry (it is the
 * previous row's trunk extending down to the new row).
 */
data class RailAnim(val part: RailPart, val row: Int, val startMs: Int, val a: Double, val b: Double)

/**
 * The draw-in schedule, in iOS sublayer order (`ios:Transcript/ToolViews.swift:174-196`):
 * per animated row `i` the predecessor's continuation (not for row 0), the
 * incoming trunk, the branch and the cover, all starting together. `isFirst`
 * is `i == 0` — row 0 of the **group**, not the first animated row, so a
 * grown group's first new row uses the "child" windows and draws its
 * predecessor's continuation.
 */
fun railSchedule(first: Int, count: Int, brandNew: Boolean): List<RailAnim> = buildList {
    for (i in first until count) {
        val start = (if (brandNew) Motion.LeadInMs else 0) + (i - first) * Motion.StaggerMs
        val isFirst = i == 0
        if (!isFirst) add(RailAnim(RailPart.Continuation, i - 1, start, 0.0, 0.45))
        add(RailAnim(RailPart.Incoming, i, start, if (isFirst) 0.0 else 0.45, if (isFirst) 0.62 else 0.72))
        val branchFrom = if (isFirst) 0.58 else 0.68
        add(RailAnim(RailPart.Branch, i, start, branchFrom, 1.0))
        add(RailAnim(RailPart.Cover, i, start, branchFrom, 1.0))
    }
}

/** Total draw-in length: the last row's start plus the 480 ms connector. */
fun railTotalMs(first: Int, count: Int, brandNew: Boolean): Int =
    (if (brandNew) Motion.LeadInMs else 0) + (count - 1 - first) * Motion.StaggerMs + Motion.ConnectorMs

/** `Motion.quintOut(k / 24)` for k = 0…24 — the 25 CAKeyframeAnimation samples. */
private val QuintSamples = DoubleArray(25) { Motion.quintOut(it / 24.0) }

/**
 * One part's value at [elapsedMs]: the stroke fraction (0…1 of the segment's
 * arc length) for Continuation/Incoming/Branch, or the cover **alpha**
 * (1 → 0) for Cover.
 *
 * iOS animates a 25-sample piecewise-linear table, not the exact curve
 * (`CAKeyframeAnimation` with `calculationMode = .linear`,
 * `ios:Transcript/ToolViews.swift:201-228`): `v_k = f(local(quintOut(k / 24)))`,
 * linearly interpolated at `t · 24` with `t = clamp(elapsed / 480)` and
 * `fillMode = .both` — 0 before the start (covers 1) and the model value
 * after the end (strokes 1, covers 0).
 */
fun railValue(anim: RailAnim, elapsedMs: Float): Float {
    fun local(p: Double) = ((p - anim.a) / (anim.b - anim.a)).coerceIn(0.0, 1.0)
    val t = ((elapsedMs - anim.startMs) / Motion.ConnectorMs.toFloat()).coerceIn(0f, 1f).toDouble()
    val x = t * 24.0
    val k = minOf(23, x.toInt())
    val fraction = x - k
    val v = local(QuintSamples[k]) + (local(QuintSamples[k + 1]) - local(QuintSamples[k])) * fraction
    return (if (anim.part == RailPart.Cover) 1.0 - v else v).toFloat()
}
