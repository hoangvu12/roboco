package sh.roboco.android.session

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.design.Motion

/**
 * The transcript's bottom insets for one layout pass of the bottom stack
 * (android-ui-parity #74), the port of `viewDidLayoutSubviews`
 * (`ios:Session/SessionViewController.swift:367-379`):
 *
 * - [covered] is the effective viewport bottom inset — iOS's
 *   `adjustedContentInset.bottom`, which is what actually limits
 *   scrolling (`TranscriptListView.swift:218-220` adds the safe-area
 *   bottom back on top of `contentInset`). A full-window Android
 *   transcript must use **this** for its scroll range.
 * - [contentInset] is the iOS-literal `contentInset.bottom`
 *   (`max(0, covered − navBottom)`, `:372`), kept for parity checks; UIKit
 *   only stores it without the safe-area bottom because
 *   `contentInsetAdjustmentBehavior = .always` adds that back.
 */
@Immutable
data class SessionBottomInsets(
    /** The stack's top edge, from the container's top (the window top). */
    val stackTop: Dp,
    /** `H − stackTop + 8` — the transcript's viewport bottom inset. */
    val covered: Dp,
    /** `max(0, covered − navBottom)` — the iOS-literal content inset. */
    val contentInset: Dp,
    /** The navigation-bars bottom the inset was computed against. */
    val navBottom: Dp,
)

/**
 * The container's published state (android-ui-parity #74): what stays
 * reachable outside the layout pass. The transcript slot does **not** read
 * this — it receives the insets as a parameter in the same frame the stack
 * moved; [insets] exists for non-layout readers (the jump button's
 * visibility rule, ticket 131, and tests).
 *
 * Created with [rememberBottomStackState]; the container writes
 * [insets] and [composerBounds] itself, in the layout pass.
 */
@Stable
class BottomStackState internal constructor() {

    private var insetsState by mutableStateOf(
        SessionBottomInsets(stackTop = 0.dp, covered = 0.dp, contentInset = 0.dp, navBottom = 0.dp),
    )
    private var composerBoundsState by mutableStateOf(Rect.Zero)
    private var imeOverrideState by mutableStateOf<Dp?>(null)

    /**
     * The last published insets (snapshot state). Only written when the
     * stack top moved by more than 0.5 dp, the port of iOS's
     * `abs(list.contentInset.bottom - inset) > 0.5` guard (`:373`).
     */
    val insets: SessionBottomInsets
        get() = insetsState

    /**
     * The composer slot's bounds in container px (tests and the gallery;
     * ticket 77 anchors the arrival flight on its own composer node
     * instead). `Rect.Zero` until the composer is first placed.
     */
    val composerBounds: Rect
        get() = composerBoundsState

    /**
     * Debug and test only: a simulated IME height measured from the window
     * bottom, standing in for `WindowInsets.ime` where none exists (the
     * design gallery, Robolectric). `null` = the real insets. Production
     * code never writes it.
     */
    var imeOverride: Dp?
        get() = imeOverrideState
        set(value) {
            imeOverrideState = value
        }

    /** One [SlotMotion] per slot, remembered with the state. */
    internal val motions: Map<BottomStackSlot, SlotMotion> =
        BottomStackSlot.entries.associateWith { SlotMotion() }

    /** The stack top of the last published [insets] value, in dp. */
    internal var lastPublishedStackTop: Float = Float.NaN

    internal fun publish(insets: SessionBottomInsets) {
        val top = insets.stackTop.value
        if (lastPublishedStackTop.isNaN() || abs(top - lastPublishedStackTop) > 0.5f) {
            lastPublishedStackTop = top
            insetsState = insets
        }
    }

    internal fun setComposerBounds(bounds: Rect) {
        if (bounds != composerBoundsState) composerBoundsState = bounds
    }
}

/** Creates one [BottomStackState] for the screen (one per Session screen). */
@Composable
fun rememberBottomStackState(): BottomStackState = remember { BottomStackState() }

/**
 * One slot's animated extent (internal, one per slot, remembered inside
 * [BottomStackState]): the settled target, the spring that chases it, and
 * the interruption bookkeeping for `beginFromCurrentState`.
 *
 * `update` is called from the container's **measure** block; the spring
 * itself runs in a composition-scoped coroutine launched
 * `UNDISPATCHED`, so `snapTo(from)` lands synchronously in that measure
 * pass and the first animation frame is the next frame — the same pattern
 * Lazy layouts use for item placement animations. [extent] reads the
 * `Animatable`'s snapshot-backed value while running, so every animation
 * frame re-measures the layout only, with no recomposition.
 */
internal class SlotMotion {
    /** The settled extent, px. `NaN` before the first measure. */
    var target = Float.NaN

    /** The spring's current value; equals [target] once settled. */
    val anim = Animatable(0f)

    /** True while the spring is in flight (snapshot state, read in measure). */
    var running by mutableStateOf(false)

    private var job: Job? = null

    /** Identifies the newest run, so a cancelled run never clears [running]. */
    private var token = 0

    /** The slot's current extent: the animated value in flight, else the target. */
    fun extent(): Float = if (running) anim.value else target

    /**
     * Retargets the slot.
     *
     * - The first measure snaps (`target` was `NaN`).
     * - A non-structural change snaps — **unless** the slot is already in
     *   flight, in which case it retargets from the current value and
     *   velocity (`beginFromCurrentState`, so nothing jumps).
     * - A structural change (the first measure after a structural commit)
     *   springs from the current value with `Motion.BottomStackReflow`.
     */
    fun update(newTarget: Float, animate: Boolean, scope: CoroutineScope) {
        if (target.isNaN()) {
            target = newTarget
            return
        }
        if (abs(newTarget - target) < 0.5f) return
        val from = extent()
        val velocity = if (running) anim.velocity else 0f
        target = newTarget
        if (!animate && !running) return
        running = true
        job?.cancel()
        val mine = ++token
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                anim.snapTo(from)
                anim.animateTo(newTarget, Motion.BottomStackReflow.spec(), initialVelocity = velocity)
            } finally {
                if (mine == token) running = false
            }
        }
    }
}

/**
 * Tracks the render history between compositions and measures
 * (android-ui-parity #74): the last committed [BottomStackModel], the
 * structural [epoch] the measure pass compares against, and the
 * first-render glass flag.
 *
 * The composable computes `pendingStructural` without mutating, commits in
 * a `SideEffect` (side effects run before the frame's layout, so the same
 * frame's measure sees the new epoch), and the measure block marks the
 * epoch consumed — exactly one animated measure per structural change.
 */
internal class ReflowTracker {
    /** The last committed model; `null` before the first composition. */
    var last: BottomStackModel? = null

    /** Bumped once per structural commit. */
    var epoch: Int = 0

    /** The epoch the measure block last consumed. */
    var lastMeasuredEpoch: Int = 0

    private var firstRender = true

    /**
     * The glass animation flag handed to the slot scopes: `false` on the
     * first composition, flipped by the first commit so the slots
     * recompose once and later appearances animate their glass in
     * (`SessionViewController.swift:129-130` and its "they used to stay
     * dematerialized" comment).
     */
    var animateGlass by mutableStateOf(false)

    fun commit(model: BottomStackModel, structural: Boolean) {
        last = model
        if (structural) epoch++
        if (firstRender) {
            firstRender = false
            animateGlass = true
        }
    }
}
