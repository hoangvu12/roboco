package sh.roboco.android.newsession

import android.os.Looper
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import sh.roboco.android.design.HandoffTimings
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.HandoffAnimator
import sh.roboco.android.shell.SessionRoute

/**
 * The draft handoff animator (android-ui-parity #95) — the port of
 * `ios:Session/DraftHandoff.swift:53-132`: the only place that knows the
 * timings and curves of the draft → chat motion. Ticket 32's router
 * pushes the session without animation, hides the IME and the sheet,
 * then calls [run]; this plays the dissolve, the composer glide, the
 * bubble flight and the two reveals over a window-level overlay host,
 * and calls [onFinished] exactly once (also on cancellation — the
 * deferred sheet teardown relies on it).
 *
 * Haptics: none (the iOS file has no feedback generator). Copy: none.
 * The overlays themselves are [HandoffOverlayHost]'s; the animator only
 * writes their state.
 */
class DraftHandoffAnimator internal constructor(
    /**
     * The host's overlay state: the animator adds the set here
     * synchronously in [run] (same frame as the router's push) and
     * removes it in `play`'s `finally`. Internal for the tests, which
     * read the set's progress and rects.
     */
    internal val overlays: HandoffOverlayState,

    /** The host's composition scope: main thread + MonotonicFrameClock. */
    private val scope: CoroutineScope,

    /** Reduce Motion, read **once** per [run] (`DraftHandoff.swift:59`). */
    private val reduceMotion: () -> Boolean = { ReduceMotion.isReduced },

    /** Route → arrival target; production uses [arrivalTargetOf]. */
    private val targetOf: (SessionRoute) -> HandoffArrivalTarget? = ::arrivalTargetOf,
) : HandoffAnimator {

    override fun run(handoff: DraftHandoff, session: SessionRoute, onFinished: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DraftHandoffAnimator.run must be called on the main thread"
        }
        val h = handoff as? CapturedDraftHandoff
        val target = targetOf(session)
        if (h == null || target == null) {
            // Ticket 32's Instant path: a foreign handoff or a route with
            // no arrival hooks reveals at once.
            session.finishArrival()
            onFinished()
            return
        }
        val timings = Motion.handoff(reduceMotion())
        val set = HandoffOverlaySet(h, timings, overlays.density)
        // Synchronous: the overlays land in the same frame as the router's
        // push and sheet hide, so the sheet never visibly vanishes before
        // the pictures cover it (`DraftHandoff.swift:60-67`).
        overlays.sets.add(set)
        scope.launch { play(set, h, target, timings, onFinished) }
    }

    /**
     * One handoff (suspend, on the host scope). The port of `animate` +
     * `waitForBubble` — every stage below runs 50 ms after [run] (the
     * router's push is one heavy commit; the motion starts on the next
     * frames so it isn't swallowed, `DraftHandoff.swift:69-75`).
     */
    private suspend fun play(
        set: HandoffOverlaySet,
        h: CapturedDraftHandoff,
        target: HandoffArrivalTarget,
        t: HandoffTimings,
        onFinished: () -> Unit,
    ) {
        var finished = false
        // The overlay-only coroutines (dissolve, glide, composer fade):
        // cancelled when the overlays go, never cancelling the reveals.
        val overlayJob = SupervisorJob(scope.coroutineContext[Job])
        val overlayScope = CoroutineScope(scope.coroutineContext + overlayJob)
        try {
            delay(t.startDelayMs)
            // Read once, at animate start — the second fallback of every
            // glide frame (`DraftHandoff.swift:80`).
            val composerTarget0 = target.arrivalComposerBounds()
            val from = h.composerFrame

            // 1. The scene dissolves (alpha 1 → 0, slight settle-scale).
            overlayScope.launch {
                animate(0f, 1f, animationSpec = tween(t.dissolveMs, easing = Motion.EaseOut)) { value, _ ->
                    set.sceneProgress = value
                }
            }

            // 2. The composer glides home, its target re-read **every
            //    frame**: the chat's composer slides down with the
            //    keyboard while this runs (`DraftHandoff.swift:87-94`,
            //    `:136-174`). The frame clock is the time base.
            overlayScope.launch {
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val e = glideProgress(now - start, t.glideMs)
                    val live = target.arrivalComposerBounds() ?: composerTarget0 ?: from
                    set.composerRect = glideRect(from, glideTarget(live, from), e)
                    if (e >= 1f) break
                }
            }

            // 3. The composer cross-fade and 4. the transcript reveal.
            val composerSpec = tween<Float>(
                durationMillis = t.composerFadeMs,
                delayMillis = t.composerFadeDelayMs,
                easing = Motion.EaseInOut,
            )
            overlayScope.launch {
                animate(1f, 0f, animationSpec = composerSpec) { value, _ ->
                    set.composerAlpha = value
                }
            }
            // On `scope`, not `overlayScope`: the reveals outlive the
            // overlays (with reduce motion the transcript's 350 ms fade
            // outlives the 153 ms flight) and must never be cancelled by
            // the overlay teardown — a cancelled reveal snaps the alpha
            // to 1 instead of fading (ticket 77's finally).
            scope.launch { target.revealArrivalComposer(composerSpec) }
            scope.launch {
                target.revealArrivalTranscript(
                    tween(
                        durationMillis = t.transcriptRevealMs,
                        delayMillis = t.transcriptRevealDelayMs,
                        easing = Motion.EaseOut,
                    ),
                )
            }

            // 5. The bubble: poll for the first user bubble (the
            //    transcript lays its first row around now), then fly.
            //    First read at animate start, then up to 20 retries
            //    16 ms apart — 21 reads at most (`:124-131`).
            var found: Rect? = null
            var tries = 0
            while (true) {
                found = target.arrivalBubbleBounds()
                if (found != null || tries >= t.bubblePollTries) break
                delay(t.bubblePollMs)
                tries++
            }
            val start = set.bubbleRect
            val dest = (found
                ?: fallbackBubbleTarget(start, overlays.hostWidth, overlays.safeTop, overlays.density))
                .translate(overlays.hostOrigin) // host coords → root (normally +0)
            // Lay the text out once, at the target's line breaks, for the
            // whole flight and fade.
            set.bubbleLayoutWidth = bubbleInner(dest, overlays.density).width.roundToInt().coerceAtLeast(1)
            animate(0f, 1f, animationSpec = t.bubbleSpring.spec<Float>(visibilityThreshold = 0.01f)) { p, _ ->
                set.bubbleRect = flightRect(start, dest, p)
                set.bubbleFill = p.coerceIn(0f, 1f)
            }
            target.finishArrival()
            finished = true
            // The final fade, then the finally removes the overlays.
            animate(1f, 0f, animationSpec = tween(t.bubbleFadeMs, easing = Motion.EaseInOut)) { value, _ ->
                set.bubbleAlpha = value
            }
        } finally {
            if (!finished) target.finishArrival()
            overlayJob.cancel() // stops a glide still running
            overlays.sets.remove(set) // releases both bitmaps
            onFinished() // exactly once, also on cancellation
        }
    }
}
