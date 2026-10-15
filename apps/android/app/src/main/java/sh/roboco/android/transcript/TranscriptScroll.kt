package sh.roboco.android.transcript

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest

/**
 * The transcript's gesture plumbing (ticket 68): one modifier chain that
 * (1) reports the box size to the viewport, (2) observes pointers on the
 * `Initial` pass for `tracking` and finger velocity without ever
 * consuming, (3) connects [TranscriptViewport.scrollByUser] to Compose
 * gestures through a custom [ScrollableState] and a follow-aware fling
 * wrapper, and (4) drives the tail spring with a `withFrameNanos` loop
 * that runs only while `springRunning` — idle transcripts schedule no
 * frames. Compose has no content insets, so the scrollable's raw deltas
 * are the model's dp deltas and the content scrolls under the header and
 * the bottom stack.
 *
 * No Material components, no `Animatable`: the spring is the per-frame
 * formula in [TranscriptViewport.springTick]. Ticket 134 wired its iOS
 * physics in as the defaults — [rememberIosFlingBehavior] (the
 * UIScrollView decay curve) as [flingBehavior],
 * [rememberTranscriptRubberBand] as [overscrollEffect] and the vertical
 * scroll indicator (decision TRANSCRIPT-D3) at the end of the chain —
 * so callers that want the platform behaviour must override both
 * parameters explicitly.
 *
 * `reverseDirection = true` is `verticalScroll`'s convention flipped to
 * this model's coordinates: a positive delta means the finger moves up,
 * the content moves toward the tail, and the offset grows.
 *
 * @param viewport the scroll model (usually `TranscriptState.viewport`).
 * @param flingBehavior 134's iOS deceleration curve by default.
 * @param overscrollEffect 134's rubber band by default. `null` opts out
 *   explicitly (the Android stretch is never installed here).
 * @param interactionSource 65's `scrollInteractions`, passed through for
 *   other consumers only — never used for follow logic (interaction
 *   collection is asynchronous and can arrive after the fling has begun,
 *   so drag start/end come from scroll deltas and pointer events).
 */
@Composable
fun rememberTranscriptScroll(
    viewport: TranscriptViewport,
    flingBehavior: FlingBehavior = rememberIosFlingBehavior(),
    overscrollEffect: OverscrollEffect? = rememberTranscriptRubberBand(viewport),
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    // The dp/px ratio, read inside the non-composable callbacks and
    // lambdas below, so a density change is picked up without rebuilding
    // the state or the modifier chain.
    val density by rememberUpdatedState(LocalDensity.current.density)

    // The raw-delta consumer (px, positive = finger up = toward the tail).
    // Compose sends px and the model works in dp; the consumed amount is
    // returned in px (`consumed dp × density`) — returning the wrong
    // value would break nested scrolling and stop flings dead.
    val state = remember(viewport) {
        lateinit var self: ScrollableState
        self = ScrollableState { px ->
            // A nested child's leftover (a horizontal scroller's drag reaches
            // here as performRawScroll with no delta on our axis, outside our
            // own scroll) is never a transcript drag start: scrollByUser(0)
            // would mark the transcript dragging and start the interactive
            // IME dismiss though nothing moved (InteractiveImeDismissTest).
            // Our own 0 (the rubber band's drag-start mark) arrives inside
            // our scroll and still goes through.
            if (px == 0f && !self.isScrollInProgress) {
                0f
            } else {
                viewport.scrollByUser(px / density) * density
            }
        }
        self
    }

    // Flags the momentum window around the inner behavior, so the
    // viewport's momentum re-latch (R7) sees exactly the fling.
    val fling = remember(viewport, flingBehavior) { FollowAwareFling(viewport, flingBehavior) }

    // The spring's frame driver (iOS CADisplayLink, TLV:503-538): one step
    // per display frame, only while the spring runs. After the spring
    // stops, no frame is requested.
    LaunchedEffect(viewport) {
        snapshotFlow { viewport.springRunning }.collectLatest { running ->
            if (!running) return@collectLatest
            while (viewport.springRunning) withFrameNanos { viewport.springTick(it) }
        }
    }

    return Modifier
        // The box height (dp), written before the first frame's landing
        // can be relied on (R12: the snap needs a real viewport).
        .onSizeChanged { viewport.setViewportHeight(it.height / density) }
        // Observes only, never consumes: the Initial pass sees the down
        // before `scrollable` (Main) starts its drag, and the up before
        // the fling begins — the release rule (R6) therefore runs first.
        // It also sees touches that row hosts consume (links, long
        // presses, horizontal scrollers from 116).
        .pointerInput(viewport) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                viewport.onPointerDown()
                var ended = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            tracker.addPosition(it.uptimeMillis, it.position)
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    ended = true
                    // dp/s, positive = finger down (UIKit's convention).
                    viewport.onPointerUp(tracker.calculateVelocity().y / density)
                } finally {
                    if (!ended) viewport.onPointerUp(0f) // cancelled gesture
                }
            }
        }
        .scrollable(
            state = state,
            orientation = Orientation.Vertical,
            overscrollEffect = overscrollEffect,
            reverseDirection = true,
            flingBehavior = fling,
            interactionSource = interactionSource,
        )
        // 134 (decision TRANSCRIPT-D3): the iOS vertical scroll indicator,
        // last in the chain so it draws over the rows. The host (65) and
        // the lab (135) get it with no call-site change.
        .transcriptScrollIndicator(viewport)
}

/**
 * Wraps a [FlingBehavior] so the viewport knows its momentum window
 * (`flinging`, iOS `isDecelerating`): [TranscriptViewport.onFlingStart]
 * before the inner fling runs, [onFlingEnd] after — including on
 * cancellation, via `finally`. The inner deltas still reach
 * [TranscriptViewport.scrollByUser] through the scroll scope, so the
 * momentum re-latch (R7) follows them.
 */
private class FollowAwareFling(
    private val viewport: TranscriptViewport,
    private val inner: FlingBehavior,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        viewport.onFlingStart()
        try {
            return with(inner) { performFling(initialVelocity) }
        } finally {
            viewport.onFlingEnd()
        }
    }
}
