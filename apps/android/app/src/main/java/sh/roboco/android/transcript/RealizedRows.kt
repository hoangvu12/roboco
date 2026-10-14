package sh.roboco.android.transcript

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import sh.roboco.android.transcript.widgets.ShimmerClock
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One realized row of the host (ticket 65): the [placement] the row was
 * composed at, its [model] once built, and the fade-in animation. The
 * registry [TranscriptState.realized] is keyed by [key] and kept in sync
 * by [RememberObserver] — a row that leaves the band is forgotten, exactly
 * as iOS recycles its `RowView` and resets the row's alpha.
 *
 * The scroll coordinate contract (shared with 68, 116, 126, 130, 132,
 * 134): `topPx = round(p.y·d) − round(offset·d)` — both terms rounded
 * separately so adjacent rows keep a constant integer gap while
 * scrolling. Ticket 132 has landed and owns the formula's `p`: while a
 * tween runs, [topPx] and [heightPx] read the row's **drawn** placement
 * (its motion track), so hit-testing, link taps (126) and 129's
 * accessibility frames agree with what is on screen; iOS hit-tests on
 * the model frame instead (a ≤ 360 ms difference the ticket accepts).
 *
 * Main thread only.
 */
class RealizedRow internal constructor(
    val key: ULong,
    private val state: TranscriptState,
    initial: RowPlacement,
) : RememberObserver {

    /** The placement this row is currently composed at (written in composition). */
    var placement: RowPlacement by mutableStateOf(initial)

    /** The row's display model, `null` until [TranscriptState.resolveModel] ran. */
    var model: RowModel? = null

    /**
     * Ticket 117: bumped on the main thread right after an async upgrade
     * replaced [model]. Only this row's [RowHost] reads it (before it
     * resolves the model), so an applied upgrade recomposes this row,
     * and only it — 118's veil diffs the old and new model there. A
     * plain write to [model] alone would leave the host painting the
     * old model.
     */
    internal var upgradeTick by mutableIntStateOf(0)

    /** The fade-in animation, `null` for rows already at alpha 1. */
    internal var appear: Animatable<Float, AnimationVector1D>? = null

    /**
     * 118: the row's streaming veil, one per row host (grown in
     * composition, drawn in the host's layer-0 `drawBehind`). `null`
     * until the host first composed. Tests read this.
     */
    internal var veil: Veil? = null

    /** The row's current fade alpha (1 for known rows). Tests read this. */
    val appearAlpha: Float get() = appear?.value ?: 1f

    /**
     * The row's live scroller offsets (ticket 116): one entry per composed
     * scroller, fed by [RowScroller]. Snapshot-observable, main thread only.
     */
    val scrollers = ScrollerRegistry()

    /**
     * 122: the row's shimmer clock — one per row host, so the live group's
     * title sweep restarts only when the row re-enters composition, never
     * on a streaming version bump (iOS rebuilds the `ShimmerView` per
     * configure; Roboco keeps the phase, like desktop's per-group start
     * instant). Lazily built: only rows that ever host a live tool group
     * read it, and the frame loop runs only while a `Shimmer` widget is
     * composed.
     */
    internal val shimmerClock: ShimmerClock by lazy(LazyThreadSafetyMode.NONE) { ShimmerClock() }

    /** The current density (px per dp), kept by the row host. */
    internal var density: Float = 1f

    /**
     * The row's top on screen, px: `round(visualY·d) − round(offset·d)` —
     * the **drawn** y while a 132 tween runs (`rowMotion.visualY`, the
     * placement plus the live segments' remainder). [state.offset] is
     * read here, never in composition or measure.
     */
    fun topPx(): Int =
        (state.rowMotion.visualY(key, placement.y) * density).roundToInt() - (state.offset * density).roundToInt()

    /** The row's height, px: `max(0, round(visualHeight·d))` — the drawn height while a 132 tween runs. */
    fun heightPx(): Int = max(0, (state.rowMotion.visualHeight(key, placement.height) * density).roundToInt())

    /** The row's rect in window px (`null` data before the host is placed). */
    fun boundsInWindow(): Rect {
        val coordinates = state.hostCoordinates ?: return Rect(0f, 0f, 0f, 0f)
        return localRowRect().translate(coordinates.localToWindow(Offset.Zero))
    }

    /**
     * The horizontal scroller [index]'s live content offset (dp),
     * `ScrollState.value / density` (ticket 116); 0 while that scroller
     * is not composed. Snapshot-observable: reading it inside a snapshot
     * observer (semantics, draw, `snapshotFlow`) re-runs on scroll.
     */
    fun scrollerOffsetDp(index: Int): Float = scrollers.offsetDp(index)

    override fun onRemembered() {
        state.realized[key] = this
    }

    override fun onForgotten() {
        if (state.realized[key] === this) {
            state.realized.remove(key)
            // 132: the row left the band — its track is dropped (iOS
            // `removeAllAnimations`, TLV:404-409); a re-entering row lands
            // at its final placement.
            state.rowMotion.drop(key)
        }
    }

    override fun onAbandoned() = onForgotten()

    /** The row's rect in host-local px: full host width at [topPx]. */
    private fun localRowRect(): Rect {
        val top = topPx()
        val height = heightPx()
        val width = state.hostCoordinates?.size?.width ?: 0
        return Rect(0f, top.toFloat(), width.toFloat(), (top + height).toFloat())
    }
}

/**
 * The first realized row containing `yPx` (host-local, top-inclusive and
 * bottom-exclusive). Rows never overlap, so the first hit is the hit
 * (iOS `row(at:)`, `TranscriptListView.swift:600-602`).
 */
fun TranscriptState.rowAt(yPx: Int): RealizedRow? = realized.values.firstOrNull { row ->
    val top = row.topPx()
    yPx >= top && yPx < top + row.heightPx()
}

/**
 * The first user row's `USER_BUBBLE` box in window px — the new-session
 * handoff target (ticket 95 consumes it through 77's root variant). Only
 * **realized** rows count (the overscan band included), sorted top-down;
 * a user row without a bubble box (image-only rows carry `ChipBackground`
 * tiles instead) is skipped. `null` without a frame, before the host is
 * placed, or when no realized user row has a bubble.
 */
fun TranscriptState.firstUserBubbleBounds(): Rect? = firstUserBubble { coordinates, local ->
    local.translate(coordinates.localToWindow(Offset.Zero))
}

/**
 * Ticket 77's variant of [firstUserBubbleBounds]: the same box in **root**
 * px. Same selection rule, different final transform.
 */
fun TranscriptState.firstUserBubbleInRoot(): Rect? = firstUserBubble { coordinates, local ->
    local.translate(coordinates.localToRoot(Offset.Zero))
}

/**
 * The shared selection rule (iOS `firstUserBubble`,
 * `TranscriptListView.swift:540-551`): the bubble box in host-local px
 * ([local]), transformed by [transform] using the host's
 * [TranscriptState.hostCoordinates].
 */
private inline fun <R> TranscriptState.firstUserBubble(
    transform: (LayoutCoordinates, Rect) -> R,
): R? {
    val local = firstUserBubbleLocal() ?: return null
    val coordinates = hostCoordinates ?: return null
    return transform(coordinates, local)
}

/** The bubble box of the first realized user row that has one, host-local px. */
private fun TranscriptState.firstUserBubbleLocal(): Rect? {
    val users = realized.values
        .filter { it.placement.kind == RowKind.USER && it.model != null }
        .sortedBy { it.topPx() }
    for (row in users) {
        val box = row.model!!.display.boxes
            .firstOrNull { it.color == ColorRole.USER_BUBBLE && it.scroller == null }
            ?: continue
        val d = row.density
        return Rect(
            box.x * d,
            box.y * d,
            (box.x + box.w) * d,
            (box.y + box.h) * d,
        ).translate(0f, row.topPx().toFloat())
    }
    return null
}
