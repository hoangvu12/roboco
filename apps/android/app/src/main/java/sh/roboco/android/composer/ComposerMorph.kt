package sh.roboco.android.composer

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.transcript.InteractiveImeDismiss

/**
 * iOS `ComposerBar.wantsCard` (`ios:Composer/ComposerBar.swift:308-310`):
 * the card is for composing — focused, holding photos, or pinned open —
 * while an unfocused draft (text alone) rests as the capsule. Text is
 * deliberately **not** an input: `pr621`/`pr609` both made a text draft
 * hold the card and that is a divergence this port does not copy.
 */
fun wantsCard(alwaysCard: Boolean, focused: Boolean, hasImages: Boolean): Boolean =
    alwaysCard || focused || hasImages

/**
 * One step of the Android-only "a closed IME blurs the field" rule (the
 * Roboco adaptation of `textViewDidEndEditing`, which on iOS is the same
 * event as "keyboard went away"). Returns `(newImeSeen, shouldBlur)`:
 *
 *  - not focused → the watch resets, nothing blurs;
 *  - IME visible (and focused) → mark it seen, no blur;
 *  - IME gone, was seen, and the window is focused → blur (a focusable
 *    popup or app backgrounding must not collapse the composer, hence the
 *    window gate);
 *  - anything else (IME never shown — a hardware keyboard) → keep [imeSeen].
 */
fun imeBlurStep(
    focused: Boolean,
    imeVisible: Boolean,
    windowFocused: Boolean,
    imeSeen: Boolean,
): Pair<Boolean, Boolean> =
    when {
        !focused -> false to false
        imeVisible -> true to false
        imeSeen && windowFocused -> false to true
        else -> imeSeen to false
    }

/**
 * The capsule ⇄ card state machine (android-ui-parity #56): the port of
 * `ios:Composer/ComposerBar.swift`'s mode logic, motion and focus path.
 * Ticket 55's [ComposerSurface] consumes the outputs.
 *
 * - **`wantsCard`** = [wantsCard]`(alwaysCard, isFocused, hasImages)`;
 * - **[isCard]** is iOS `isCard`: it flips **immediately**, in the same
 *   snapshot as the input change (`updateMode`'s `guard` writes it before
 *   the animation runs);
 * - **[progress]** (0 = capsule, 1 = card) is the animated layout state,
 *   driven by `Motion.ComposerMorph` (the 0.42 s / 0.86 spring,
 *   `ComposerBar.swift:333`). It is interruptible — a new target
 *   continues from the current value **and velocity**
 *   (`.beginFromCurrentState`) — and it **snaps** under Reduce Motion,
 *   before the glass's first placement (iOS `window == nil`), or when
 *   `alwaysCard` itself changed (iOS's `chipsAlwaysVisible` setter passes
 *   `animated: false`). It may overshoot to ≈ ±0.005; consumers clamp
 *   where a value must stay in range (alpha).
 *
 * Read [progress] in measure/placement/draw lambdas only (55 does), never
 * in the whole composer's composition, so a running morph does not
 * recompose per frame. [isCard] and [isFocused] are snapshot state and may
 * be read in composition.
 */
@Stable
class ComposerMorphState internal constructor(
    private val alwaysCardState: State<Boolean>,
    private val hasImagesState: State<() -> Boolean>,
    /** Overrides the first `progress`; previews/screenshots only. */
    initialProgress: Float? = null,
    /** Seeds [isFocused] for the preview helper; the real path is the field. */
    initialFocused: Boolean = false,
) {
    /** iOS `textView.isFirstResponder`, from the field's Compose focus. */
    var isFocused: Boolean by mutableStateOf(initialFocused)
        private set

    /** iOS `chipsAlwaysVisible` (the new-session canvas pins it true). */
    val alwaysCard: Boolean get() = alwaysCardState.value

    /** iOS `isCard`: flips immediately, before the spring starts. */
    val isCard: Boolean get() = wantsCard(alwaysCard, isFocused, hasImagesState.value())

    /**
     * The animated 0…1 layout state. `0.001` visibility threshold: 50 dp ×
     * 0.01 would jump 0.5 dp at the end.
     */
    internal val anim = Animatable(initialProgress ?: if (isCard) 1f else 0f, visibilityThreshold = 0.001f)

    /** 0 = capsule, 1 = card (± the spring's ≈ 0.005 overshoot). Read in lambdas. */
    val progress: Float get() = anim.value

    /**
     * The text field's vertical scroll (iOS `textView.setContentOffset`).
     * Reset to 0 on blur — only effective if the field passes it as
     * `scrollState` (the `TextFieldState` overload; 57's legacy
     * `TextFieldValue` field has no such parameter, so the reset is a
     * harmless no-op there). Kept so the reset works if 54/57 move to
     * `TextFieldState`.
     */
    val textScroll = ScrollState(0)

    /** The programmatic focus handle ([focus]). */
    val focusRequester = FocusRequester()

    // Plain fields re-assigned every composition (rememberComposerMorphState's
    // SideEffect); they are never snapshot state.
    internal var focusManager: FocusManager? = null
    internal var scope: CoroutineScope? = null
    internal var onFocusChange: (Boolean) -> Unit = {}
    internal var hideSuggestions: () -> Unit = {}

    /** iOS `window != nil` — the glass has been placed at least once. */
    internal var placed = false
    internal var glassCoords: LayoutCoordinates? = null

    /** The live `composerControl` nodes; main thread only. */
    internal val controls = mutableListOf<ComposerControlNode>()

    /** iOS `becomeFirstResponder` — a no-op if the field already has focus. */
    fun focus() {
        if (!isFocused) runCatching { focusRequester.requestFocus() }
    }

    /** iOS `resignFirstResponder` — a no-op if the field does not. */
    fun blur() {
        if (isFocused) focusManager?.clearFocus()
    }

    /**
     * The focus callbacks (`textViewDidBegin/EndEditing`,
     * `ComposerBar.swift:402-420`). **Begin:** `isFocused = true` (the
     * morph starts), then `onFocusChange(true)`. **End, in order:**
     * `hideSuggestions()`, `isFocused = false` (the morph starts),
     * `textScroll.scrollTo(0)` (instant), `onFocusChange(false)`. The
     * draft text and the images are never touched. Early-returns when the
     * value has not changed — `onFocusChanged` also fires once at attach
     * with `false`.
     */
    internal fun onFieldFocus(focused: Boolean) {
        if (focused == isFocused) return
        if (focused) {
            isFocused = true
            onFocusChange(true)
        } else {
            hideSuggestions()
            isFocused = false
            scope?.launch { textScroll.scrollTo(0) }
            onFocusChange(false)
        }
    }

    /**
     * Is the glass-local point [p] over any `composerControl` (the port of
     * the `UIControl` superview walk, `ComposerBar.swift:276-289`)?
     * Bounds are computed **at tap time**, not cached: controls move during
     * the morph. `clipBounds = true` so a chip scrolled out of its strip
     * does not block taps; controls outside the glass still work because
     * both coordinates share the window root.
     */
    internal fun isOverControl(p: Offset): Boolean {
        val glass = glassCoords ?: return false
        return controls.any { node ->
            node.coords?.takeIf { it.isAttached }?.let {
                glass.localBoundingBoxOf(it, clipBounds = true).contains(p)
            } == true
        }
    }
}

/**
 * [ComposerMorphState] in composition: the morph driver, the IME watch and
 * the focus/blur plumbing.
 *
 * @param alwaysCard iOS `chipsAlwaysVisible`; toggling it re-evaluates the
 *   mode **without** animation (a snap), and toggling it while `isCard`
 *   stays the same leaves a running morph alone (the iOS `guard`)
 * @param hasImages must read snapshot state — `{ composer.images.isNotEmpty() }`
 *   (ticket 54's staged images); a change animates the mode
 * @param onFocusChange the public hook iOS keeps with no subscriber; 78
 *   (blur after send) and 83 subscribe
 * @param hideSuggestions 93 passes `{ composer.hideSuggestions() }`; 78
 *   leaves it default because 86's blur observer hides the card
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberComposerMorphState(
    alwaysCard: Boolean,
    hasImages: () -> Boolean,
    onFocusChange: (Boolean) -> Unit = {},
    hideSuggestions: () -> Unit = {},
): ComposerMorphState {
    val always = rememberUpdatedState(alwaysCard)
    val images = rememberUpdatedState(hasImages)
    val state = remember { ComposerMorphState(always, images) }

    // Composables first (they cannot be called inside SideEffect).
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    SideEffect {
        state.focusManager = focusManager
        state.scope = scope
        state.onFocusChange = onFocusChange
        state.hideSuggestions = hideSuggestions
    }

    // The morph driver (updateMode, ComposerBar.swift:304-334). A new
    // target cancels the running animateTo and keeps the Animatable's
    // current value and velocity — never a snapTo of the start first
    // (.beginFromCurrentState). collect, not collectLatest: an
    // alwaysCard-only change must not cancel a running spring.
    val reduce = rememberUpdatedState(LocalReduceMotion.current)
    LaunchedEffect(state) {
        var lastCard = state.isCard // == the Animatable's initial target
        var lastAlways = state.alwaysCard
        snapshotFlow { state.isCard to state.alwaysCard }.collect { (card, always) ->
            val alwaysChanged = always != lastAlways
            lastAlways = always
            if (card == lastCard) return@collect // guard: a running morph is left alone
            lastCard = card
            val target = if (card) 1f else 0f
            val snap = reduce.value || !state.placed || alwaysChanged
            launch {
                if (snap) state.anim.snapTo(target)
                else state.anim.animateTo(target, Motion.ComposerMorph.spec(visibilityThreshold = 0.001f))
            }
        }
    }

    // The Android-only rule: a closed IME blurs the field (on iOS the
    // keyboard is visible exactly while the text view is first responder,
    // so the events are the same). A hardware keyboard never shows the
    // IME, so the rule never fires there.
    val ime = WindowInsets.isImeVisible
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // 133: while an interactive transcript drag holds (or is acquiring)
    // the IME controller, a mid-drag visibility report must not blur —
    // the drag owns the decision. When it ends (the release finishing
    // the settle flips the flag), this effect re-runs with the settled
    // IME state and blurs only if the IME stayed hidden.
    val imeDrag = InteractiveImeDismiss.active
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime, state.isFocused, windowFocused, imeDrag) {
        if (imeDrag) return@LaunchedEffect
        val (seen, blur) = imeBlurStep(state.isFocused, ime, windowFocused, imeSeen)
        imeSeen = seen
        if (blur) state.blur()
    }
    return state
}

/**
 * On the glass container (ticket 55 applies it to `ComposerSurface`'s
 * modifier): placement tracking (the `window != nil` snap condition) and
 * tap-to-focus (`ComposerBar.swift:132-136, 276-289`).
 *
 * A completed tap (down → up within touch slop) on a non-control area of
 * the glass focuses the text field; the detector **never consumes**
 * (iOS `cancelsTouchesInView = false`), skips downs inside any
 * `composerControl` region, ignores movement beyond touch slop (a
 * chip-strip scroll) and tracks the first pointer only. Taps whose up was
 * consumed by a child do nothing. The glass keeps **no semantics**: no
 * `clickable`, no role — TalkBack users focus the text field directly.
 */
fun Modifier.composerMorphHost(state: ComposerMorphState): Modifier =
    onPlaced { coords ->
        state.glassCoords = coords
        state.placed = true
    }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (state.isOverControl(down.position)) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (change.changedToUp()) {
                    if (!change.isConsumed) state.focus()
                    return@awaitEachGesture
                }
                if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
            }
        }
    }

/**
 * On the composer's single text field (57 applies it to its outer slot
 * box, which wraps the one `BasicTextField` — one field for both states,
 * never swapped or `key()`ed, or the focused node is disposed and the IME
 * drops, the pr621 lesson): the [FocusRequester] for [ComposerMorphState.focus]
 * and the focus callbacks. 78 passes `morph.focusRequester` to 57's own
 * `focusRequester` parameter **and** this modifier; the same requester
 * attached twice to one field is harmless (`requestFocus()` stops at the
 * first node that takes focus).
 */
fun Modifier.composerInput(state: ComposerMorphState): Modifier =
    focusRequester(state.focusRequester)
        .onFocusChanged { state.onFieldFocus(it.isFocused) }

/**
 * On every composer control — attach (55), chips (59), action (58),
 * thumbnail remove (88) — **enabled or disabled**: the port of the
 * `UIControl` superview walk. A disabled `clickable` lets taps through in
 * Compose while a disabled `UIButton` blocks them, so taps are excluded by
 * marker bounds, not by consumption; consumption stays a second guard.
 * Place it outermost of the control's own chrome (padding/clickable), and
 * after any offset positioning, so its bounds match the on-screen control.
 */
fun Modifier.composerControl(state: ComposerMorphState): Modifier =
    this.then(ComposerControlElement(state))

/** One live control: its latest [LayoutCoordinates] (main thread only). */
internal class ComposerControlNode(
    var morph: ComposerMorphState?,
) : Modifier.Node(), GlobalPositionAwareModifierNode {

    var coords: LayoutCoordinates? = null

    override fun onAttach() {
        morph?.controls?.add(this)
    }

    override fun onDetach() {
        morph?.controls?.remove(this)
        // Keep [morph] (a detached node can be re-attached); only the
        // coordinates are stale. isOverControl checks isAttached anyway.
        coords = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        coords = coordinates
    }
}

/** Element equality is by [state] identity — a stable element never updates. */
internal class ComposerControlElement(
    private val state: ComposerMorphState,
) : ModifierNodeElement<ComposerControlNode>() {

    override fun create(): ComposerControlNode = ComposerControlNode(state)

    override fun update(node: ComposerControlNode) {
        if (node.morph !== state) {
            node.morph?.controls?.remove(node)
            node.morph = state
            if (node.isAttached) state.controls.add(node)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ComposerControlElement && other.state === state

    override fun hashCode(): Int = state.hashCode()
}

/**
 * A state frozen at a morph position for `@Preview`s and screenshot
 * fixtures: [card] drives `isCard` through a constant `alwaysCard`, and
 * `progress` starts (and stays, nothing drives it) at [progress].
 */
@VisibleForTesting
internal fun previewMorphState(progress: Float, card: Boolean, focused: Boolean = false): ComposerMorphState =
    ComposerMorphState(
        alwaysCardState = mutableStateOf(card),
        hasImagesState = mutableStateOf({ false }),
        initialProgress = progress,
        initialFocused = focused,
    )
