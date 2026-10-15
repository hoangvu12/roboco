package sh.roboco.android.design

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import sh.roboco.android.BuildConfig

/**
 * The iOS 26 alert (ticket 18): the twin of
 * `UIAlertController(preferredStyle: .alert)` — a dimmed scrim, one centred
 * Liquid Glass card (a leading-aligned title, an optional message, an
 * optional capsule field, grey capsule buttons, no hairlines), one alert at
 * a time with a FIFO queue, and the UIKit interaction rules (back and
 * outside-tap run the cancel action, IME Done fires primary, a handler runs
 * at most once).
 *
 * The card visual is [RobocoAlertCard] (AlertCard.kt, stateless); this file
 * owns the data model, the queue state and the in-window host. Callers own
 * every string.
 */

/** The button roles: `UIAlertAction.Style`. No alert call site uses Destructive; it is supported for completeness. */
enum class AlertButtonRole { Default, Cancel, Destructive }

/**
 * The alert's optional text field. Defaults are `UITextField`'s:
 * `autocapitalizationType = .sentences`, `autocorrectionType = .default`.
 */
@Immutable
data class AlertField(
    /** The pre-fill; the caret lands at its end (decision FOUNDATION-D2). */
    val text: String = "",
    val placeholder: String = "",
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    val autocorrect: Boolean = true,
)

/**
 * The alert's optional progress section (android-next#04, the update
 * alert's "Updating Roboco" state): a thin bar between the message and
 * the buttons plus an optional label (`"10.3 of 24.5 MB"`). Immutable on
 * purpose — callers push a fresh copy through [Alerts.updateCurrent] to
 * move it, which keeps the card's presentation (id, animations, field
 * state) alive.
 */
@Immutable
data class AlertProgress(
    /** The fraction of the bar, 0…1 (clamped). */
    val fraction: Float,
    /** The label under the bar; null draws the bar alone. */
    val label: String? = null,
)

/**
 * One alert action. [onClick] receives the field's **raw** text (or `""`
 * with no field); trimming and empty checks stay in the caller, as on iOS.
 * [requiresText] disables the button while the trimmed field text is empty
 * (PR #685's Create), re-enabling live as the user types.
 *
 * [preferred] (android-next#04) draws the button as the filled accent
 * capsule with white text — the approved update alert's "Update" — while
 * staying a normal button otherwise; at most one per alert (checked in
 * debug). [morphsInPlace] runs the handler and **keeps the alert open**,
 * expecting the handler to replace the content through
 * [Alerts.updateCurrent] (the update alert's A→B morph); the normal
 * resolve — handler, fade-out, next queued alert — is skipped.
 */
@Immutable
data class AlertButton(
    val title: String,
    val role: AlertButtonRole = AlertButtonRole.Default,
    val preferred: Boolean = false,
    val requiresText: Boolean = false,
    val morphsInPlace: Boolean = false,
    val onClick: (fieldText: String) -> Unit = {},
)

/** An alert: 1..n buttons, at most one of them [AlertButtonRole.Cancel] (checked in debug). */
@Immutable
data class RobocoAlert(
    val title: String,
    val message: String? = null,
    val field: AlertField? = null,
    val progress: AlertProgress? = null,
    val buttons: List<AlertButton>,
)

/** Shows alerts from anywhere: composable code, view models, any thread. */
interface Alerts {

    /** True while an alert is presented (read on the main thread). */
    val isShowing: Boolean
        get() = false

    /** Presents now, or after the alerts already shown/queued. Callable from any thread. */
    fun show(alert: RobocoAlert)

    /**
     * Replaces the **showing** alert's content in place (android-next#04):
     * the same card keeps its presentation id, so no re-animation, no
     * re-focus, no queue slot — the morph target of an
     * [AlertButton.morphsInPlace] handler. With nothing showing (or from
     * [Alerts.Noop]) the update is dropped and logged.
     */
    fun updateCurrent(alert: RobocoAlert)

    /** Clears the queue and fades the current alert out without running any handler. */
    fun dismissAll()

    companion object {
        /** Used outside an [AlertHost]: drops the alert and logs it. */
        val Noop: Alerts = object : Alerts {
            override fun show(alert: RobocoAlert) {
                Log.w("Alerts", "No AlertHost mounted; dropped: ${alert.title}")
            }

            override fun updateCurrent(alert: RobocoAlert) {
                Log.w("Alerts", "No AlertHost mounted; dropped update: ${alert.title}")
            }

            override fun dismissAll() {}
        }
    }
}

/** The nearest [Alerts] — the app root's `AlertHost` (ticket 35) or a local one. */
val LocalAlerts = staticCompositionLocalOf<Alerts> { Alerts.Noop }

/**
 * The queue state behind [AlertHost]: the visible alert ([current]) plus a
 * FIFO [queue] of waiting ones. Exactly one alert is visible at a time;
 * `show` while one is up (or dismissing) enqueues, and the next one
 * presents in the frame after the current is removed.
 *
 * [show] and [dismissAll] are thread-safe: applied immediately on the main
 * thread, posted to the main looper otherwise (ticket 111's engine replies
 * arrive on `Dispatchers.Default`). [current], [queue] and
 * [dismissRequested] are main-thread only.
 */
@Stable
class AlertHostState : Alerts {

    /** The presented alert, or `null` when none is up. */
    internal var current: Presented? by mutableStateOf<Presented?>(null)
        private set

    /** True while an alert is presented (main thread only, like [current]). */
    override val isShowing: Boolean
        get() = current != null

    /**
     * The id of the alert `dismissAll` asked to go away (it fades out
     * without running any handler); `null` otherwise.
     */
    internal var dismissRequested: Long? by mutableStateOf<Long?>(null)
        private set

    /** The waiting alerts, oldest first. Main thread only. */
    internal val queue = ArrayDeque<RobocoAlert>()

    private var nextId = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun show(alert: RobocoAlert) = onMain { present(alert) }

    override fun updateCurrent(alert: RobocoAlert) = onMain {
        // Same id: the composition's key(p.id) survives, so the card keeps
        // its animations and field state while the content swaps.
        current = current?.let { Presented(it.id, alert) }
    }

    override fun dismissAll() = onMain {
        queue.clear()
        dismissRequested = current?.id
    }

    /**
     * Called when the fade-out of alert `id` ended: present the next queued
     * alert (a fresh id, so it gets fresh field state and animations).
     */
    internal fun removed(id: Long) {
        if (current?.id != id) return
        current = queue.removeFirstOrNull()?.let { Presented(++nextId, it) }
        dismissRequested = null
    }

    private fun present(alert: RobocoAlert) {
        if (BuildConfig.DEBUG) {
            require(alert.buttons.count { it.role == AlertButtonRole.Cancel } <= 1) {
                "RobocoAlert allows at most one Cancel button (alert \"${alert.title}\")"
            }
            require(alert.buttons.isNotEmpty()) {
                "RobocoAlert needs at least one button (alert \"${alert.title}\")"
            }
            require(alert.buttons.count { it.preferred } <= 1) {
                "RobocoAlert allows at most one preferred button (alert \"${alert.title}\")"
            }
        }
        if (current != null) {
            queue.addLast(alert)
        } else {
            current = Presented(++nextId, alert)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /** One presented alert: a stable identity plus the description. */
    internal data class Presented(val id: Long, val alert: RobocoAlert)
}

/**
 * The in-window alert host (ticket 18): wraps a window's content, draws the
 * scrim and the card **above** it, and owns back handling, IME placement,
 * the present/dismiss animation and the FIFO queue. No `Dialog`/`Popup`
 * window — Haze cannot blur across windows, and the in-window host is what
 * makes the glass real; the card sits above everything in its window
 * (list, tab pill, in-window sheets, action sheets, toasts).
 *
 * Screens inside keep their own per-screen `LocalHazeState` sources
 * (ticket 07's convention); this host adds a window-level source around the
 * content, which the alert's [GlassSurface] samples. A screen's own source
 * nested inside the host's is a *different* state, so the no-nesting rule
 * holds; verify in the gallery that the nested source still blurs (if Haze
 * ever refuses nesting, pass `hazeState = null` for the alert — the
 * fallback fill — and record the change).
 *
 * Ticket 35 mounts this at the app root, outermost:
 * `AlertHost { ToastHost { ActionSheetHost { … } } }`, so alerts are modal
 * over toasts and action sheets. This ticket mounts it in the gallery and
 * the tests only.
 */
@Composable
fun AlertHost(state: AlertHostState = remember { AlertHostState() }, content: @Composable () -> Unit) {
    val hostHaze = rememberHazeState()
    CompositionLocalProvider(LocalAlerts provides state) {
        Box(Modifier.fillMaxSize()) {
            // 1. The wrapped content: the window-level haze source, hidden
            //    from TalkBack while an alert is up so it stays inside the alert.
            val hideContent = state.current != null
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hostHaze)
                    .let { m -> if (hideContent) m.semantics { hideFromAccessibility() } else m },
            ) {
                content()
            }
            // 2. The alert itself, key fresh per presentation: each alert
            //    gets fresh field state and animations.
            state.current?.let { p ->
                key(p.id) {
                    PresentedAlert(
                        presented = p,
                        hazeState = hostHaze,
                        dismissRequested = state.dismissRequested == p.id,
                        onRemoved = state::removed,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The presented alert: the scrim, the placement, the interaction rules
// ---------------------------------------------------------------------------

/**
 * One alert on screen. The scrim and the card fade together; the card also
 * springs from 1.1 to 1 (no scale under Reduce Motion). [resolve] is the
 * single exit path: it marks the alert resolved, clears focus before the
 * handler runs (a handler that focuses something else must win), calls the
 * handler synchronously, then fades out and asks the host for the next one.
 */
@Composable
private fun PresentedAlert(
    presented: AlertHostState.Presented,
    hazeState: HazeState,
    dismissRequested: Boolean,
    onRemoved: (Long) -> Unit,
) {
    val alert = presented.alert
    val hasField = alert.field != null
    val reduce = LocalReduceMotion.current

    // The field's value, the caret at the end of the pre-fill (FOUNDATION-D2,
    // UIKit's behaviour): typing appends, a tap moves the caret.
    var fieldValue by remember {
        val initial = alert.field?.text.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    var resolved by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(if (reduce) 1f else 1.1f) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, Motion.alertFadeIn()) }
        if (!reduce) launch { scale.animateTo(1f, Motion.AlertScaleIn.spec()) }
        if (hasField) {
            // One frame after composition: the node is attached, so focus
            // and the keyboard land (pr621's recipe, minus the dialog hack).
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    /**
     * The single exit path: `index == null` dismisses without any handler.
     * A [morphsInPlace][AlertButton.morphsInPlace] button never exits: it
     * runs its handler (which replaces the content through
     * [AlertHostState.updateCurrent]) and the alert stays up.
     */
    fun resolve(index: Int?) {
        if (resolved) return
        val button = index?.let { alert.buttons.getOrNull(it) }
        if (button != null && button.morphsInPlace) {
            button.onClick(fieldValue.text)
            return
        }
        resolved = true
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        index?.let { alert.buttons.getOrNull(it)?.onClick?.invoke(fieldValue.text) }
        scope.launch {
            alpha.animateTo(0f, Motion.alertFadeOut())
            onRemoved(presented.id)
        }
    }

    // dismissAll(): fade out without running any handler.
    LaunchedEffect(dismissRequested) {
        if (dismissRequested) resolve(null)
    }

    // Composed after the screens' handlers, so it wins while the alert is up.
    BackHandler(enabled = !resolved) {
        // The back rule: the cancel action, or a plain dismiss when there is none.
        resolve(cancelActionIndex(alert.buttons))
    }

    Box(Modifier.fillMaxSize()) {
        // The scrim: full window, fixed black @0.20, fading with the card.
        Scrim(alpha) {
            // Outside-tap rule: run the cancel action; no cancel action → the
            // tap is ignored (the alert stays), like iOS.
            cancelActionIndex(alert.buttons)?.let { resolve(it) }
        }
        // The card: centred in the window minus the system bars, the cutout
        // and the IME (so it re-centres as the keyboard animates), minus 16.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RobocoAlertCard(
                alert = alert,
                fieldValue = fieldValue,
                onFieldValueChange = { fieldValue = it },
                onButton = { i ->
                    if (isEnabled(alert.buttons[i], hasField, fieldValue.text)) resolve(i)
                },
                onImeDone = {
                    val primary = primaryIndex(alert.buttons)
                    if (primary != null && isEnabled(alert.buttons[primary], hasField, fieldValue.text)) {
                        resolve(primary)
                    }
                },
                hazeState = hazeState,
                fieldFocusRequester = focusRequester,
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    // Taps on the card's own background must not reach the
                    // scrim below it (children process the Main pass first).
                    .swallowCardTaps()
                    .semantics {
                        dismiss {
                            // The back rule, for accessibility services.
                            resolve(cancelActionIndex(alert.buttons))
                            true
                        }
                    },
            )
            AlertProgressSemantics(alpha, scale)
        }
    }
}

/**
 * The scrim: a full-window `#33000000` that fades with the card and
 * swallows every pointer gesture — nothing under the alert can start a
 * gesture, and taps keep being consumed through the 200 ms dismiss (the
 * composable only leaves when the fade ends).
 *
 * A tap (down + up, anywhere outside the card) fires [onScrimTap]; the
 * gesture runs on the Main pass, where this node precedes the wrapped
 * content, so the app never sees an unconsumed change.
 */
@Composable
private fun Scrim(alpha: Animatable<Float, AnimationVector1D>, onScrimTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(rgba(0x000000, 0.2))
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // The card's tap swallower has already consumed downs that
                    // land on the card (it processes the Main pass first); a
                    // consumed down means "inside the card" — never fire.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val onScrim = !down.isConsumed
                    if (!down.isConsumed) down.consume()
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (!change.isConsumed) change.consume()
                        }
                        released = event.changes.all { !it.pressed }
                    }
                    if (onScrim) onScrimTap()
                }
            },
    )
}

/**
 * Makes the card a hit target and consumes the **down** that lands on it
 * (Main pass, after the children): a tap on the card's padding never
 * reaches the scrim's tap detector, which treats a consumed down as
 * "inside the card".
 *
 * Downs only, never moves or ups. A button's `clickable` re-checks every
 * intermediate event on the Final pass and cancels its tap when an
 * ancestor consumed it (`waitForUpOrCancellation`), and a real finger's tap
 * carries a few ACTION_MOVEs: consuming them here dropped button taps on
 * phones (the ActionSheet's identical swallower did, round 44).
 */
private fun Modifier.swallowCardTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
                if (change.pressed && !change.previousPressed && !change.isConsumed) change.consume()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Test-only progress keys (frame-0 opacity/scale assertions, AlertHostTest)
// ---------------------------------------------------------------------------

/** Test-only: the present/dismiss fade of the current alert (0…1). */
val AlertAlphaKey = SemanticsPropertyKey<Float>("AlertAlpha")

/** Test-only: the present scale of the current alert card (1.1 → 1). */
val AlertScaleKey = SemanticsPropertyKey<Float>("AlertScale")

/**
 * Test-only: the current alert's progress fraction (0…1), when it carries
 * an [AlertProgress] (android-next#04's download state).
 */
val AlertProgressKey = SemanticsPropertyKey<Float>("AlertProgress")

/**
 * Exposes [AlertAlphaKey]/[AlertScaleKey] on a zero-size sibling of the
 * card, recomposing alone while the animations run — the same isolation
 * trick as `GlassSurface`'s progress keys.
 */
@Composable
private fun AlertProgressSemantics(
    alpha: Animatable<Float, AnimationVector1D>,
    scale: Animatable<Float, AnimationVector1D>,
) {
    val a = alpha.value
    val s = scale.value
    Box(
        Modifier.semantics {
            this[AlertAlphaKey] = a
            this[AlertScaleKey] = s
        },
    )
}
