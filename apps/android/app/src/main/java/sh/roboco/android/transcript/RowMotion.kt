package sh.roboco.android.transcript

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import sh.roboco.android.design.Motion
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowKind

/**
 * The two row-motion kinds (ticket 132; iOS `rowAnimation(old:new:)`,
 * `ios:Transcript/TranscriptListView.swift:334-349`): a fold the user just
 * toggled tweens with the desktop fold (140 ms ease-out, TLV:343), and a
 * realized tool group that grew — a new call arrived — reveals over the
 * desktop reveal (360 ms expo-out, TLV:348). Both durations and curves
 * come from ticket 04's [Motion] tokens; never a literal, never
 * FastOutSlowIn (#609's bug).
 */
enum class RowMotionKind(val durationMs: Int, val easing: Easing) {
    FOLD(Motion.FoldMs, Motion.EaseOut),
    REVEAL(Motion.RowRevealMs, Motion.ExpoOut),
}

/** A row's geometry in dp (content coordinates). */
data class RowBox(val y: Float, val height: Float)

/** Old/new frame lookups, so the core is testable without the native lib. */
fun interface RowLookup {
    fun box(key: ULong): RowBox?
}

/** One frame's placements as a [RowLookup] — `indexOf` then `placement`. */
fun LayoutFrame.lookup(): RowLookup = RowLookup { k ->
    indexOf(k)?.let { i -> placement(i)?.let { p -> RowBox(p.y, p.height) } }
}

/**
 * The transcript's row motion (ticket 132): the S1–S4 fold/reveal
 * selection per applied frame and the **additive** per-key segment tracks
 * that keep realized rows sliding from their old placement to their new
 * one (UIKit's additive frame animations, reproduced without any per-row
 * `Animatable` and without #609's `snapTo(0f)` restarts).
 *
 * The drawn geometry of a key is its placement in the current frame plus
 * every live segment's remaining part:
 * `visualY = p.y + Σ dy·(1 − easing(fraction))` (height likewise), with
 * `fraction = 0` until the first frame tick stamps the segment's start —
 * so a row draws exactly where it was until the tween's first tick, and a
 * second animated frame mid-flight adds a segment measured against the
 * old frame's placement, which is continuous with what is on screen (the
 * in-flight segments carry the remainder).
 *
 * Fed by [TranscriptState.apply] **before** the frame listeners, with the
 * rows realized for the old frame; read by the host's measure and place
 * passes, the Tools-row clip and [RealizedRow]'s drawn geometry. Main
 * thread only.
 */
@Stable
class RowMotion {

    /**
     * iOS `pendingFold` (`ios:Transcript/TranscriptListView.swift:161-163`):
     * the row a disclosure or tool toggle just flipped — its next height
     * change tweens as a fold. Written through [TranscriptState.pendingFold]
     * (a delegate to this field, so ticket 120's handlers and tests keep
     * working), always **before** the engine call and under Reduce Motion
     * too, as on iOS. Cleared only by [select], and only when the fold
     * actually runs; it survives reduce-motion and no-change frames. Main
     * thread only.
     */
    var pendingFold: ULong? = null

    /**
     * Debug only ("Slow ×10" in the gallery): multiplies every **new**
     * segment's duration. The scaled duration is fixed at creation, so
     * flipping this mid-tween never jumps a running segment. Always 1f in
     * release paths.
     */
    internal var debugTimeScale = 1f

    /** Bumped whenever tracks are added or removed; read by the host's measure pass and by the tick loop. */
    var version by mutableIntStateOf(0)
        private set

    /** The last frame tick (ns). Read in measure only while tracks exist. */
    var clockNanos by mutableLongStateOf(0L)
        private set

    /**
     * The kind the last [onFrameApplied] chose, `null` when it chose none
     * — read by the host tests; production never reads it.
     */
    internal var lastKind: RowMotionKind? = null
        private set

    /** Whether any track is live (the tick loop's condition). */
    val hasTracks: Boolean get() = tracks.isNotEmpty()

    /**
     * One additive motion: the row's remaining displacement, in dp. The
     * scaled duration (`durationMs · 1e6 · debugTimeScale`) is fixed at
     * creation; [start] is [UNSET] until the first tick after the apply.
     */
    private class Segment(
        val dy: Float,
        val dh: Float,
        val kind: RowMotionKind,
        val durationNanos: Long,
    ) {
        var start = UNSET
    }

    private val tracks = HashMap<ULong, ArrayList<Segment>>()

    /**
     * The S1–S4 selection (iOS `rowAnimation(old:new:)`, TLV:334-349),
     * exactly in order:
     * S1 — no old frame, or reduce motion → none (and `pendingFold` is
     * left as it was; iOS's guard runs before the fold check);
     * S2 — the pending row exists in both frames and its height differs
     * (exact `Float` `!=`) → FOLD, clearing the mark;
     * S3 — some **realized** Tools row's new height beats its old one
     * (missing from the new frame counts as 0, missing from the old frame
     * counts as +∞, so neither is growth) → REVEAL;
     * S4 — otherwise none. There is no width check.
     */
    internal fun select(
        old: RowLookup?,
        new: RowLookup,
        realizedTools: Iterable<ULong>,
        reduced: Boolean,
    ): RowMotionKind? {
        if (old == null || reduced) return null
        pendingFold?.let { k ->
            val a = old.box(k)?.height
            val b = new.box(k)?.height
            if (a != null && b != null && a != b) {
                pendingFold = null
                return RowMotionKind.FOLD
            }
        }
        val grew = realizedTools.any { k -> (new.box(k)?.height ?: 0f) > (old.box(k)?.height ?: Float.MAX_VALUE) }
        return if (grew) RowMotionKind.REVEAL else null
    }

    /**
     * Called from [TranscriptState.apply] after the frame swap: selects
     * the kind (S1–S4) and, when one was chosen, appends one segment per
     * realized row whose placement moved — `from` is the row's placement
     * in the **old** frame (never the drawn value: the live segments
     * already carry the in-flight remainder), `to` its placement in the
     * new one; equal placements and rows missing from either frame get no
     * track, so rows composed later land at their final placement. Rows
     * that grew or shrank without any selection (a streaming token) keep
     * their running segments against the new final. Returns the chosen
     * kind, `null` for none.
     */
    fun onFrameApplied(
        old: RowLookup?,
        new: RowLookup,
        realized: Collection<Pair<ULong, RowKind>>,
        reduced: Boolean,
    ): RowMotionKind? {
        val kind = select(old, new, realized.filter { it.second == RowKind.TOOLS }.map { it.first }, reduced)
        lastKind = kind
        if (kind == null) return null
        var appended = false
        for ((key, _) in realized) {
            val a = old!!.box(key) ?: continue
            val b = new.box(key) ?: continue
            if (a == b) continue
            val durationNanos = (kind.durationMs * 1_000_000L * debugTimeScale).toLong()
            tracks.getOrPut(key) { ArrayList(2) } += Segment(a.y - b.y, a.height - b.height, kind, durationNanos)
            appended = true
        }
        if (appended) version++
        return kind
    }

    /**
     * The row's drawn y (dp): the final placement plus every live
     * segment's remaining `dy`. Returns [finalY] itself while the key has
     * no track (idle rows read no state).
     */
    fun visualY(key: ULong, finalY: Float): Float = remaining(key, finalY) { s -> s.dy }

    /** The row's drawn height (dp), like [visualY] over `dh`. */
    fun visualHeight(key: ULong, finalHeight: Float): Float = remaining(key, finalHeight) { s -> s.dh }

    /** `final` plus every live segment's remaining [delta] (dp). */
    private fun remaining(key: ULong, final: Float, delta: (Segment) -> Float): Float {
        val list = tracks[key] ?: return final
        var value = final
        for (s in list) value += delta(s) * (1f - s.kind.easing.transform(fraction(s)))
        return value
    }

    /**
     * Whether [key] has a live track — the Tools-row clip's condition.
     * Reads [version] so a track appearing or ending invalidates whoever
     * observes this (the row's layer and its test-only semantics); the
     * intermediate ticks never touch `version`.
     */
    fun isAnimating(key: ULong): Boolean {
        version
        return tracks.containsKey(key)
    }

    /**
     * One frame tick: stamps unstarted segments with [frameNanos] (the
     * first tick after their apply — until then their fraction is 0),
     * drops finished segments (`frameNanos − start ≥ durationNanos`, the
     * stored scaled duration) and empty tracks, and bumps [version] when
     * a track was removed. Rows never re-read the clock while idle: the
     * host only calls this from a `withFrameNanos` loop that runs while
     * [hasTracks].
     */
    fun tick(frameNanos: Long) {
        clockNanos = frameNanos
        var removedTrack = false
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            val segments = iterator.next().value
            for (s in segments) if (s.start == UNSET) s.start = frameNanos
            segments.removeAll { s -> frameNanos - s.start >= s.durationNanos }
            if (segments.isEmpty()) {
                iterator.remove()
                removedTrack = true
            }
        }
        if (removedTrack) version++
    }

    /**
     * A row left composition (it left the realized band): its track is
     * dropped, as iOS removes the row view's animations (TLV:404-409). A
     * row that comes back lands at its final placement.
     */
    fun drop(key: ULong) {
        if (tracks.remove(key) != null) version++
    }

    /**
     * This segment's eased progress: 0 until the first tick stamps its
     * start, then `(clockNanos − start) / durationNanos` clamped to
     * [0, 1]. The subtraction is done in `Long` before converting — the
     * nanosecond clock is large enough that subtracting two converted
     * `Float`s would lose the elapsed milliseconds.
     */
    private fun fraction(s: Segment): Float {
        if (s.start == UNSET) return 0f
        val dt = clockNanos - s.start
        if (dt <= 0L) return 0f
        val f = (dt.toDouble() / s.durationNanos).coerceIn(0.0, 1.0)
        return f.toFloat()
    }

    private companion object {
        /** "No start yet" — the segment's fraction is 0 until the first tick. */
        const val UNSET = Long.MIN_VALUE
    }
}
