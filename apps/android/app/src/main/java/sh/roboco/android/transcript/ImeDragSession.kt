package sh.roboco.android.transcript

import kotlin.math.roundToInt

/**
 * One controlled IME animation (the platform seam over
 * `WindowInsetsAnimationControllerCompat`). All values are **px from
 * the window bottom**. A fake in tests implements the same interface.
 */
internal interface ImeHandle {
    /** The hidden state's bottom inset (0 for an ordinary IME). */
    val hidden: Int

    /** The shown state's bottom inset. */
    val shown: Int

    /** The controller's current bottom inset. */
    val current: Int

    /** `isFinished || isCancelled` — the controller is no longer usable. */
    val done: Boolean

    /**
     * Applies [bottom] (already clamped by the caller to
     * `[hidden, shown]`) with alpha 1 and the matching visibility
     * fraction; Compose reads the insets, not the fraction.
     */
    fun set(bottom: Int)

    /** `finish(shown)` — hands the animation back to the system. */
    fun finish(shown: Boolean)
}

/** A pending `controlWindowInsetsAnimation` request; cancel aborts it. */
internal fun interface ImeRequest {
    fun cancel()
}

/**
 * The controller's callbacks, routed into [ImeDragSession].
 * [onCancelled]'s `beforeReady` tells a refused request (the IME is not
 * controllable) from a takeover after tracking started.
 */
internal interface ImeListener {
    fun onReady(handle: ImeHandle)

    /** The controller finished (our own `finish`, or the system's). */
    fun onFinished()

    fun onCancelled(beforeReady: Boolean)
}

/**
 * The platform seam behind interactive dismiss (133): everything the
 * session needs from the window. `ImeControl` reads live values —
 * `imeNow()`/`imeTarget()` return the current insets, not composition
 * snapshots — so they are safe to call from a pointer handler on the
 * main thread.
 */
internal interface ImeControl {
    /** The root view's height, px. */
    fun windowHeight(): Int

    /** `WindowInsets.ime` bottom, px. */
    fun imeNow(): Int

    /** `WindowInsets.imeAnimationTarget` bottom, px. */
    fun imeTarget(): Int

    /**
     * `controlWindowInsetsAnimation(Type.ime(), -1, LinearInterpolator,
     * signal, listener)` — one per gesture; the returned request must be
     * kept and cancelled if the gesture ends before `onReady`.
     */
    fun request(listener: ImeListener): ImeRequest

    /** `WindowInsetsControllerCompat.hide(Type.ime())` — the fallback. */
    fun hide()
}

/**
 * The interactive IME dismiss state machine (133, the port of UIKit
 * `keyboardDismissMode = .interactive`): a transcript drag that carries
 * the finger down into the IME takes over the IME animation — the IME's
 * top edge follows the finger, never above its shown frame and never
 * below fully hidden; on release the IME closes if it was pulled past
 * half its height and springs back up otherwise.
 *
 * Pure Kotlin — no Android and no Compose types — so the release math
 * and the gesture rules are unit-testable on the plain JVM (the
 * platform seam is [ImeControl]). The composable layer
 * ([interactiveImeDismiss]) owns the pointer observer (Final pass, never
 * consuming) and the settle animation.
 *
 * Gesture rules (coordinates are window px, `y` grows down):
 * - **Armed** at the down: `imeNow > 0 && imeNow == imeTarget` — a
 *   gesture that starts while the IME is hidden or animating never
 *   tracks.
 * - **Start** on the first move where armed, the transcript's own drag
 *   passed slop (`dragging`, 68 R5) **and** the finger reached the IME's
 *   top edge (`y >= windowH − imeNow`); then the controller is requested
 *   once (`active` goes true, 56's IME rule gates on it).
 * - **Track**: every move sets the IME bottom to
 *   `windowH − y` clamped to `[hidden, shown]`; moves that arrived
 *   before `onReady` are remembered and the latest finger position is
 *   applied at once on ready.
 * - **Release**: `Settle(handle, from, to, close)` while the controller
 *   is live (`close` iff `current < hidden + (shown − hidden) / 2`,
 *   exactly half reopens); `FallbackHide` when the controller never
 *   became ready (or the fallback was forced) and the finger ended below
 *   the half line of the IME as it was at the down; `None` otherwise.
 * - **Cancel** (`onCancelled`): all state of the gesture is dropped; a
 *   refusal before `onReady` additionally switches this gesture to the
 *   discrete fallback, a takeover after it ends the gesture with no
 *   further action — not even the fallback (Spec B: "do nothing else").
 */
internal class ImeDragSession(
    private val control: ImeControl,
    private val forceFallback: () -> Boolean,
) {

    /** What the composable layer does with the release decision. */
    sealed interface Release {
        /** Nothing fires (no drag reached the IME, or a system takeover). */
        data object None : Release

        /**
         * The IME animates from [from] to [to] with `Motion.ImeSettle`
         * carrying the finger velocity, then `handle.finish(!close)`.
         */
        data class Settle(val handle: ImeHandle, val from: Int, val to: Int, val close: Boolean) : Release

        /** `control.hide()`: the discrete fallback closes the IME. */
        data object FallbackHide : Release
    }

    /**
     * True while a transcript drag holds (or is acquiring) the IME
     * animation, and until the settle after a release calls [settled].
     * Mirrored into `InteractiveImeDismiss.active` for 56's IME rule.
     */
    var active: Boolean = false
        private set

    /** Debug/gallery readout: a controller handle has been granted. */
    val ready: Boolean
        get() = handle != null

    private var armed = false
    private var imeAtArm = 0
    private var wanted = false
    private var lastY = Float.NaN
    private var handle: ImeHandle? = null
    private var request: ImeRequest? = null
    private var fallback = false

    private val listener = object : ImeListener {
        override fun onReady(h: ImeHandle) = this@ImeDragSession.onReady(h)

        override fun onFinished() {
            // The release path drives the state through settled(); the
            // platform's own finish needs nothing here.
        }

        override fun onCancelled(beforeReady: Boolean) = this@ImeDragSession.onCancelled(beforeReady)
    }

    /**
     * The down: armed iff the IME is settled and visible
     * (`imeNow > 0 && imeNow == imeTarget`); `imeAtArm` is that height,
     * the fallback's half-line reference. The rest of the gesture state
     * is reset; a running settle is finished by the composable layer
     * before this (a new down takes over).
     */
    fun onDown() {
        val now = control.imeNow()
        armed = now > 0 && now == control.imeTarget()
        imeAtArm = now
        wanted = false
        lastY = Float.NaN
        handle = null
        request = null
        fallback = forceFallback()
    }

    /**
     * One move. `dragging` is 68's `viewport.dragging` — it may flip one
     * move event after the slop crossing (the scrollable dispatches
     * deltas through a coroutine), so the start rule is re-checked on
     * every move; the worst case is starting one move (≈ 4–8 ms) later.
     */
    fun onMove(windowY: Float, dragging: Boolean) {
        lastY = windowY
        if (!wanted && armed && dragging && windowY >= control.windowHeight() - control.imeNow()) {
            wanted = true
            if (!fallback) {
                active = true
                request = control.request(listener)
            }
        }
        val h = handle
        if (h != null && !h.done) {
            h.set(target(control.windowHeight(), windowY, h.hidden, h.shown))
        }
    }

    /**
     * `onReady` from the platform: a floating or undocked IME reports no
     * bottom inset (`shown − hidden ≤ 0`) — give the controller back at
     * once and use the discrete fallback for the rest of the gesture
     * (iOS's `keyboardLayoutGuide` with `followsUndockedKeyboard = false`
     * behaves the same way). Otherwise remember the handle and apply the
     * latest finger position at once.
     */
    fun onReady(h: ImeHandle) {
        if (h.shown - h.hidden <= 0) {
            h.finish(true)
            fallback = true
            active = false
            return
        }
        handle = h
        if (!lastY.isNaN()) {
            h.set(target(control.windowHeight(), lastY, h.hidden, h.shown))
        }
    }

    /**
     * The system took the IME back (rotation, focus loss, app hide,
     * another show/hide call). A refusal before `onReady` switches to
     * the discrete fallback; a takeover after it ends the gesture with
     * nothing further — `wanted` and `armed` are cleared so neither the
     * release nor a later move re-requests the controller.
     */
    fun onCancelled(beforeReady: Boolean) {
        handle = null
        request = null
        active = false
        if (beforeReady) {
            fallback = true
        } else {
            wanted = false
            armed = false
        }
    }

    /**
     * The release (or a cancelled gesture, treated as a release at the
     * last known position). While the controller is live the settle owns
     * the decision; the pending request is cancelled otherwise (a slow
     * IME that never reported ready), and the discrete fallback fires
     * only if the finger ended below the half line of the IME as it was
     * at the down.
     */
    fun onRelease(): Release {
        if (!wanted) return Release.None
        val h = handle
        if (h != null && !h.done) {
            val close = closes(h.current, h.hidden, h.shown)
            return Release.Settle(h, h.current, if (close) h.hidden else h.shown, close)
        }
        request?.cancel()
        request = null
        active = false
        return if (fallbackCloses(control.windowHeight(), lastY, imeAtArm)) {
            Release.FallbackHide
        } else {
            Release.None
        }
    }

    /**
     * The settle after a `Settle` release finished (`finish` called): the
     * composable layer calls this from the animation's `finally`.
     */
    fun settled() {
        active = false
        handle = null
        request = null
    }

    /**
     * Host disposal: cancel a pending request; a handle still held
     * mid-drag is given back shown (the system re-shows the IME), so the
     * controller never leaks.
     */
    fun cancelPending() {
        request?.cancel()
        request = null
        val h = handle
        if (h != null && !h.done) h.finish(true)
        handle = null
        active = false
    }

    companion object {
        /** The IME bottom for a finger at `windowY`: `(windowH − y)` clamped to `[hidden, shown]`. */
        fun target(windowH: Int, windowY: Float, hidden: Int, shown: Int): Int =
            (windowH - windowY).roundToInt().coerceIn(hidden, shown)

        /**
         * The release rule: close iff the current inset is **past** half
         * the IME's height toward hidden — exactly half reopens (iOS:
         * "pulled past half its height").
         */
        fun closes(current: Int, hidden: Int, shown: Int): Boolean =
            current < hidden + (shown - hidden) / 2f

        /**
         * The discrete fallback's rule: hide iff the finger ended below
         * the half line of the IME as it was at the down.
         */
        fun fallbackCloses(windowH: Int, windowY: Float, imeAtArm: Int): Boolean =
            windowH - windowY < imeAtArm / 2f
    }
}
