package sh.roboco.android.session

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize

/**
 * The new-session handoff's arrival target (android-ui-parity #77) — the
 * port of the arrival hooks of
 * `ios:Session/SessionViewController.swift:317-344` that the draft
 * handoff animator (ticket 95) drives:
 *
 * 1. [prepareArrival] — the hidden state: both alpha channels to 0, the
 *    open choreography skipped (no loader, no hold, no 8 s timer), the
 *    own turn started;
 * 2. [arrivalComposerBounds] / [arrivalBubbleBounds] — the glide and
 *    bubble flight targets, in root px, unclipped (the iOS
 *    `convert(_:to: window)` semantics);
 * 3. [revealArrivalComposer] / [revealArrivalTranscript] — the two
 *    fades, animated with the caller's specs (95's);
 * 4. [finishArrival] — everything visible again.
 *
 * Owned by 75's [SessionScreenState] and exposed by 76's
 * `SessionScreenRoute.arrival`, so the routers (32/106) can call
 * [prepareArrival] **before** the unanimated push — before the screen is
 * ever composed. Ticket 95 reads it through its `HandoffArrivalTarget`
 * adapter (`arrivalTargetOf(route)`), whose method names and parameter
 * types match the ones here exactly.
 *
 * Created with the route (any thread: plain snapshot state). The hooks
 * are main thread only. One invariant must hold (iOS
 * `TranscriptListView.beginOwnTurn` runs before any frame is applied):
 * the own turn starts before the transcript can apply a frame with rows.
 * Ticket 32 calls [prepareArrival] before the push, so the own turn is
 * remembered as pending and [bind] runs it when 75's `SessionScreen`
 * composes — always before `controller.attach(transcript.engine)`.
 */
@Stable
class SessionArrival {

    /**
     * iOS `list.alpha`: the transcript's arrival alpha, multiplied into
     * 65's host layer inside 75's `OpenRevealLayers` (draw-phase read
     * only). 1 until [prepareArrival], 0 until a transcript reveal.
     */
    var transcriptAlpha by mutableFloatStateOf(1f)
        private set

    /**
     * iOS `bottom.alpha`: the bottom stack's arrival alpha, multiplied
     * into 74's `stackAlpha` product (draw-phase read only). 1 until
     * [prepareArrival], 0 until a composer reveal.
     */
    var stackAlpha by mutableFloatStateOf(1f)
        private set

    /**
     * `true` from the first [prepareArrival] for this screen's lifetime
     * (never cleared: iOS never re-arms the loader either, because
     * `hasAppeared` becomes true). Ticket 75's open hold is suppressed
     * while this is set — the explicit port of iOS's `list.alpha > 0`
     * guard in `viewIsAppearing` (`SessionViewController.swift:245`).
     */
    var prepared by mutableStateOf(false)
        private set

    /** `true` between [prepareArrival] and [finishArrival]. Diagnostics and tests only. */
    var arriving by mutableStateOf(false)
        private set

    /**
     * The composer's latest coordinates, captured by
     * [arrivalComposerAnchor] (78 forwards it to 55's `ComposerSurface`
     * root, whose bounds are the glass capsule/card). A plain field on
     * purpose: only [arrivalComposerBounds] reads it, off the snapshot
     * system, and it is cheap enough to poll every frame of 95's glide.
     */
    internal var composerCoordinates: LayoutCoordinates? = null

    /** Ticket 69's `TranscriptState.beginOwnTurn`, bound by 75's `SessionScreen`. */
    private var beginOwnTurn: (() -> Unit)? = null

    /** Ticket 65's `TranscriptState.firstUserBubbleInRoot`, bound by 75's `SessionScreen`. */
    private var bubbleBounds: (() -> Rect?)? = null

    /** The own turn remembered by a pre-composition [prepareArrival], run by [bind]. */
    private var ownTurnPending = false

    /** One mutex per channel: a second reveal cancels the running one. */
    private val transcriptMutex = MutatorMutex()

    /** See [transcriptMutex]. */
    private val stackMutex = MutatorMutex()

    /** `true` while a transcript reveal is running on [transcriptMutex]. */
    private var transcriptRevealing = false

    /** `true` while a stack reveal is running on [stackMutex]. */
    private var stackRevealing = false

    /**
     * 75's `SessionScreen` calls this once at first composition, before
     * `controller.attach(transcript.engine)`:
     * `bind(transcript::beginOwnTurn) { transcript.firstUserBubbleInRoot() }`.
     * A second call replaces both lambdas and never re-runs the own
     * turn; if [prepareArrival] ran before the screen existed, the
     * pending own turn runs here, at bind (the iOS invariant: before
     * any frame with rows can be applied).
     */
    fun bind(beginOwnTurn: () -> Unit, bubbleBounds: () -> Rect?) {
        this.beginOwnTurn = beginOwnTurn
        this.bubbleBounds = bubbleBounds
        if (ownTurnPending) {
            ownTurnPending = false
            beginOwnTurn()
        }
    }

    /**
     * The hidden-arrival state (iOS `prepareArrival`,
     * `SessionViewController.swift:321-326`): both channels to 0, the
     * loader suppressed (`prepared`), `arriving` on, and the own turn
     * started — now if a transcript is bound, at [bind] otherwise.
     *
     * Main thread only; valid **before** the screen is composed (ticket
     * 32 calls it before the push). It does not trigger a layout: Compose
     * lays the screen out on the next frame, and the bounds hooks return
     * `null` until then.
     */
    fun prepareArrival() {
        transcriptAlpha = 0f
        stackAlpha = 0f
        prepared = true
        arriving = true
        val ownTurn = beginOwnTurn
        if (ownTurn != null) ownTurn() else ownTurnPending = true
    }

    /**
     * The composer's bounds in **root px**, unclipped (iOS
     * `arrivalComposerFrame(in:)` through `convert(_:to: window)`):
     * `positionInRoot()` plus the measured size — never
     * `boundsInRoot()`, which clips to ancestors. `null` before the
     * first layout or once the composer node is detached. Cheap enough
     * to call every frame of the composer glide (ticket 95 reads it on
     * each display-link frame). Do not use 74's
     * `BottomStackState.composerBounds`: container px, not root px.
     */
    fun arrivalComposerBounds(): Rect? {
        val coordinates = composerCoordinates?.takeIf { it.isAttached } ?: return null
        return Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    }

    /**
     * The first user bubble's bounds in root px (iOS
     * `arrivalBubbleFrame(in:)`): ticket 65's
     * `TranscriptState.firstUserBubbleInRoot()` — the first **realized**
     * `USER` row (the 700 dp overscan band included) that has a
     * `USER_BUBBLE` box without a scroller. `null` while unbound or
     * without a target; read fresh on every call (95 polls it up to
     * 20 × 16 ms).
     */
    fun arrivalBubbleBounds(): Rect? = bubbleBounds?.invoke()

    /**
     * iOS `revealArrivalComposer` over `bottom.alpha`: animate
     * [stackAlpha] from its current value to 1 with [spec]. With the
     * default [snap] it matches the plain iOS setter (visible on the
     * next frame). For reference, ticket 95 uses
     * `tween(180, delayMillis = 360, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))`
     * — durations and curves are the caller's, never ours.
     *
     * Must run in a coroutine with a `MonotonicFrameClock` in its
     * context — a composition `rememberCoroutineScope()` or a
     * `LaunchedEffect` (ticket 95's overlay host has one); a bare
     * `Dispatchers.Main` coroutine throws. A cancelled reveal (the
     * screen popped, the scope gone) never leaves the channel
     * invisible: the `finally` restores 1f. Calling it again on the
     * same channel cancels the running reveal.
     */
    suspend fun revealArrivalComposer(spec: AnimationSpec<Float> = snap()) =
        reveal(stackMutex, { stackAlpha }, { stackAlpha = it }, { stackRevealing = it }, spec)

    /**
     * iOS `revealArrivalTranscript` over `list.alpha`: animate
     * [transcriptAlpha] from its current value to 1 with [spec]. See
     * [revealArrivalComposer] for the clock and cancellation rules;
     * ticket 95's reference spec is
     * `tween(350, delayMillis = 120, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))`.
     */
    suspend fun revealArrivalTranscript(spec: AnimationSpec<Float> = snap()) =
        reveal(transcriptMutex, { transcriptAlpha }, { transcriptAlpha = it }, { transcriptRevealing = it }, spec)

    /**
     * iOS `finishArrival` (`SessionViewController.swift:343-344`):
     * `arriving` off. For each channel: a reveal still running on it is
     * left alone (it ends at 1f on its own — UIKit's "setting alpha = 1
     * while a reveal runs does not jump"); a channel with no reveal
     * running is set to 1f at once. Ticket 95 calls this in the bubble
     * spring's completion, outside any animation block.
     */
    fun finishArrival() {
        arriving = false
        if (!transcriptRevealing) transcriptAlpha = 1f
        if (!stackRevealing) stackAlpha = 1f
    }

    /**
     * One channel's reveal: animate [get] to 1 under [mutex] (a second
     * call on the same channel cancels the running one), and always
     * leave the channel at 1 — a cancelled reveal must never leave
     * content invisible (iOS has no such case; Compose cancellation
     * does).
     */
    private suspend fun reveal(
        mutex: MutatorMutex,
        get: () -> Float,
        set: (Float) -> Unit,
        flag: (Boolean) -> Unit,
        spec: AnimationSpec<Float>,
    ) = mutex.mutate(MutatePriority.Default) {
        flag(true)
        try {
            animate(get(), 1f, animationSpec = spec) { v, _ -> set(v) }
        } finally {
            set(1f)
            flag(false)
        }
    }
}

/**
 * Anchors the composer for the handoff (ticket 77): captures the node's
 * coordinates for [SessionArrival.arrivalComposerBounds]. 78 forwards
 * this to `ComposerBar` → 55's `ComposerSurface` root, whose bounds
 * equal the glass capsule/card (iOS pins the glass to all four edges of
 * `ComposerBar`). Anchor the composer itself, never the stack or the
 * slot wrapper: the bounds are the glide's target.
 */
fun Modifier.arrivalComposerAnchor(arrival: SessionArrival): Modifier =
    onGloballyPositioned { arrival.composerCoordinates = it }

/**
 * Blocks input and semantics while `alpha() < 0.01f` — UIKit's
 * `hitTest` rule (views under alpha 0.01 take no taps) plus the
 * TalkBack reading of it: invisible content is absent from the
 * semantics tree. Called from composition, on the node whose whole
 * subtree is the hidden content (75 wraps 65's host with it; 74 already
 * does the same for the stack slots).
 *
 * The content keeps composing and laying out while invisible: never
 * hide with `if`, `AnimatedVisibility` or node removal, or the bounds
 * hooks return `null` and the handoff falls back.
 */
@Composable
fun Modifier.arrivalHidden(alpha: () -> Float): Modifier {
    val currentAlpha by rememberUpdatedState(alpha)
    // Only the threshold crossing recomposes (about twice per arrival),
    // never every animation frame.
    val hidden by remember { derivedStateOf { currentAlpha() < 0.01f } }
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (currentAlpha() < 0.01f) event.changes.forEach { it.consume() }
                }
            }
        }
        .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)
}
