package sh.roboco.android.design.sheet

import android.content.res.Configuration
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.uiStyle

/**
 * The detents of a sheet (android-ui-parity #17).
 *
 * [Hidden] is off screen — the state's initial value on first composition
 * and the terminal value before [GlassSheet]'s `onDismiss`. [Medium] is
 * half height, [Large] full height (status bar + 10).
 */
enum class SheetDetent { Hidden, Medium, Large }

/**
 * The state of one [GlassSheet]: the detents it offers, the current
 * offset and the anchors, held in foundation's
 * [AnchoredDraggableState] — the only offset/anchor holder, exactly as
 * the ticket requires.
 *
 * `offset` is px from the **parent's** top (`NaN` before the first
 * layout), `current` is the settled detent, and [presentFraction] drives
 * scrim alpha and ticket 31's presenting-content recession.
 */
@Stable
class GlassSheetState internal constructor(
    /** The visible detents offered: a subset of `[Medium, Large]`, non-empty, never `Hidden`. */
    val detents: List<SheetDetent>,
    internal val draggable: AnchoredDraggableState<SheetDetent>,
) {
    init {
        require(detents.isNotEmpty()) { "detents must not be empty" }
        require(SheetDetent.Hidden !in detents) { "Hidden is not a visible detent" }
        require(detents.all { it == SheetDetent.Medium || it == SheetDetent.Large }) {
            "detents must be a subset of [Medium, Large], was $detents"
        }
    }

    /** The settled detent (the one the sheet is at, or is settling to). */
    val current: SheetDetent get() = draggable.settledValue

    /** The sheet's top offset, px from the parent's top; `NaN` before the first layout. */
    val offset: Float get() = draggable.offset

    /**
     * 0 at `Hidden` … 1 at the lowest visible detent (the anchor nearest
     * the bottom: `Medium` when the sheet has it, else `Large`). The scrim
     * fades with it during drags as well as animations, and ticket 31
     * drives its recession from it.
     */
    val presentFraction: Float
        get() {
            val hidden = anchors.positionOf(SheetDetent.Hidden)
            if (hidden.isNaN() || offset.isNaN()) return 0f
            val lowest = if (anchors.hasPositionFor(SheetDetent.Medium)) SheetDetent.Medium else SheetDetent.Large
            val lowestPosition = anchors.positionOf(lowest)
            if (lowestPosition.isNaN()) return 0f
            val span = hidden - lowestPosition
            if (span <= 0f) return 0f
            return ((hidden - offset) / span).coerceIn(0f, 1f)
        }

    /** Animates to [detent] with [GlassSheetDefaults.spec()]. */
    suspend fun animateTo(detent: SheetDetent) {
        draggable.animateTo(detent, GlassSheetDefaults.spec())
    }

    /**
     * Snaps to [detent] with no animation — ticket 31's handoff teardown
     * ("the sheet container goes to alpha 0 instantly and is dismissed
     * without animation", `MainTabController.swift:178-190`).
     */
    suspend fun snapTo(detent: SheetDetent) {
        draggable.snapTo(detent)
    }

    internal val anchors: DraggableAnchors<SheetDetent> get() = draggable.anchors

    internal fun dispatchRawDelta(delta: Float): Float = draggable.dispatchRawDelta(delta)
}

/**
 * Remembers one [GlassSheetState] for [detents]. The settled detent is
 * `rememberSaveable`d, so a configuration change restores it without
 * replaying the present animation; a fresh state starts at `Hidden` and
 * [GlassSheet] presents it to the smallest detent.
 */
@Composable
fun rememberGlassSheetState(detents: List<SheetDetent> = listOf(SheetDetent.Large)): GlassSheetState {
    val saved: MutableState<SheetDetent> = rememberSaveable(detents, stateSaver = SheetDetentSaver) {
        mutableStateOf(SheetDetent.Hidden)
    }
    val state = remember(detents) {
        val initial = saved.value.takeIf { it != SheetDetent.Hidden && it in detents }
            ?: SheetDetent.Hidden
        GlassSheetState(detents, AnchoredDraggableState(initialValue = initial))
    }
    // Keep the saved detent in step with the sheet, so the saveable
    // registry snapshots the live detent.
    LaunchedEffect(state) {
        snapshotFlow { state.current }.collect { saved.value = it }
    }
    return state
}

private val SheetDetentSaver = Saver<SheetDetent, String>(
    save = { it.name },
    restore = { SheetDetent.valueOf(it) },
)

/**
 * The receiver of [GlassSheet]'s `header` and `content` slots: the sheet's
 * [state] and its dismissal.
 */
@Stable
interface GlassSheetScope {
    val state: GlassSheetState

    /**
     * Animates the sheet to `Hidden`, then calls `onDismiss` — exactly
     * once, on every path (bar items, scrim tap at `Medium`, system back,
     * a drag past the threshold, this call). Safe to call twice.
     */
    fun dismiss()
}

/**
 * The Android page sheet (android-ui-parity #17): the port of iOS's
 * `UISheetPresentationController` — an opaque `Palette.background` card
 * that slides up from the window's bottom over a dimming scrim, with
 * medium/large detents, a grabber, drag/fling/back dismissal, the IME
 * grow-to-large rule and a nav-bar header.
 *
 * This is a plain composable overlay in the same window as the presenting
 * content — no `Dialog`/`Popup` (ticket 31 scales that content behind the
 * sheet, and glass must sample it). The caller hosts it above the app
 * content in a window-sized `Box`; on its own it fills the parent it is
 * given.
 *
 * Dismissal paths: a drag or fling past [GlassSheetDefaults.DismissFraction]
 * of the way to hidden (or faster than [GlassSheetDefaults.FlingVelocity]),
 * the scrim's tap while settled at `Medium` (a tap at `Large` is swallowed
 * and does nothing, matching ticket 31), predictive back (commit), the
 * header's bar items through [GlassSheetScope.SheetHeader], and the
 * container's `Dismiss` semantics action. [onDismiss] fires **after** the
 * exit animation, exactly once — keep the sheet composed until then.
 *
 * Haze (ticket 07's rule: glass never sits inside the node that carries
 * `hazeSource`): the content slot is the sheet's own `hazeSource`, and the
 * header's glass circles are drawn as **siblings after it**, so they
 * sample the sheet fill/content instead of falling back. [glass] is the
 * same rule for a sheet whose own header lives in its content (wave-10 38's
 * model picker): glass controls laid over the whole sheet, after the
 * source, placed by the caller, without the header's content inset.
 *
 * Reduced motion: no special case — iOS does not change sheet presentation
 * under Reduce Motion (android-app#14:584 "everything else keeps
 * animating"), and Compose's duration scale already collapses the springs
 * when the system removes animations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    state: GlassSheetState = rememberGlassSheetState(),
    grabber: Boolean = false,
    cornerRadius: Dp = GlassSheetDefaults.CornerRadius,
    scrim: Boolean = true,
    title: String? = null,
    header: (@Composable GlassSheetScope.() -> Unit)? = null,
    glass: (@Composable GlassSheetScope.() -> Unit)? = null,
    content: @Composable GlassSheetScope.(bottomInset: Dp) -> Unit,
) {
    val palette = RobocoTheme.palette
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dismissal = remember(state, scope) { SheetDismissal(state, scope) { currentOnDismiss() } }
    val sheetScope = remember(dismissal, state) { GlassSheetScopeImpl(state, dismissal) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val parentHeightPx = constraints.maxHeight.toFloat()
        val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
        val flingVelocityPx = with(density) { GlassSheetDefaults.FlingVelocity.toPx() }
        val detents = state.detents

        // -------------------------------------------------------- anchors
        // Large = sb + 10, Medium = H × 0.5 (dropped on tiny windows),
        // Hidden = H. Keep the current detent; if it is no longer present
        // (Medium dropped), fall back to Large.
        LaunchedEffect(parentHeightPx, statusBarPx, detents) {
            val anchors = sheetAnchors(parentHeightPx, statusBarPx, density, detents)
            val keep = state.draggable.settledValue
            val target = if (anchors.hasPositionFor(keep)) keep else SheetDetent.Large
            state.draggable.updateAnchors(anchors, target)
        }

        // -------------------------------------------------------- present
        // First composition: Hidden → the smallest visible detent,
        // animated. A restored saved detent skips the replay: snap to it.
        LaunchedEffect(state) {
            val smallest =
                if (state.anchors.hasPositionFor(SheetDetent.Medium)) SheetDetent.Medium else SheetDetent.Large
            val settled = state.current
            if (settled != SheetDetent.Hidden && state.anchors.hasPositionFor(settled)) {
                state.snapTo(settled)
            } else {
                state.animateTo(smallest)
            }
        }

        // ------------------------------------------------------------ IME
        // The keyboard shown while at Medium grows the sheet to Large
        // (UIKit does the same to make room).
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, state) {
            if (imeVisible && state.current == SheetDetent.Medium && SheetDetent.Large in state.detents) {
                state.animateTo(SheetDetent.Large)
            }
        }

        // ------------------------------------------------- predictive back
        PredictiveBackHandler(enabled = state.current != SheetDetent.Hidden && !dismissal.dismissing) { events ->
            val startDetent = state.current
            val startOffset = state.offset
            val hiddenOffset = state.anchors.positionOf(SheetDetent.Hidden)
            try {
                events.collect { event ->
                    // 25 % of the way to hidden at progress 1, the same
                    // travel ticket 31's PageSheet uses.
                    val offset = startOffset + GlassSheetDefaults.PredictiveBackTravel *
                        (hiddenOffset - startOffset) * event.progress
                    state.draggable.anchoredDrag { dragTo(offset) }
                }
                // Released to commit: spring the rest of the way, then
                // dismiss (in the composition scope — this coroutine ends).
                dismissal.dismiss()
            } catch (e: CancellationException) {
                // Gesture abandoned: spring back to the detent it came
                // from, in the composition scope (this coroutine is
                // already cancelling and cannot suspend).
                if (!dismissal.dismissing) {
                    scope.launch { state.animateTo(startDetent) }
                }
                throw e
            }
        }

        // -------------------------------------------------- drag machinery
        val onSettled: (SheetDetent) -> Unit = { detent ->
            if (detent == SheetDetent.Hidden) dismissal.settledAtHidden()
        }
        val nestedScrollConnection = remember(state, flingVelocityPx, dismissal) {
            SheetNestedScroll(state, flingVelocityPx, onSettled)
        }
        val fling = remember(state, flingVelocityPx, dismissal) {
            SheetFling(state, flingVelocityPx, onSettled)
        }

        // ---------------------------------------------------------- scrim
        // Black at 0.25 (light) / 0.45 (dark) × presentFraction, read at
        // draw time so drags never recompose. A tap dismisses only while
        // settled at Medium (UIKit dismisses on a tap above a medium
        // sheet; ticket 31 chose "do nothing" at Large so both sheet
        // implementations agree). The scrim consumes every gesture in
        // both cases, so nothing behind the sheet is ever reachable.
        val scrimAlpha =
            if (RobocoTheme.isDark) GlassSheetDefaults.ScrimAlphaDark else GlassSheetDefaults.ScrimAlphaLight
        if (scrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black, alpha = scrimAlpha * state.presentFraction) }
                    .clearAndSetSemantics { testTag = "glass-sheet-scrim" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                if (state.current == SheetDetent.Medium) dismissal.dismiss()
                            }
                        }
                    },
            )
        } else {
            // No dimming, but still an input-consuming layer: ticket 31
            // may draw its own dimming, and the content behind a sheet
            // without one is never clickable through it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var released = false
                            while (!released) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) if (!change.isConsumed) change.consume()
                                released = event.changes.all { !it.pressed }
                            }
                        }
                    },
            )
        }

        // --------------------------------------------------- sheet surface
        // Height H − min(offset, offsetOf(settled)): dragging down from a
        // detent keeps the height and slides the bottom off screen;
        // expanding up grows it, so the bottom never gaps. Read through
        // derivedStateOf so re-layout happens only while the height value
        // actually changes (while expanding), never on a plain drag.
        // Before the anchors exist the offset is NaN: a sheet that enters
        // the composition through a recomposition (an overlay request, a
        // keyed swap) is measured and placed in that same frame, before the
        // anchors effect above has run. It lays out as Hidden — zero height,
        // parked at the parent's bottom — until the anchors land.
        val sheetHeightPx: State<Float> = remember(state, parentHeightPx) {
            derivedStateOf {
                if (state.offset.isNaN()) return@derivedStateOf 0f
                val settledPosition = state.anchors.positionOf(state.current)
                val cap = if (settledPosition.isNaN()) state.offset else settledPosition
                (parentHeightPx - min(state.offset, cap)).coerceAtLeast(0f)
            }
        }
        val shape = remember(cornerRadius) { SmoothTopRoundedShape(cornerRadius) }
        Box(
            Modifier
                .offset {
                    val offset = state.offset
                    IntOffset(0, if (offset.isNaN()) parentHeightPx.roundToInt() else offset.roundToInt())
                }
                .fillMaxWidth()
                .sheetHeight { sheetHeightPx.value }
                .clip(shape)
                .background(palette.background)
                .consumeWindowInsets(WindowInsets.statusBars)
                .anchoredDraggable(state.draggable, Orientation.Vertical, flingBehavior = fling)
                // Taps on the sheet itself never reach the scrim (this
                // node processes the Main pass before the scrim's).
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag("glass-sheet")
                .semantics {
                    paneTitle = title ?: "Sheet"
                    isTraversalGroup = true
                    dismiss(label = "Dismiss") {
                        dismissal.dismiss()
                        true
                    }
                },
        ) {
            ProvideHazeState {
                // The sheet's children. The content slot below is the sheet's
                // own haze source, and the header's glass circles and the
                // grabber are **siblings after it**, never inside it
                // (ticket 07's feedback rule).
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = if (header != null) GlassSheetDefaults.HeaderHeight else 0.dp)
                            .nestedScroll(nestedScrollConnection)
                            .imePadding()
                            .hazeSource(LocalHazeState.current!!),
                    ) {
                        val bottomInset = with(density) {
                            WindowInsets.navigationBars.exclude(WindowInsets.ime).getBottom(density).toDp()
                        }
                        content(sheetScope, bottomInset)
                    }
                    if (header != null) {
                        Box(Modifier.fillMaxWidth().height(GlassSheetDefaults.HeaderHeight)) {
                            header(sheetScope)
                        }
                    }
                    if (glass != null) {
                        Box(Modifier.fillMaxSize()) { glass(sheetScope) }
                    }
                    if (grabber) {
                        val twoDetents =
                            SheetDetent.Medium in detents && SheetDetent.Large in detents
                        SheetGrabber(
                            modifier = Modifier.align(Alignment.TopCenter),
                            onClick = if (twoDetents) {
                                {
                                    val next =
                                        if (state.current == SheetDetent.Medium) SheetDetent.Large else SheetDetent.Medium
                                    scope.launch { state.animateTo(next) }
                                }
                            } else {
                                null
                            },
                            stateDescription = if (twoDetents) {
                                if (state.current == SheetDetent.Medium) "Half screen" else "Full screen"
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Everything the sheet measures and tunes (android-ui-parity #17). Starred
 * values (`*` in the ticket) are UIKit system metrics carried over from
 * `.scratch/android-app/issues/14-design-system.md` §4 and
 * `19-app-shell.md` §205-230, tuned once in the gallery against an iOS 26
 * simulator and then frozen.
 */
object GlassSheetDefaults {
    /** Top-corner radius of the sheet card. */
    val CornerRadius = 26.dp

    /** The gap between the status bar and a large sheet's top. */
    val LargeTopGap = 10.dp

    /** `H × 0.5`: the medium detent's fraction of the parent height. */
    const val MediumFraction = 0.5f

    /** The grabber bar: 36 × 5, centred, 5 from the sheet top. */
    val GrabberSize = DpSize(36.dp, 5.dp)

    /** The grabber bar's top edge, from the sheet top. */
    val GrabberTop = 5.dp

    /** The grabber's Android-only touch target (tap + a11y). */
    val GrabberTouchTarget = DpSize(44.dp, 20.dp)

    /** The header row's height. */
    val HeaderHeight = 52.dp

    /**
     * The header title's width clearance: `2 × (16 inset + 44 button + 8
     * gap)`. The title starts ellipsizing 8 dp before the bar items.
     */
    val TitleClearance = 136.dp

    /** A release at least this fast (dp/s) is a fling and moves one step. */
    val FlingVelocity = 1200.dp

    /** This fraction of the way from a detent to `Hidden` dismisses. */
    const val DismissFraction = 0.25f

    /** This fraction between two visible detents crosses to the lower one. */
    const val DetentFraction = 0.5f

    /** Scrim: black at this alpha in the light mode. */
    const val ScrimAlphaLight = 0.25f

    /** Scrim: black at this alpha in the dark mode. */
    const val ScrimAlphaDark = 0.45f

    /** Predictive back moves this fraction of the way to hidden at progress 1. */
    const val PredictiveBackTravel = 0.25f

    /**
     * The sheet spring: response 0.5 s, damping 1.0 (critically damped,
     * settle-matched stiffness (9.2334/0.5)² = 341.02). Ticket 04 has no
     * sheet token — UIKit's system sheet motion is dropped there and left
     * to this owner — so the token lives here, computed through
     * [IosSpring], never pasted.
     */
    val Spring = IosSpring("sheet", durationSec = 0.50, damping = 1.0f)

    /** The spec every sheet animation uses, with a 1 px visibility threshold. */
    fun spec(): AnimationSpec<Float> = Spring.spec(visibilityThreshold = 1f)
}

// ---------------------------------------------------------------------------
// Internals: the dismissal funnel, the scope, the fling, the height
// ---------------------------------------------------------------------------

/**
 * The single funnel for every dismissal path: `dismissing` latches on the
 * first one, the animation runs in the composition scope, and
 * [onDismiss] fires exactly once after the sheet reached `Hidden`.
 */
private class SheetDismissal(
    private val state: GlassSheetState,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    /** True once a dismissal started; the once-guard for [onDismiss]. */
    var dismissing by mutableStateOf(false)
        private set

    /** Bar items, scrim tap, back commit, programmatic `dismiss()`. */
    fun dismiss() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            try {
                state.animateTo(SheetDetent.Hidden)
                onDismiss()
            } catch (e: CancellationException) {
                // The exit animation was preempted (a new drag grabbed the
                // sheet) or the composition left. Only re-arm while the
                // composition is still alive; rethrowing cancellation is
                // contained to this coroutine.
                if (scope.isActive) dismissing = false
                throw e
            }
        }
    }

    /** A drag or fling that settled at `Hidden`: the animation already ran. */
    fun settledAtHidden() {
        if (dismissing) return
        dismissing = true
        onDismiss()
    }
}

private class GlassSheetScopeImpl(
    override val state: GlassSheetState,
    private val dismissal: SheetDismissal,
) : GlassSheetScope {
    override fun dismiss() = dismissal.dismiss()
}

/**
 * The settle of a finger drag on the sheet surface (grabber, header,
 * non-scrolling content): `anchoredDraggable` runs
 * [performFling] inside its own drag scope on release, so the settle must
 * animate through the [ScrollScope]'s `scrollBy` (which drives the same
 * `AnchoredDraggableState`) — never `state.animateTo`, which would contend
 * for the same `MutatorMutex`.
 */
private class SheetFling(
    private val state: GlassSheetState,
    private val flingVelocityPx: Float,
    private val onSettled: (SheetDetent) -> Unit,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val target = settleTarget(
            state.draggable.requireOffset(),
            initialVelocity,
            state.draggable.anchors,
            flingVelocityPx,
        )
        val targetPosition = state.draggable.anchors.positionOf(target)
        if (targetPosition.isNaN()) return 0f
        var previous = state.draggable.requireOffset()
        animate(
            initialValue = previous,
            targetValue = targetPosition,
            initialVelocity = initialVelocity,
            animationSpec = GlassSheetDefaults.spec(),
        ) { value, _ ->
            scrollBy(value - previous)
            previous = value
        }
        onSettled(target)
        return 0f
    }
}

/**
 * The sheet's height modifier: the measured height is `heightPx()` —
 * `H − min(offset, offsetOf(settled))` — read in the measure block through
 * [derivedStateOf], so a drag that does not change the height re-places
 * the sheet (the `offset` placement above) but never re-measures it.
 */
private fun Modifier.sheetHeight(heightPx: () -> Float): Modifier = layout { measurable, constraints ->
    val height = heightPx().roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(minHeight = height, maxHeight = height),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

// ---------------------------------------------------------------------------
// Previews (step 14): a striped presenting backdrop, a medium sheet with
// grabber + Done, a large sheet with Close. Each starts already at its
// detent — the snap runs inside the content slot, whose effects launch
// after the sheet's own present effect, so the preview frame is settled.
// ---------------------------------------------------------------------------

@Composable
private fun SheetPreviewContent(
    appearance: Appearance,
    state: GlassSheetState,
    grabber: Boolean,
    header: @Composable GlassSheetScope.() -> Unit,
) {
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.fillMaxSize())
            GlassSheet(
                onDismiss = {},
                state = state,
                grabber = grabber,
                title = "Sheet",
                header = header,
            ) { _ ->
                LaunchedEffect(state) { state.snapTo(state.detents.first()) }
                SheetPreviewText()
            }
        }
    }
}

/** The mono body the previews show inside the sheet (the tool-detail look). */
@Composable
private fun SheetPreviewText() {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        "$ cd ~/engines/roboco\n$ roboco engine start\nlistening on ws://0.0.0.0:8675\n" +
            "paired: macbook-pro\n$ ",
        Modifier.padding(16.dp),
        style = uiStyle(Face.Mono, typeScale.bodyScaled(13f), palette.text),
    )
}

@Preview(name = "Medium sheet light", widthDp = 360, heightDp = 740)
@Composable
private fun GlassSheetMediumLightPreview() {
    SheetPreviewContent(
        appearance = Appearance.Light,
        state = rememberGlassSheetState(listOf(SheetDetent.Medium, SheetDetent.Large)),
        grabber = true,
        header = { SheetHeader("Bash", trailing = SheetTrailing.Done) },
    )
}

@Preview(name = "Medium sheet dark", widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassSheetMediumDarkPreview() {
    SheetPreviewContent(
        appearance = Appearance.Dark,
        state = rememberGlassSheetState(listOf(SheetDetent.Medium, SheetDetent.Large)),
        grabber = true,
        header = { SheetHeader("Bash", trailing = SheetTrailing.Done) },
    )
}

@Preview(name = "Large sheet light", widthDp = 360, heightDp = 740)
@Composable
private fun GlassSheetLargeLightPreview() {
    SheetPreviewContent(
        appearance = Appearance.Light,
        state = rememberGlassSheetState(listOf(SheetDetent.Large)),
        grabber = true,
        header = { SheetHeader("New Session", leading = SheetLeading.Close) },
    )
}

@Preview(name = "Large sheet dark", widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassSheetLargeDarkPreview() {
    SheetPreviewContent(
        appearance = Appearance.Dark,
        state = rememberGlassSheetState(listOf(SheetDetent.Large)),
        grabber = true,
        header = { SheetHeader("New Session", leading = SheetLeading.Close) },
    )
}
