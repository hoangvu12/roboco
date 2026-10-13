package sh.roboco.android.sessions

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.RobocoIcon

/**
 * `UISwipeActionsConfiguration` for one row (android-ui-parity #44): a
 * Compose-foundation swipe that reveals icon-only, full-height, tinted
 * action panes — leading (Pin/Unpin) and trailing (Archive, Move; only
 * Unarchive on the Archived list). The look and behaviour are
 * `SessionListController.swift:228-263`; the metrics UIKit does not
 * expose live in [SwipeTokens].
 *
 * This file holds the gesture half. The per-list wiring (which actions
 * each row gets, the long-press menu, the hook into 41's list) is
 * `SessionRowActions.kt`.
 */

/**
 * One swipe action — `UIContextualAction`. [title] is the VoiceOver label
 * (UIKit draws icon-only at the 62 pt row height), [background] the pane
 * tint, [destructive] the row's fate after a commit (slide out and stay).
 *
 * [perform] runs on the main thread at release/pane-tap time; it calls
 * [onAccepted] **at most once, any time later**, with `false` when the
 * write was refused — a destructive row then springs back. Non-destructive
 * actions may ignore it.
 */
@Immutable
class SwipeAction(
    val title: String,
    val icon: RobocoIcon,
    val background: Color,
    val destructive: Boolean = false,
    val testTag: String,
    val perform: (onAccepted: (Boolean) -> Unit) -> Unit,
)

// ---------------------------------------------------------------------------
// The coordinator (one open row per list)
// ---------------------------------------------------------------------------

/**
 * One per list: which row is open (the only one), the rows currently
 * composed (for closing them by id) and whether the list is scrolling
 * (a touch that stops a fling never starts a swipe).
 *
 * Created by [rememberSwipeCoordinator] — 41's list hosts it through
 * [rememberSessionRowActions] (ticket 44); 47's edit mode may call
 * [closeAll].
 */
@Stable
class SwipeCoordinator {

    /** The id of the one open row, or null. */
    var openId: String? by mutableStateOf(null)
        internal set

    /** The rows currently composed (registered by [SwipeRow]). */
    internal val rows = mutableMapOf<String, SwipeRowState>()

    /** Set by [CloseSwipesOnScroll]; read at gesture start. */
    internal var listScrolling: () -> Boolean = { false }

    /** Closes the open row, animated. No-op when none is open. */
    fun closeAll() {
        rows[openId]?.settle(0f)
    }

    /** The open row's coordinates, or null. */
    internal fun openRowCoords(): LayoutCoordinates? = rows[openId]?.coords
}

/** The [SwipeCoordinator] of the nearest list host. */
val LocalSwipeCoordinator = staticCompositionLocalOf<SwipeCoordinator?> { null }

/** Creates one [SwipeCoordinator] for a list. */
@Composable
fun rememberSwipeCoordinator(): SwipeCoordinator = remember { SwipeCoordinator() }

// ---------------------------------------------------------------------------
// The row state
// ---------------------------------------------------------------------------

/**
 * One row's swipe state. [offset] is the logical slide in px
 * (+ = leading side revealed; RTL flips it only at the physical layout
 * boundary), [arm] the full-swipe arming progress. Both are read in
 * layout/draw phases only — dragging re-lays out, it never recomposes.
 *
 * [isOpen] is the per-frame answer; composition uses [open], the
 * transition-sampled boolean (flips only when the row opens or finishes
 * closing), so a 350 ms settle does not recompose the row's content on
 * every frame.
 */
@Stable
class SwipeRowState internal constructor(
    internal val id: String,
    internal val scope: CoroutineScope,
) {
    internal val offset = Animatable(0f)
    internal val arm = Animatable(0f)
    internal var armed = false
    internal var dragging by mutableStateOf(false)

    /** Transition-sampled openness (see the class doc). */
    internal var open by mutableStateOf(false)

    /** The offset the drag started from (an open row drags further/back). */
    internal var base = 0f

    /** The row's own coordinates, for the list's close-on-touch. */
    internal var coords: LayoutCoordinates? = null

    /** Set by [SwipeRow] per composition. */
    internal var coordinator: SwipeCoordinator? = null
    internal var widthPx: () -> Float = { 0f }
    internal var reduceMotion: () -> Boolean = { false }

    /** Non-null in previews/screenshots: the fixed offset, no gestures. */
    internal var staticOffsetPx: Float? by mutableStateOf(null)

    /** The offset to lay out from: the static preview value, else the live one. */
    internal val currentOffsetPx: Float
        get() = staticOffsetPx ?: offset.value

    /**
     * The arming progress to lay out from. Static previews arm by rule
     * (`|preview| ≥ 0.6 × width`), so a 70 % preview shows Archive
     * covering the gap while a 50 % one keeps the equal split.
     * [rowWidthPx] is the width measured in the caller's measure block
     * ([widthPx] is 0 until the first `onGloballyPositioned` and is not
     * snapshot state, so it cannot drive the first layout).
     */
    internal fun currentArm(rowWidthPx: Float): Float =
        staticOffsetPx?.let { if (abs(it) >= SwipeTokens.FullSwipeFraction * rowWidthPx) 1f else 0f }
            ?: arm.value

    val isOpen: Boolean get() = dragging || offset.value != 0f

    // ------------------------------------------------------------ gestures

    /** The pointer is claimed: the row follows the finger from [base]. */
    internal fun dragStart() {
        base = offset.value
        dragging = true
        open = true
        coordinator?.openId = id
    }

    internal fun dragEnd() {
        dragging = false
    }

    // ------------------------------------------------- settles and commits

    /**
     * Springs the offset to [target] (0, +open, −open) with
     * [SwipeTokens.Settle]; snaps under reduce motion. Arriving at 0
     * resets the arm, the openness and the coordinator's open id.
     */
    internal fun settle(target: Float) {
        val c = coordinator
        scope.launch {
            animateOffset(target)
            if (target == 0f) resetClosed() else open = true
        }
    }

    /**
     * A released full swipe — the side's first action. Destructive
     * (Archive): the slide-out starts immediately and a later refusal
     * (`onAccepted(false)`) cancels it mid-flight (`Animatable` mutations
     * cancel the running one) and springs back. Non-destructive: out to
     * ±rowWidth and straight back, both with the settle spring; reduce
     * motion jumps back to 0 with no intermediate frames.
     */
    internal fun commit(action: SwipeAction, leading: Boolean) {
        val target = if (leading) widthPx() else -widthPx()
        if (action.destructive) {
            open = true
            // Launched first: a refusal reported synchronously still lands
            // after the slide-out job and therefore cancels it.
            scope.launch { animateOffset(target) }
            // A pane tap (or a11y action) arrives unarmed: arm the layout
            // so the destructive pane — always the side's first, outermost
            // action — grows over the row exactly as after a released full
            // swipe ("tapping Archive behaves like the full swipe"), instead
            // of the unarmed equal split leaving Move half the row. A
            // released full swipe is already armed: a no-op. A refusal's
            // settle(0) snaps the arm back in resetClosed().
            if (!armed) {
                armed = true
                scope.launch {
                    if (reduceMotion()) arm.snapTo(1f) else arm.animateTo(1f, SwipeTokens.Settle.spec())
                }
            }
            action.perform { ok -> if (!ok) settle(0f) }
        } else {
            action.perform { }
            scope.launch {
                if (!reduceMotion()) animateOffset(target)
                animateOffset(0f)
                resetClosed()
            }
        }
    }

    /**
     * A pane tap / a11y custom action. Destructive actions commit (the
     * toast follows the slide-out); everything else performs and starts
     * the close in the same frame — no waiting for the animation (iOS
     * presents the Move sheet and calls `done(true)` together,
     * `SessionListController.swift:256-259`).
     */
    internal fun tap(action: SwipeAction, leading: Boolean) {
        if (action.destructive) {
            commit(action, leading)
        } else {
            action.perform { }
            settle(0f)
        }
    }

    private suspend fun animateOffset(target: Float) {
        if (reduceMotion()) offset.snapTo(target)
        else offset.animateTo(target, SwipeTokens.Settle.spec(visibilityThreshold = 0.5f))
    }

    private suspend fun resetClosed() {
        arm.snapTo(0f)
        armed = false
        open = false
        val c = coordinator
        if (c != null && c.openId == id) c.openId = null
    }
}

// ---------------------------------------------------------------------------
// SwipeRow
// ---------------------------------------------------------------------------

/**
 * The row host: full-height, icon-only action panes behind the content,
 * the content sliding 1:1 under the finger, settle-open, full-swipe
 * commit, one row open per [SwipeCoordinator].
 *
 * Needs a bounded height (the list's fixed row height); the panes span it
 * fully, top to bottom, no inset, no radius, and the row clips to its
 * bounds so nothing draws outside it.
 *
 * @param id the row id — every state, key and coordinator entry hangs off it
 * @param leading the leading action (Pin/Unpin), or null (archived lists)
 * @param trailing the trailing actions, **outermost first** (Archive, Move)
 * @param enabled false (Pinned edit mode): no gesture, no menu, no custom
 *   actions; an open row closes
 * @param previewOffset static rendering for previews/screenshots: the fixed
 *   reveal; no gestures, no coordinator registration, `|preview| ≥ 60 %`
 *   of the width renders the armed layout
 * @param content the row content; [isOpen] is true while the row is open
 *   (tickets 16/41 use it to drop the context menu and swallow taps)
 */
@Composable
fun SwipeRow(
    id: String,
    leading: SwipeAction?,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    coordinator: SwipeCoordinator = LocalSwipeCoordinator.current ?: rememberSwipeCoordinator(),
    previewOffset: Dp? = null,
    content: @Composable (isOpen: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(id) { SwipeRowState(id, scope) }
    val leadNow = rememberUpdatedState(leading)
    val trailNow = rememberUpdatedState(trailing)
    val enabledNow = rememberUpdatedState(enabled)
    val feedback = LocalFeedback.current
    val reduce = LocalReduceMotion.current
    val static = previewOffset != null

    // Per-composition wiring (all idempotent writes).
    state.coordinator = coordinator
    state.reduceMotion = { reduce }
    state.staticOffsetPx = previewOffset?.let { with(LocalDensity.current) { it.toPx() } }

    if (!static) {
        DisposableEffect(id, coordinator) {
            coordinator.rows[id] = state
            onDispose {
                coordinator.rows.remove(id)
                if (coordinator.openId == id) coordinator.openId = null
            }
        }
        // Another row opened (drag claim, settle-open): close this one.
        LaunchedEffect(coordinator.openId) {
            val open = coordinator.openId
            if (open != null && open != id && !state.dragging && state.offset.value != 0f) {
                state.settle(0f)
            }
        }
        // Edit mode: no swipes, and an open row closes (animated).
        LaunchedEffect(enabled) {
            if (!enabled) state.settle(0f)
        }
    }

    // Logical → physical: in RTL the leading side is the right edge, so a
    // positive logical offset slides the content physically left. The
    // gesture maps raw (physical) pointer deltas through it; layout does
    // not — `Modifier.offset { }` already mirrors x in RTL.
    val dirSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val showPanes = static || state.open

    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clipToBounds()
            .semantics {
                if (enabledNow.value && !static) {
                    customActions = buildList {
                        leadNow.value?.let { action ->
                            add(CustomAccessibilityAction(action.title) {
                                state.tap(action, leading = true)
                                true
                            })
                        }
                        trailNow.value.forEach { action ->
                            add(CustomAccessibilityAction(action.title) {
                                state.tap(action, leading = false)
                                true
                            })
                        }
                    }
                }
            }
            .testTag("swipe-row-$id")
            .onGloballyPositioned { c ->
                state.coords = c
                state.widthPx = { c.size.width.toFloat() }
            }
            .then(
                if (static) {
                    Modifier
                } else {
                    Modifier.pointerInput(id, enabled, dirSign) {
                        detectRowSwipe(
                            state = state,
                            leading = leadNow,
                            trailing = trailNow,
                            enabled = enabledNow,
                            coordinator = coordinator,
                            feedback = feedback,
                            dirSign = dirSign,
                        )
                    }
                },
            ),
    ) {
        // 1. The panes, only in the revealed gap (behind the content).
        if (showPanes) {
            Box(Modifier.matchParentSize()) {
                leadNow.value?.let { action ->
                    SwipePanes(state, listOf(action), atLeading = true)
                }
                if (trailNow.value.isNotEmpty()) {
                    SwipePanes(state, trailNow.value, atLeading = false)
                }
            }
        }

        // 2. The content — fills the row, slides with the offset.
        Box(
            Modifier
                .matchParentSize()
                .offset { IntOffset(state.currentOffsetPx.roundToInt(), 0) },
        ) {
            content(static || state.open)
        }

        // 3. The close-tap catcher, over the slid content only: a tap on
        // an open row's content closes it without opening the session.
        if (!static && state.open && !state.dragging) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .matchParentSize()
                    .offset { IntOffset(state.currentOffsetPx.roundToInt(), 0) }
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                    ) { coordinator.closeAll() }
                    // This ticket's own choice (the ticket's `\*`): the
                    // label is not iOS copy.
                    .semantics { contentDescription = "Close actions" }
                    .testTag("swipe-close"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The panes
// ---------------------------------------------------------------------------

/**
 * One side's panes, placed in the revealed gap only — never under the
 * content. Pane 0 (index 0, the list's first action) sits at the side's
 * screen edge; [atLeading] anchors at the start edge, else the end edge.
 * The offset and arm are read in the measure block: a drag re-measures,
 * it does not recompose.
 */
@Composable
private fun SwipePanes(
    state: SwipeRowState,
    actions: List<SwipeAction>,
    atLeading: Boolean,
) {
    val buttonPx = with(LocalDensity.current) { SwipeTokens.ButtonWidth.toPx() }
    Layout(
        content = {
            actions.forEach { action ->
                SwipePane(action = action, innerIsEnd = atLeading, onTap = { state.tap(action, atLeading) })
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rtl = layoutDirection == LayoutDirection.Rtl
        val spans = paneSpans(abs(state.currentOffsetPx), actions.size, buttonPx, state.currentArm(w.toFloat()))
        layout(w, h) {
            measurables.forEachIndexed { i, measurable ->
                val span = spans.getOrNull(i) ?: return@forEachIndexed
                val paneWidth = (span.inner - span.outer).roundToInt()
                val pane = measurable.measure(Constraints.fixed(paneWidth, h))
                // Physical x: [outer, inner] measured from the side's edge.
                val x = when {
                    atLeading && !rtl -> span.outer
                    atLeading -> w - span.inner
                    !rtl -> w - span.inner
                    else -> span.outer
                }
                pane.place(x.roundToInt(), 0)
            }
        }
    }
}

/**
 * One pane: a full-height tinted box (no ripple, no radius) with the
 * white icon centred in its slot — `min(paneWidth, 74)` wide, glued to
 * the pane's **inner** edge (the content side; the logical End edge for
 * leading panes, the logical Start edge for trailing ones). The pane
 * clips its icon.
 */
@Composable
private fun SwipePane(
    action: SwipeAction,
    innerIsEnd: Boolean,
    onTap: () -> Unit,
) {
    val buttonPx = with(LocalDensity.current) { SwipeTokens.ButtonWidth.toPx() }
    val interaction = remember { MutableInteractionSource() }
    Layout(
        content = { IconInBox(action.icon, Color.White, SwipeTokens.IconBox) },
        modifier = Modifier
            .background(action.background)
            .clipToBounds()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .semantics { contentDescription = action.title }
            .testTag(action.testTag),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val icon = measurables.first().measure(Constraints())
        val slot = minOf(w.toFloat(), buttonPx)
        val centreX = if (innerIsEnd) w - slot / 2f else slot / 2f
        val x = (centreX - icon.width / 2f).roundToInt()
        val y = ((h - icon.height) / 2f).roundToInt()
        layout(w, h) { icon.placeRelative(x, y) }
    }
}

// ---------------------------------------------------------------------------
// The gesture detector
// ---------------------------------------------------------------------------

/**
 * The swipe gesture (`pr621:…/ui/ListGestures.kt`'s lock idea, this
 * ticket's numbers): observe until [lockDecision] is sure, claim only
 * clearly horizontal drags toward a side that can move, consume from
 * then on, release by [swipeRelease]. One coroutine, no launch per move.
 *
 * The pre-slop travel counts toward the offset (`base + (now − down)`),
 * like UIKit's cell catching up to the finger.
 */
private suspend fun PointerInputScope.detectRowSwipe(
    state: SwipeRowState,
    leading: State<SwipeAction?>,
    trailing: State<List<SwipeAction>>,
    enabled: State<Boolean>,
    coordinator: SwipeCoordinator,
    feedback: Feedback,
    dirSign: Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled.value || coordinator.listScrolling()) return@awaitEachGesture

        val slopPx = SwipeTokens.LockSlop.toPx()
        val buttonPx = SwipeTokens.ButtonWidth.toPx()
        val flingPx = SwipeTokens.FlingVelocity.toPx()

        /** Physical sign → "that side can move" (direction mapped through RTL). */
        fun canGo(dxSign: Int): Boolean {
            val towardLeading = (dxSign > 0) == (dirSign > 0f)
            return state.offset.value != 0f ||
                (towardLeading && leading.value != null) ||
                (!towardLeading && trailing.value.isNotEmpty())
        }

        // The latest dragged-to offset: the snap below is launched, so
        // `state.offset.value` may lag it by a frame at release.
        var lastX = state.offset.value

        /**
         * Clamp to the sides that exist, then follow the finger 1:1. Not
         * `suspend`: this block's scope is `@RestrictsSuspension`, so the
         * snap runs in the row's scope, undispatched (applied immediately
         * when the offset's mutex is free).
         */
        fun dragTo(x: Float) {
            val width = state.widthPx()
            val min = if (trailing.value.isEmpty()) 0f else -width
            val max = if (leading.value == null) 0f else width
            val clamped = x.coerceIn(min, max)
            lastX = clamped
            state.scope.launch(start = CoroutineStart.UNDISPATCHED) { state.offset.snapTo(clamped) }
            val nowArmed = abs(clamped) >= SwipeTokens.FullSwipeFraction * width &&
                (if (clamped > 0f) leading.value != null else trailing.value.isNotEmpty())
            if (nowArmed != state.armed) {
                state.armed = nowArmed
                if (nowArmed) feedback.haptic(Haptic.Threshold)
                val armTarget = if (nowArmed) 1f else 0f
                state.scope.launch {
                    if (state.reduceMotion()) state.arm.snapTo(armTarget)
                    // The ticket names no timing for the arm tween (UIKit
                    // internal); the settle token is the ticket's one spring.
                    else state.arm.animateTo(armTarget, SwipeTokens.Settle.spec())
                }
            }
        }

        fun release(v: Float) {
            val action = swipeRelease(
                x = lastX,
                v = v,
                widthPx = state.widthPx(),
                buttonPx = buttonPx,
                leadingCount = if (leading.value != null) 1 else 0,
                trailingCount = trailing.value.size,
                flingPx = flingPx,
            )
            when (action) {
                is SwipeRelease.Settle -> state.settle(action.target)
                is SwipeRelease.Commit -> {
                    val commit = if (action.leading) leading.value else trailing.value.firstOrNull()
                    if (commit == null) state.settle(0f) else state.commit(commit, action.leading)
                }
            }
        }

        var dx = 0f
        var dy = 0f
        var claimed = false
        val vt = VelocityTracker()
        while (true) {
            val event = awaitPointerEvent()
            val c = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) {
                if (claimed) {
                    c.consume()
                    state.dragEnd()
                    val v: Velocity = vt.calculateVelocity()
                    vt.resetTracking()
                    release(v.x * dirSign)
                }
                return@awaitEachGesture
            }
            val d = c.positionChange()
            if (!claimed) {
                // A child (the context menu's swallow) or the list's scroll
                // owns this pointer: give it up for good.
                if (c.isConsumed) return@awaitEachGesture
                dx += d.x
                dy += d.y
                when (lockDecision(dx, dy, slopPx, ::canGo)) {
                    LockDecision.Undecided -> continue
                    LockDecision.Reject -> return@awaitEachGesture
                    LockDecision.Horizontal -> {
                        claimed = true
                        state.dragStart()
                    }
                }
            }
            c.consume()
            vt.addPosition(c.uptimeMillis, c.position)
            dragTo(state.base + (c.position.x - down.position.x) * dirSign)
        }
        // The pointer vanished (system cancellation): close.
        if (claimed) {
            state.dragEnd()
            state.settle(0f)
        }
    }
}

// ---------------------------------------------------------------------------
// The list hooks
// ---------------------------------------------------------------------------

/**
 * On the element that contains the list (41's `SessionList` modifier, or
 * the `LargeTitleScaffold`'s): a touch **outside the open row** closes it
 * in the Initial pass and — when that touch ends as a tap — swallows the
 * up, so no session opens and no header folds. A moving touch scrolls the
 * list normally. Taps whose down is above [swallowTopPx] (the nav row:
 * the back circle and ⋯) are never swallowed.
 */
fun Modifier.swipeDismissOnTouch(
    coordinator: SwipeCoordinator,
    swallowTopPx: () -> Float = { 0f },
): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val swallow = rememberUpdatedState(swallowTopPx)
    this
        .onGloballyPositioned { coords = it }
        .pointerInput(coordinator) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val openRow = coordinator.openRowCoords() ?: return@awaitEachGesture
                val container = coords ?: return@awaitEachGesture
                if (container.localBoundingBoxOf(openRow).contains(down.position)) {
                    return@awaitEachGesture
                }
                coordinator.closeAll()
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                    }
                    if (!change.pressed) {
                        // Swallow the tap only; a scroll's moves were never
                        // consumed and the down was never consumed either.
                        if (!moved && down.position.y >= swallow.value()) change.consume()
                        break
                    }
                }
            }
        }
}

/**
 * Closes every open row when the list starts scrolling, and flags the
 * coordinator so a touch that stops a fling never starts a swipe.
 * Called by [rememberSessionRowActions]; screens hosting a list without
 * it call this directly.
 */
@Composable
fun CloseSwipesOnScroll(listState: LazyListState, coordinator: SwipeCoordinator) {
    LaunchedEffect(listState, coordinator) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect { coordinator.closeAll() }
    }
    DisposableEffect(listState, coordinator) {
        val previous = coordinator.listScrolling
        coordinator.listScrolling = { listState.isScrollInProgress }
        onDispose { coordinator.listScrolling = previous }
    }
}
