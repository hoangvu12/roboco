package sh.roboco.android.transcript

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.design.Motion

/**
 * The shared state of the interactive IME dismiss pipeline (133).
 *
 * - [active] is true while a transcript drag holds (or is acquiring) the
 *   IME animation and until the settle after the release finishes.
 *   56's IME rule reads it: while it is true, a mid-drag
 *   `isImeVisible == false` report does not blur the composer — the
 *   decision waits for the drag to end. When the drag ends with the IME
 *   hidden, the flag flips to false and 56's rule blurs with the settled
 *   state.
 * - [forceFallback] is the debug/gallery toggle: skip the controller and
 *   use the discrete release rule (hide only on release, past half).
 * - [debugMode] is the gallery readout's coarse pipeline state
 *   (idle / requesting / tracking / settling / fallback).
 */
object InteractiveImeDismiss {

    /** True while a transcript drag holds (or is acquiring) the IME. */
    var active by mutableStateOf(false)
        internal set

    /** Debug only (gallery): skip the controller, use the discrete fallback. */
    var forceFallback by mutableStateOf(false)

    /** Debug only (gallery readout): the pipeline's coarse mode. */
    var debugMode by mutableStateOf(MODE_IDLE)
        internal set
}

/** `debugMode` values. */
internal const val MODE_IDLE = "idle"
internal const val MODE_REQUESTING = "requesting"
internal const val MODE_TRACKING = "tracking"
internal const val MODE_SETTLING = "settling"
internal const val MODE_FALLBACK = "fallback"

/**
 * The [ImeControl] seam: `null` (the default) means the real platform
 * control; Robolectric tests provide a fake through
 * `CompositionLocalProvider(LocalImeControl provides fake)`.
 */
internal val LocalImeControl = staticCompositionLocalOf<ImeControl?> { null }

/**
 * Interactive IME dismiss (133) — the port of UIKit
 * `keyboardDismissMode = .interactive`
 * (`ios:Transcript/TranscriptListView.swift:182`): when a transcript
 * drag carries the finger down into the IME, the IME's top edge follows
 * the finger; dragging back up raises it again, never above its shown
 * frame. On release, past half closes (and 56's rule blurs the composer
 * to the capsule with the draft kept), otherwise it springs back up.
 * The transcript keeps scrolling the whole time — the observer only
 * **observes** pointer input on the `Final` pass and never consumes, so
 * tap-to-dismiss (81), link taps (126), image tiles (66) and the scroll
 * (68) still get every event.
 *
 * `imeNestedScroll()` is deliberately **not** used (Roboco adaptation):
 * it consumes downward deltas to hide the IME wherever the finger is,
 * opens the IME at the fling's tail, and is driven by scroll deltas
 * rather than the finger position — three divergences from iOS.
 *
 * 65 applies this to the transcript host's own chain (after the caller's
 * modifier and `onPlaced`, before `scrollable`), so the session screen
 * and the Transcript Lab both get it. A new down while a settle is still
 * running takes over: the settle snaps to its target and finishes at
 * once, then the new gesture starts normally.
 *
 * @param viewport the scroll model; `viewport.dragging` (68 R5) is the
 *   "this is a transcript drag" gate.
 * @param hostCoordinates the host's latest coordinates, for mapping the
 *   finger into window space (the IME inset is measured from the window
 *   bottom; the tablet detail column, 33, is not at window x 0).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.interactiveImeDismiss(
    viewport: TranscriptViewport,
    hostCoordinates: () -> LayoutCoordinates?,
): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val control = LocalImeControl.current
        ?: remember(view, density) { AndroidImeControl(view, ime, imeTarget, density) }
    val session = remember(control) { ImeDragSession(control) { InteractiveImeDismiss.forceFallback } }
    val scope = rememberCoroutineScope()

    /** The running settle: its job and the release that started it. */
    val settle = remember { SettleState() }

    /** Mirrors the session's flags into the shared snapshot state. */
    fun sync() {
        InteractiveImeDismiss.active = session.active
        InteractiveImeDismiss.debugMode = when {
            settle.pending != null -> MODE_SETTLING
            InteractiveImeDismiss.forceFallback -> MODE_FALLBACK
            session.active && session.ready -> MODE_TRACKING
            session.active -> MODE_REQUESTING
            else -> MODE_IDLE
        }
    }

    /**
     * A new down while a settle runs: cancel it, snap the IME to the
     * settle's target and finish at once (Spec B, "new down while
     * settling"). The cancelled coroutine's `finally` is a no-op — it
     * only clears state while it still owns `settle.job`.
     */
    fun finishSettleNow() {
        val pending = settle.pending ?: return
        settle.job?.cancel()
        settle.job = null
        settle.pending = null
        val h = pending.handle
        if (!h.done) {
            h.set(pending.to)
            h.finish(shown = !pending.close)
        }
        session.settled()
        sync()
    }

    fun release(r: ImeDragSession.Release, fingerVelocityY: Float) {
        when (r) {
            ImeDragSession.Release.None -> {}
            ImeDragSession.Release.FallbackHide -> control.hide()
            is ImeDragSession.Release.Settle -> {
                settle.pending = r
                settle.job = scope.launch {
                    try {
                        val animatable = Animatable(r.from.toFloat())
                        // iOS's 0.25 s keyboard settle as a critically
                        // damped spring (Motion.ImeSettle), carrying the
                        // finger velocity (px/s, + = finger down, so the
                        // inset's velocity is the negative). The 0.5 px
                        // visibility threshold ends the animation once
                        // the rounded inset can no longer change; each
                        // step is clamped to [hidden, shown] by set().
                        animatable.animateTo(
                            r.to.toFloat(),
                            Motion.ImeSettle.spec(visibilityThreshold = 0.5f),
                            initialVelocity = -fingerVelocityY,
                        ) {
                            // Receiver is the Animatable: `value` is this
                            // frame's inset; set() clamps to [hidden, shown].
                            if (!r.handle.done) r.handle.set(value.roundToInt())
                        }
                        if (!r.handle.done) r.handle.finish(shown = !r.close)
                    } finally {
                        // Only the owner clears: a settle interrupted by a
                        // new down is finished synchronously by
                        // finishSettleNow (which drops `pending` first), and
                        // this coroutine's late cancellation must not
                        // clobber the new gesture's state.
                        if (settle.pending === r) {
                            settle.job = null
                            settle.pending = null
                            session.settled()
                            sync()
                        }
                    }
                }
                sync()
            }
        }
    }

    // Host disposal: finish a running settle, drop a pending request and
    // give a held controller back shown, so the IME never leaks mid-drag.
    DisposableEffect(session) {
        onDispose {
            finishSettleNow()
            session.cancelPending()
            InteractiveImeDismiss.active = false
            InteractiveImeDismiss.debugMode = MODE_IDLE
        }
    }

    return this.pointerInput(session, viewport) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            finishSettleNow()
            session.onDown()
            sync()

            val tracker = VelocityTracker()
            fun windowY(c: PointerInputChange): Float =
                hostCoordinates()?.localToWindow(c.position)?.y ?: c.position.y
            tracker.addPosition(down.uptimeMillis, Offset(0f, windowY(down)))

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                // Only the first (down) pointer is tracked; the up and
                // other unpressed changes of that pointer end the loop.
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, Offset(0f, windowY(change)))
                if (!change.pressed) break
                session.onMove(windowY(change), viewport.dragging)
                sync()
            }

            // A cancelled gesture arrives as a consumed, unpressed change
            // — a release at the last known position (Spec B). Velocity in
            // px/s, + = finger down.
            release(session.onRelease(), tracker.calculateVelocity().y)
            sync()
        }
    }
}

/** The running settle's bookkeeping (main thread only). */
private class SettleState {
    var job: Job? = null
    var pending: ImeDragSession.Release.Settle? = null
}

/**
 * The production [ImeControl] (133): `WindowInsets.ime` /
 * `WindowInsets.imeAnimationTarget` captured in composition (their
 * holders update per IME frame, so `getBottom` reads the live values from
 * a pointer handler) and `WindowInsetsAnimationControllerCompat` on the
 * activity's window. minSdk 31, so the platform controller always exists.
 */
internal class AndroidImeControl(
    private val view: View,
    private val ime: WindowInsets,
    private val imeTarget: WindowInsets,
    private val density: Density,
) : ImeControl {

    override fun windowHeight(): Int = view.rootView.height

    override fun imeNow(): Int = ime.getBottom(density)

    override fun imeTarget(): Int = imeTarget.getBottom(density)

    override fun request(listener: ImeListener): ImeRequest {
        val window = view.context.unwrapActivity()?.window
        if (window == null) {
            // No activity window (preview, detached view): the request is
            // refused at once — the session falls back to the discrete rule.
            listener.onCancelled(beforeReady = true)
            return ImeRequest { }
        }
        val signal = CancellationSignal()
        var ready = false
        WindowCompat.getInsetsController(window, view).controlWindowInsetsAnimation(
            WindowInsetsCompat.Type.ime(),
            -1L,
            LinearInterpolator(),
            signal,
            object : WindowInsetsAnimationControlListenerCompat {
                override fun onReady(controller: WindowInsetsAnimationControllerCompat, types: Int) {
                    ready = true
                    listener.onReady(Handle(controller))
                }

                override fun onFinished(controller: WindowInsetsAnimationControllerCompat) {
                    listener.onFinished()
                }

                // jspecify @Nullable in core 1.19 (null when cancelled
                // before onReady); Kotlin 2.x enforces it on overrides.
                override fun onCancelled(controller: WindowInsetsAnimationControllerCompat?) {
                    listener.onCancelled(beforeReady = !ready)
                }
            },
        )
        return ImeRequest { signal.cancel() }
    }

    override fun hide() {
        val window = view.context.unwrapActivity()?.window ?: return
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }

    /**
     * The live controller as an [ImeHandle]: every member reads the
     * controller at call time (bounds and finish state move under us),
     * and `set` clamps to `[hidden, shown]` — which also catches a settle
     * overshoot from a strong release velocity (Spec B).
     */
    private class Handle(private val c: WindowInsetsAnimationControllerCompat) : ImeHandle {
        override val hidden: Int get() = c.hiddenStateInsets.bottom
        override val shown: Int get() = c.shownStateInsets.bottom
        override val current: Int get() = c.currentInsets.bottom
        override val done: Boolean get() = c.isFinished || c.isCancelled

        override fun set(bottom: Int) {
            val v = bottom.coerceIn(hidden, shown)
            val span = shown - hidden
            val fraction = if (span > 0) ((shown - v) / span.toFloat()).coerceIn(0f, 1f) else 0f
            // runCatching: the platform can take the controller back
            // between the done check and the call; an uncaught throw
            // would otherwise kill the settle coroutine.
            runCatching { c.setInsetsAndAlpha(Insets.of(0, 0, 0, v), 1f, fraction) }
        }

        override fun finish(shown: Boolean) {
            if (!done) runCatching { c.finish(shown) }
        }
    }
}

/** Unwraps [ContextWrapper]s down to the hosting [Activity], or null (previews, tests). */
private tailrec fun Context.unwrapActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.unwrapActivity()
    else -> null
}
