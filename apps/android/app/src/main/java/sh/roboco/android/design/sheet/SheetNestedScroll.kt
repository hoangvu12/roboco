package sh.roboco.android.design.sheet

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * The scroll/detent coupling of a sheet with scrolling content
 * (android-ui-parity #17): UIKit's default
 * `prefersScrollingExpandsWhenScrolledToEdge = true`, driven through
 * Compose nested scroll.
 *
 * The connection sits on the sheet's **content slot** (`GlassSheet` wires
 * it); every callback is in screen space, so `available.y < 0` is a finger
 * moving up and `available.y > 0` a finger moving down. Only
 * `NestedScrollSource.UserInput` is handled — fling-driven scrolling
 * dispatches as `SideEffect`, which we ignore on purpose (flings are
 * handled by [onPreFling]/[onPostFling] and the sheet's own
 * [settleSheet]), so a content fling that reaches its top never drags the
 * sheet down with it.
 *
 * - `onPreScroll` (finger up): if the sheet is above its `Large` anchor,
 *   it moves up first — the medium → large expansion happens **before**
 *   the content scrolls, like UIKit.
 * - `onPostScroll` (finger down, content at its top): the leftover delta
 *   moves the sheet down instead — pulling down at the content's top
 *   drags the sheet.
 * - `onPreFling` (release, velocity up): if the sheet is above `Large`,
 *   settle it with that velocity and consume all of it.
 * - `onPostFling` (release, leftover velocity): settle with the leftover
 *   velocity **only if this gesture moved the sheet** — a content fling
 *   that hits the top must not dismiss the sheet (iOS bounces the scroll
 *   view instead).
 */
internal class SheetNestedScroll(
    private val state: GlassSheetState,
    private val flingVelocityPx: Float,
    private val onSettled: (SheetDetent) -> Unit,
) : NestedScrollConnection {

    /** True once the first UserInput scroll of the current gesture arrived. */
    private var gestureStarted = false

    /**
     * The sheet was already off its settled anchor when the gesture began,
     * or one of the callbacks moved it during the gesture. Only then may a
     * leftover fling velocity settle the sheet.
     */
    private var movedThisGesture = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        markGestureStart()
        val dy = available.y
        // Finger moving up: expand the sheet before the content scrolls.
        if (dy < 0f && state.offset > largeAnchor() + SettleEpsilon) {
            val consumed = state.dispatchRawDelta(dy)
            if (abs(consumed) > 0f) movedThisGesture = true
            return Offset(0f, consumed)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (!gestureStarted) markGestureStart()
        val dy = available.y
        // Finger moving down and the content has nothing left (it is at
        // its top): the sheet takes over and moves down.
        if (dy > 0f) {
            val used = state.dispatchRawDelta(dy)
            if (abs(used) > 0f) movedThisGesture = true
            return Offset(0f, used)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Release while expanding: settle with the release velocity and
        // consume it all, so the content does not start a fling.
        if (available.y < 0f && state.offset > largeAnchor() + SettleEpsilon) {
            onSettled(settleSheet(state, available.y, flingVelocityPx))
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Next gesture starts with a fresh "moved" flag.
        gestureStarted = false
        if (!movedThisGesture) return Velocity.Zero
        onSettled(settleSheet(state, available.y, flingVelocityPx))
        return available
    }

    private fun markGestureStart() {
        if (gestureStarted) return
        gestureStarted = true
        val settledPosition = state.anchors.positionOf(state.current)
        movedThisGesture = !settledPosition.isNaN() && abs(state.offset - settledPosition) > SettleEpsilon
    }

    private fun largeAnchor(): Float = state.anchors.positionOf(SheetDetent.Large)
}

/**
 * Settles a released sheet with [settleTarget]'s rule and the sheet spring
 * (`GlassSheetDefaults.spec()`), starting from the release [velocity]
 * (px/s, positive = downward). Used by the nested-scroll paths; the
 * sheet-surface drag settles through the same math inside its own drag
 * scope (`SheetFling`). Returns the detent it settled at, so callers can
 * finish a dismissal when it is `Hidden`.
 *
 * Runs inside `AnchoredDraggableState.anchoredDrag` — the same drag mutex
 * a finger drag uses — which also sets the settled value to the anchor
 * the offset lands on when the block completes.
 */
internal suspend fun settleSheet(
    state: GlassSheetState,
    velocity: Float,
    flingVelocityPx: Float,
): SheetDetent {
    val draggable: AnchoredDraggableState<SheetDetent> = state.draggable
    val target = settleTarget(draggable.requireOffset(), velocity, draggable.anchors, flingVelocityPx)
    val targetPosition = draggable.anchors.positionOf(target)
    if (targetPosition.isNaN()) return target
    draggable.anchoredDrag {
        animate(
            initialValue = draggable.requireOffset(),
            targetValue = targetPosition,
            initialVelocity = velocity,
            animationSpec = GlassSheetDefaults.spec(),
        ) { value, _ ->
            dragTo(value)
        }
    }
    return target
}
