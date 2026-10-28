package sh.roboco.android.design

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import kotlin.math.min

/**
 * The toast (ticket 14): a small glass capsule that floats just above the
 * bottom chrome for 4 seconds, then slides down and fades out, with an
 * optional text action ("Archived · Undo"). One at a time — a new toast
 * replaces the current one immediately. It sits above everything in the app
 * window: list rows, tab pill, accessory capsule, session screen.
 *
 * The port of `ios:Design/Toast.swift` (one file, 113 lines):
 *  - **Geometry:** centred on the window, width hugging content capped at
 *    window − 48, bottom edge 12 dp above the anchor top (the bottom chrome
 *    actually on screen, or the safe-area bottom when none is); 12/20 dp
 *    padding, 14 dp label↔action spacing, capsule shape, no press effect.
 *  - **Motion:** present starts 16 dp low with the glass invisible, rises
 *    with the `Motion.ToastRise` spring (0.4 s / ζ 0.85) while the glass
 *    materializes over 350 ms (`GlassSurface(materializeOnEnter = true)`);
 *    auto-dismiss after 4000 ms, then a 250 ms fall to +12 dp and a 250 ms
 *    dematerialize in parallel; removal when the fall finishes. The capsule
 *    consumes every tap that lands on it (the row underneath does not get
 *    it); the action fires at most once per toast.
 *  - **Copy is the caller's:** `show(text, action?, onAction?)` renders
 *    both strings verbatim; the toast never builds or localizes strings.
 *
 * Roboco adaptations (see the ticket): the anchor follows the *live* chrome
 * top with the rise spring (iOS freezes the constraint constant at show
 * time); the keyboard counts as a bottom edge; the hold uses Android's
 * recommended a11y timeout (4000 ms unless "Time to take action" is set);
 * the action is once-only per toast.
 */

// ---------------------------------------------------------------------------
// The toaster API
// ---------------------------------------------------------------------------

/** Posts the label text as a live-region announcement, once per toast (`UIAccessibility.post(.announcement)`, `Toast.swift:99`). */
fun interface ToastAnnouncer {
    fun announce(text: String)
}

/** Shows toasts: `show(text, action?, onAction?)`. Callers own the copy. */
interface Toaster {
    /**
     * Shows a toast, replacing the current one instantly (no exit animation
     * for the replaced one, its timer cancelled). Callable from any thread.
     */
    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null)

    companion object {
        /** Default when no [ToastHost] is mounted (previews): logs a warning, shows nothing. */
        val Noop: Toaster = object : Toaster {
            override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
                Log.w("Toaster", "No ToastHost mounted; dropped toast: $text")
            }
        }
    }
}

/** One toast's payload. The id is the replacement token (pr621's `toastToken` idea): a stale timer cannot clear a newer toast. */
@Immutable
data class ToastModel(
    val id: Long,
    val text: String,
    val action: String?,
    val onAction: (() -> Unit)?,
)

/**
 * The one-at-a-time state behind [ToastHost]: [current] is the whole
 * timeline — `show` replaces it (the old capsule leaves composition the
 * same frame, which is the instant removal) and [remove] clears it when the
 * fall finishes, but only if the id still matches.
 */
@Stable
class ToasterState : Toaster {
    /** The showing toast, or `null`. */
    var current: ToastModel? by mutableStateOf(null)
        private set

    private var nextId = 0L // main thread only
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
        // Off the main thread: post to the main looper (callers in tickets 42
        // and 80 may be on Dispatchers.Default). On main: apply now, so
        // tests and same-frame double shows stay synchronous.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyShow(text, action, onAction)
        } else {
            mainHandler.post { applyShow(text, action, onAction) }
        }
    }

    private fun applyShow(text: String, action: String?, onAction: (() -> Unit)?) {
        current = ToastModel(++nextId, text, action, onAction)
    }

    /** Clears the toast with [id] — a no-op if it was already replaced by a newer one. */
    internal fun remove(id: Long) {
        if (current?.id == id) current = null
    }
}

/** The ambient [Toaster]. [Toaster.Noop] outside a [ToastHost] (previews). */
val LocalToaster = staticCompositionLocalOf<Toaster> { Toaster.Noop }

/**
 * The default announcer: `View.announceForAccessibility`, the direct
 * equivalent of `UIAccessibility.post(.announcement, label)` — TalkBack
 * speaks the label text without moving focus to the toast.
 */
@Composable
fun rememberViewAnnouncer(): ToastAnnouncer {
    val view = LocalView.current
    return remember(view) { ToastAnnouncer { text -> view.announceForToast(text) } }
}

@Suppress("DEPRECATION") // deprecated on newer APIs, but the only announcement API that works on minSdk 31; the toast announces on present only (Toast.swift:99)
private fun View.announceForToast(text: String) {
    announceForAccessibility(text)
}

/**
 * Test-only: the capsule's current translationY in dp (16 → 0 on present,
 * → 12 on dismiss), exposed for UI tests. Set on an invisible child of the
 * capsule; production code never reads it.
 */
val ToastSlideKey = SemanticsPropertyKey<Float>("ToastSlide")

// ---------------------------------------------------------------------------
// The host
// ---------------------------------------------------------------------------

/**
 * Draws toasts over the app content, above everything in the window, and
 * provides [LocalToaster] (callers call `show`) and [LocalBottomChromeTop]
 * (the shell writes the chrome top, ticket 30).
 *
 * Mount once, wrapping the whole app root (ticket 35:
 * `AlertHost { ToastHost { … } }` — the toast draws above sheets, action
 * sheets and menus, alerts above the toast). The app content is the one
 * `hazeSource` of this window's [HazeState][dev.chrisbanes.haze.HazeState];
 * the capsule samples it as a **sibling** drawn after the content, never
 * from inside the source it blurs. The content's own `LocalHazeState` is
 * untouched (screens provide their own states, ticket 07's convention).
 *
 * The host `Box` itself consumes no touches — only the capsule does; taps
 * outside it pass through to the app.
 */
@Composable
fun ToastHost(
    state: ToasterState? = null,
    announcer: ToastAnnouncer? = null,
    content: @Composable () -> Unit,
) {
    // Both defaults are remembered unconditionally (an `x ?: remember {}` elvis
    // would call the composable only sometimes and skew the slot table the
    // first time the argument flips).
    val toastState = remember(state) { state ?: ToasterState() }
    val viewAnnouncer = rememberViewAnnouncer()
    val toastAnnouncer = announcer ?: viewAnnouncer
    val rootHaze = rememberHazeState()
    val chromeTopState = remember { BottomChromeTopState() }
    CompositionLocalProvider(
        LocalToaster provides toastState,
        LocalBottomChromeTop provides chromeTopState,
    ) {
        Box(Modifier.fillMaxSize()) {
            // 1. The app content: the only haze source of this window.
            Box(Modifier.fillMaxSize().hazeSource(rootHaze)) { content() }
            // 2. The toast: above everything, blurring the content below it.
            //    Keyed by the model id — a replaced toast leaves composition
            //    the same frame (instant removal, timers cancelled) and the
            //    new one composes in its place.
            CompositionLocalProvider(LocalHazeState provides rootHaze) {
                toastState.current?.let { model ->
                    key(model.id) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val safeBottom = rememberSafeBottom(maxHeight)
                            val chromeTop = chromeTopState.top
                            val anchorTop = toastAnchor(
                                safeBottom.value,
                                if (chromeTop.isSpecified) chromeTop.value else Float.NaN,
                            )
                            ToastCapsule(
                                model = model,
                                announcer = toastAnnouncer,
                                holdMs = rememberHoldMs(model),
                                anchorTop = anchorTop,
                                onRemoved = toastState::remove,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hold, adapted for accessibility (ticket 14):
 * `accessibilityManager.getRecommendedTimeoutMillis(4000, FLAG_CONTENT_TEXT
 * [or FLAG_CONTENT_CONTROLS])` — exactly 4000 ms unless the user set
 * Android's "Time to take action", which only ever *lengthens* the hold
 * (TalkBack users need time to reach "Undo"). Read once per toast.
 *
 * The floor at [Motion.ToastHoldMs] mirrors the platform's own guarantee
 * (the recommended value is always ≥ the original) and keeps Robolectric's
 * unshadowed real-code path from zeroing the hold.
 */
@Composable
private fun rememberHoldMs(model: ToastModel): Long {
    val context = LocalContext.current
    return remember(model.id) {
        val flags = AccessibilityManager.FLAG_CONTENT_TEXT or
            (if (model.action != null) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
        val recommended = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getRecommendedTimeoutMillis(Motion.ToastHoldMs.toInt(), flags)
        }.getOrNull()
        maxOf(recommended ?: 0, Motion.ToastHoldMs.toInt()).toLong()
    }
}

// ---------------------------------------------------------------------------
// The capsule
// ---------------------------------------------------------------------------

/** A plain holder so the hold job can be cancelled from `dismiss()`. */
private class HoldJobHolder {
    var job: Job? = null
}

/**
 * The animated capsule, keyed by `model.id` so replacement is composition
 * exit. Owns the timeline (`Toast.swift:92-111`):
 *
 *  - present: `slide` starts at 16 dp and springs to 0 with
 *    [Motion.ToastRise]; the glass materializes 0→1 over 350 ms
 *    (`materializeOnEnter = true`, inside `GlassSurface`); the label is
 *    announced; the hold job starts (4000 ms).
 *  - dismiss (hold fired, action tapped): once only — `visible` flips to
 *    `false` (250 ms dematerialize + touch swallowing, inside
 *    `GlassSurface`) while the slide tweens to +12 dp over 250 ms
 *    ([Motion.toastOut]) in a **new** coroutine (the hold job's own
 *    cancellation must not take the removal down with it); when the tween
 *    finishes the toast is removed by id.
 *  - anchor follow (Roboco adaptation): the rest anchor animates to the
 *    live chrome top with the rise spring. The first composition starts at
 *    the right value, so the initial placement is not animated.
 */
@Composable
private fun ToastCapsule(
    model: ToastModel,
    announcer: ToastAnnouncer,
    holdMs: Long,
    anchorTop: Float,
    onRemoved: (Long) -> Unit,
) {
    val slide = remember { Animatable(Motion.ToastRiseFromDp) }
    val anchor = remember { Animatable(anchorTop) }
    var dismissing by remember { mutableStateOf(false) }
    var actionFired by remember { mutableStateOf(false) }
    val holdHolder = remember { HoldJobHolder() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(anchorTop) {
        // The first composition starts at the right value: no initial jump.
        if (anchor.value == anchorTop) return@LaunchedEffect
        anchor.animateTo(anchorTop, Motion.ToastRise.spec())
    }

    fun dismiss() {
        if (dismissing) return // an animated dismiss already running is not restarted
        dismissing = true
        holdHolder.job?.cancel()
        // A new coroutine: the hold job may be the caller of dismiss(), and
        // cancelling it above must not cancel the fall that removes the toast.
        scope.launch {
            slide.animateTo(Motion.ToastOutToDp, Motion.toastOut())
            onRemoved(model.id)
        }
    }

    LaunchedEffect(Unit) {
        launch { slide.animateTo(0f, Motion.ToastRise.spec()) }
        announcer.announce(model.text)
        holdHolder.job = launch { delay(holdMs); dismiss() }
    }

    // The rest position is a layout value (custom Layout below); the slide
    // is a draw-time offset, so the 16/12 dp slides never relayout the tree.
    // Both are read in their cheapest scope: placement block / layer block.
    Layout(
        modifier = Modifier.graphicsLayer { translationY = slide.value.dp.toPx() },
        content = {
            Box {
                ToastCapsuleBody(
                    model = model,
                    visible = !dismissing,
                    materializeOnEnter = true,
                    actionClick = {
                        // Roboco adaptation: once-only. Two taps can be
                        // delivered before the recomposition that flips
                        // `visible`, which would run the handler twice.
                        if (!actionFired) {
                            actionFired = true
                            model.onAction?.invoke() // handler first (Toast.swift:69)
                            dismiss() // then the animated dismiss (Toast.swift:70)
                        }
                    },
                )
                ToastSlideSemantics(slide)
            }
        },
    ) { measurables, constraints ->
        // The host box fills the window, so the loose incoming maxWidth is
        // the window width: the capsule never spans window − 48 dp
        // (Toast.swift:23), and it is centred on the window (:21).
        val windowWidthPx = constraints.maxWidth
        val maxToastWidthPx = (windowWidthPx - 48.dp.roundToPx()).coerceAtLeast(0)
        // The content is the single wrapping Box (capsule body + slide
        // semantics child).
        val placeable = measurables.first().measure(Constraints(maxWidth = maxToastWidthPx))
        layout(placeable.width, placeable.height) {
            // toastRestY with the animated anchor: reading anchor.value in
            // the placement block invalidates placement only, not the measure.
            val restTop = (anchor.value.dp - ToastGapDp.dp).roundToPx() - placeable.height
            placeable.placeRelative((windowWidthPx - placeable.width) / 2, restTop)
        }
    }
}

/**
 * The capsule's visible content: the glass surface and its row. Shared by
 * the animated capsule and [ToastCapsuleStatic] so the geometry can never
 * drift between them. All values from the ticket's spec tables
 * (`Toast.swift:47-88`): padding 12/20, spacing 14, centred cross-axis,
 * Geist Medium 15 / `Palette.text` label (up to two lines), capsule shape,
 * no press effect on the capsule itself. Wave-10 42 (N7, upstream #966):
 * the action is a small filled capsule in the text colour — Geist
 * SemiBold 14 in `Palette.background`, insets 6/12 — like the desktop's,
 * and it never shrinks (the label wraps first).
 */
@Composable
private fun ToastCapsuleBody(
    model: ToastModel,
    visible: Boolean,
    materializeOnEnter: Boolean,
    actionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val labelStyle = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.text)
    val actionStyle = uiStyle(Face.SansSemibold, typeScale.fixed(14f), palette.background)
    GlassSurface(
        modifier = modifier
            .testTag("toast")
            // The capsule consumes every tap that lands on it, like the iOS
            // effect view — the row underneath does not get the touch. The
            // child sees the Main pass first, so the action still fires.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            },
        shape = CapsuleShape,
        interactive = false, // Glass.surface(): no press effect on the capsule
        tint = null,
        visible = visible,
        materializeOnEnter = materializeOnEnter,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                model.text,
                Modifier.weight(1f, fill = false), // the label wraps (two lines), never the action
                style = labelStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            model.action?.let { title ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                BasicText(
                    title,
                    Modifier
                        .testTag("toast-action")
                        // UIKit's .filled highlighted state, approximated:
                        // dim to 0.5, no ripple.
                        .graphicsLayer { alpha = if (pressed) 0.5f else 1f }
                        .clip(CapsuleShape)
                        .background(palette.text)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = title,
                            role = Role.Button,
                            onClick = actionClick,
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = actionStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The test-only [ToastSlideKey], in an isolated child so per-frame reads never touch the capsule content. */
@Composable
private fun ToastSlideSemantics(slide: Animatable<Float, AnimationVector1D>) {
    val value = slide.value
    Box(Modifier.semantics { this[ToastSlideKey] = value })
}

/**
 * The settled capsule for previews and fixtures: no entry animation, no
 * timer, no dismiss — `GlassSurface(visible = true, materializeOnEnter =
 * false)`, slide 0. The caller positions it.
 */
@Composable
internal fun ToastCapsuleStatic(
    text: String,
    action: String?,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    ToastCapsuleBody(
        model = ToastModel(0L, text, action, onAction),
        visible = true,
        materializeOnEnter = false,
        actionClick = { onAction?.invoke() },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// The shared fixture (previews, goldens, gallery)
// ---------------------------------------------------------------------------

/** 120 characters: the long-label fixture for the golden and the gallery's "Long text" button. */
internal val ToastLongText: String =
    "The quick brown fox jumps over the lazy dog. ".repeat(3).take(120)

/**
 * The toast fixtures' backdrop: full-size stripes (the haze source the
 * capsule blurs) and a fake 80 dp "bottom chrome" bar — a plain `Box` with
 * `palette.chip` fill, no Material — that writes its top into
 * [LocalBottomChromeTop], exactly as the real shell will (ticket 30). When
 * the bar leaves composition it resets the value to `Dp.Unspecified`, so
 * the toast falls back to the safe-area bottom.
 */
@Composable
internal fun ToastChromeFixture(
    modifier: Modifier = Modifier,
    chromeVisible: Boolean = true,
) {
    val palette = RobocoTheme.palette
    val chromeTop = LocalBottomChromeTop.current
    val density = LocalDensity.current
    Box(modifier) {
        Box(Modifier.fillMaxSize().drawBehind { drawToastStripes(palette) })
        if (chromeVisible) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(palette.chip)
                    .onGloballyPositioned { coordinates ->
                        val topPx = coordinates.boundsInRoot().top
                        chromeTop.top = with(density) { topPx.toDp() }
                    },
            )
            DisposableEffect(Unit) {
                onDispose { chromeTop.top = Dp.Unspecified }
            }
        }
    }
}

/** 20 dp stripes of `palette.accent` / `palette.text` @0.5, alternating — the backdrop the glass blurs. */
private fun DrawScope.drawToastStripes(palette: Palette) {
    val stripeHeight = 20.dp.toPx()
    var y = 0f
    var index = 0
    while (y < size.height) {
        val h = min(stripeHeight, size.height - y)
        drawRect(
            toastStripeColor(palette, index),
            topLeft = Offset(0f, y),
            size = Size(size.width, h),
        )
        y += h
        index++
    }
}

private fun toastStripeColor(palette: Palette, i: Int): Color = when (i % 2) {
    0 -> palette.accent
    else -> if (palette.isDark) rgba(0xE8E8EA, 0.5) else rgba(0x27272C, 0.5)
}

// ---------------------------------------------------------------------------
// Previews (step 8): a settled capsule over the striped backdrop, with the
// fake chrome bar 80 dp tall — the toast's bottom edge is 92 dp above the
// window's bottom (80 chrome + 12 gap; previews have no insets).
// ---------------------------------------------------------------------------

@Preview(name = "Toast light")
@Composable
private fun ToastLightPreview() {
    ToastPreview(Appearance.Light)
}

@Preview(name = "Toast dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastDarkPreview() {
    ToastPreview(Appearance.Dark)
}

@Composable
private fun ToastPreview(appearance: Appearance) {
    RobocoTheme(appearance) {
        ProvideHazeState {
            Box(Modifier.size(393.dp, 852.dp).background(RobocoTheme.palette.background)) {
                Box(Modifier.fillMaxSize().hazeSource(LocalHazeState.current!!)) {
                    ToastChromeFixture(Modifier.fillMaxSize())
                }
                ToastCapsuleStatic(
                    "Archived",
                    "Undo",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 92.dp),
                    onAction = {},
                )
            }
        }
    }
}
