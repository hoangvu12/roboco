package sh.roboco.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.design.Motion

/**
 * The whole bottom chrome group's frame for one instant (android-ui-parity
 * #30): whether it is **placed** at all, how it rides a navigation
 * transition, and how the incoming screen covers it. Pure values —
 * [ChromeVisibilityController.frame] derives them from the selected
 * [NavStack], and `Modifier.chromeVisibility` consumes them in the
 * placement / draw / layer phases only.
 *
 * The group is **never removed from composition** (upstream #577):
 * `placed = false` skips its placement only, so measured size, the minimize
 * spring and the accessory's press state all survive. An unplaced node is
 * not drawn, not hit-tested and not in the a11y tree — the Android port of
 * iOS `hidesBottomBarWhenPushed` + `setBottomAccessory(nil)`.
 *
 * [translationX]/[clipLeft]/[clipRight] are in **window px**: the chrome
 * layout fills the shell, so its local coordinates are the window's
 * (verified by `positionInRoot() == Offset.Zero` in [MainTabShell]).
 */
@Stable
data class ChromeFrame(
    val placed: Boolean,
    val translationX: Float = 0f,
    val alpha: Float = 1f,
    /** Window px; `±∞` = no clip. */
    val clipLeft: Float = Float.NEGATIVE_INFINITY,
    val clipRight: Float = Float.POSITIVE_INFINITY,
    /** Black overlay alpha over the chrome's own pixels (24's scrim). */
    val dim: Float = 0f,
) {
    companion object {
        val Hidden = ChromeFrame(placed = false)
        val Shown = ChromeFrame(placed = true)
    }
}

/**
 * Pure: the [ChromeFrame] of 24's [ChromeLayer] for the transition's
 * transforms [x] (`navLayerTransforms` of the current progress).
 *
 * `RidesBelow` (push to / pop from a `hidesBottomChrome` route) reproduces
 * 24's z-order "below, chrome, scrim, above" from outside the host: the
 * group travels with the below layer ([x.belowTx]), is covered by the
 * above screen's leading edge (the clip, window px: LTR `0..aboveTx`,
 * RTL `aboveTx + W..W`) and dimmed by the scrim ([x.scrimAlpha], black
 * `SrcAtop` over the chrome's own pixels). Under reduced motion the group
 * cross-fades instead (`1 − aboveAlpha` = `1 − s`), with no clip or dim.
 */
fun chromeFrame(
    layer: ChromeLayer,
    x: NavLayerTransforms,
    widthPx: Float,
    rtl: Boolean,
    reduceMotion: Boolean,
): ChromeFrame = when (layer) {
    ChromeLayer.Hidden -> ChromeFrame.Hidden
    ChromeLayer.Fixed -> ChromeFrame.Shown
    ChromeLayer.RidesAbove -> ChromeFrame(
        placed = true,
        translationX = x.aboveTx,
        alpha = x.aboveAlpha,
    )
    ChromeLayer.RidesBelow ->
        if (reduceMotion) {
            ChromeFrame(placed = true, alpha = 1f - x.aboveAlpha)
        } else {
            ChromeFrame(
                placed = true,
                translationX = x.belowTx,
                clipLeft = if (rtl) x.aboveTx + widthPx else 0f,
                clipRight = if (rtl) widthPx else x.aboveTx,
                dim = x.scrimAlpha,
            )
        }
}

/**
 * Pure: the accessory **content**'s alpha on a reveal (iOS
 * `setAccessoryContentAlpha` from the pop's alongside block,
 * `ios:Session/SessionViewController.swift:212`): a pop **from** a
 * `hidesBottomChrome` route fades the content 0 → 1 with the transition
 * progress, tracking a predictive-back gesture. Everything else (a push, a
 * same-kind transition, reduced motion) leaves it 1 — the group alpha
 * already fades under reduced motion, so multiplying would square it.
 */
fun accessoryRevealAlpha(fromShows: Boolean, toShows: Boolean, progress: Float, reduceMotion: Boolean): Float =
    if (!fromShows && toShows && !reduceMotion) progress.coerceIn(0f, 1f) else 1f

/**
 * The chrome group's visibility state holder (android-ui-parity #30): the
 * Android port of `MainTabController`'s `syncAccessory` /
 * `setAccessory(visible:)` / `prepareAccessoryForReveal()`
 * (`ios:Shell/MainTabController.swift:65-123`) as one derived rule over
 * the selected tab's [NavStack] — **derived, never flipped**: idle
 * placement comes from `stack.top`, transition placement from 24's
 * `chromePlacement`, and the completion of any transition re-derives it
 * (a cancelled predictive back ends hidden because the top is still the
 * Session, not because a flag was toggled twice).
 *
 * Also owns the after-Search content fade (iOS `:72-80`): an
 * [Animatable] snapped to 0 the frame the tab leaves Search, animated back
 * to 1 over `Motion.accessoryContentReturn()` (250 ms, 120 ms delay,
 * ease-out), skipped under reduced motion. Only the accessory **content**
 * fades — the glass capsule stays opaque, so an empty capsule shows for
 * the first 120 ms, as on iOS.
 *
 * Every public getter is a snapshot read; call them from
 * layout/draw/layer lambdas or `snapshotFlow`, never in a composable body
 * that must not recompose per frame.
 */
@Stable
class ChromeVisibilityController internal constructor(
    private val tabs: MainTabState,
    private val minimize: TabBarMinimizeState?,
    private val scope: CoroutineScope,
) {
    /** iOS `accessoryContent.alpha`: 1 at rest, 0 during the after-Search delay. */
    internal val searchReturn = Animatable(1f)

    private var fadeJob: Job? = null

    /** The chrome layout's width in px (its `onSizeChanged`). */
    internal var widthPx by mutableFloatStateOf(0f)

    /** The layout direction, as seen by the shell (a `SideEffect`). */
    internal var rtl by mutableStateOf(false)

    /** Reduce Motion, as seen by the shell (a `SideEffect`). */
    internal var reduceMotion by mutableStateOf(false)

    /** [TabChromeLayout]'s top in root px (`onPlaced`). 0 on an edge-to-edge shell. */
    internal var layoutTopInRoot by mutableFloatStateOf(0f)

    /** Window-space chrome top in dp, from 28's `onRects` + [layoutTopInRoot]. NaN until first placement. */
    internal var chromeTopInRoot by mutableFloatStateOf(Float.NaN)

    /**
     * 29's IME lift in dp ([SearchTabState.searchImeLift]); 0 when Search
     * is not selected. Read only inside [publishedChromeTop] (a
     * `snapshotFlow`), never in composition.
     */
    internal var searchImeLift: () -> Float = { 0f }

    /** The selected tab's stack — the one visibility derives from. */
    val stack: NavStack
        get() = tabs.stack(tabs.selected)

    /**
     * The value for ticket 14's [sh.roboco.android.design.BottomChromeTopState.top],
     * in dp; `NaN` = no chrome on screen ("[Dp.Unspecified]"). While Search
     * is selected with the IME up, 29's row rides the keyboard through
     * `Modifier.offset`, which 28's `onRects` cannot see — so the lift past
     * the accessory (48 + 8 dp) is subtracted here: below 56 the (fading)
     * accessory is still the top, past it the raised row is.
     */
    fun publishedChromeTop(): Float {
        if (!frame().placed || chromeTopInRoot.isNaN()) return Float.NaN
        return chromeTopInRoot - maxOf(0f, searchImeLift() - (TabChromeMetrics.AccessoryHeight + TabChromeMetrics.Gap))
    }

    /** Snapshot read of the current frame. Call from layout/draw lambdas or `snapshotFlow`. */
    fun frame(): ChromeFrame {
        val s = stack
        val t = s.transition
            ?: return if (s.hidesChrome(s.top)) ChromeFrame.Hidden else ChromeFrame.Shown
        val push = t.kind == NavKind.Push
        val below = if (push) t.from else t.to
        val above = if (push) t.to else t.from
        val layer = chromePlacement(
            showBelow = !s.hidesChrome(below),
            showAbove = !s.hidesChrome(above),
        )
        val x = navLayerTransforms(t.kind, t.progress, widthPx, rtl, reduceMotion)
        return chromeFrame(layer, x, widthPx, rtl, reduceMotion)
    }

    /** 27's `contentAlpha` hook: `searchReturn × popReveal`. Draw-phase read. */
    fun accessoryContentAlpha(): Float {
        val t = stack.transition
        val reveal = if (t == null) 1f else accessoryRevealAlpha(
            fromShows = !stack.hidesChrome(t.from),
            toShows = !stack.hidesChrome(t.to),
            progress = t.progress,
            reduceMotion = reduceMotion,
        )
        return searchReturn.value * reveal
    }

    /**
     * The after-Search fade (iOS `tabBarController(didSelect:)`): fired on
     * every selection change that leaves Search for a tab whose top shows
     * chrome, with Reduce Motion off. The snap to 0 happens in the same
     * frame; the 120 ms delay is the tween's, so the alpha is already 0
     * while it runs.
     */
    internal fun onTabChanged(previous: TabId, now: TabId) {
        if (previous == TabId.Search && now != TabId.Search &&
            !tabs.stack(now).let { it.hidesChrome(it.top) } && !reduceMotion
        ) {
            fadeJob?.cancel()
            fadeJob = scope.launch {
                searchReturn.snapTo(0f)
                searchReturn.animateTo(1f, Motion.accessoryContentReturn())
            }
        }
    }

    /** iOS `prepareAccessoryForReveal()` — expanded, final layout from frame 1. */
    internal fun onTransitionStarted(t: NavTransition) {
        if (stack.hidesChrome(t.from) && !stack.hidesChrome(t.to)) {
            minimize?.expand(animate = false)
        }
    }

    /** iOS completion `setAccessoryContentAlpha(1)`: end any search fade, snap to 1. */
    internal fun onTransitionEnded() {
        fadeJob?.cancel()
        fadeJob = null
        scope.launch { searchReturn.snapTo(1f) }
    }

    /**
     * From 28's `onRects` (dp, shell-relative): writes
     * `layoutTopInRoot.toDp() + chromeTopDp` into [chromeTopInRoot] when it
     * moved ≥ 0.5 px — the minimize spring moves it every frame, and the
     * publication (`snapshotFlow`) re-emits from the state write alone.
     */
    internal fun updateChromeTop(chromeTopDp: Float, density: Density) {
        val topDp = with(density) { layoutTopInRoot.toDp() }.value + chromeTopDp
        if (chromeTopInRoot.isNaN() || abs(topDp - chromeTopInRoot) * density.density >= 0.5f) {
            chromeTopInRoot = topDp
        }
    }
}

/**
 * [ChromeVisibilityController] in composition. Observes the tab selection
 * (the search fade trigger — every route into a change goes through
 * `MainTabState.select`, taps, back, Close, router) and each transition of
 * the selected stack (`onEnd` fires the completion once, or at once if the
 * transition already ended).
 */
@Composable
fun rememberChromeVisibilityController(
    tabs: MainTabState,
    minimize: TabBarMinimizeState?,
): ChromeVisibilityController {
    val scope = rememberCoroutineScope()
    val controller = remember(tabs, minimize) { ChromeVisibilityController(tabs, minimize, scope) }

    // The selection observer: no suspension between the reads (the change
    // is dispatched the same frame; the snap-to-0 runs immediately after).
    LaunchedEffect(controller) {
        var prev = tabs.selected
        snapshotFlow { tabs.selected }.collect { now ->
            if (now != prev) {
                controller.onTabChanged(prev, now)
                prev = now
            }
        }
    }

    // Transition start/end of the selected stack. Reading `transition`
    // here recomposes only this small scope, at start and end.
    val transition = controller.stack.transition
    LaunchedEffect(transition) {
        val t = transition ?: return@LaunchedEffect
        controller.onTransitionStarted(t)
        t.onEnd { controller.onTransitionEnded() }
    }
    return controller
}

/**
 * Placement + ride + cover + dim for the whole chrome group (apply to 28's
 * [TabChromeLayout], which fills the shell so the local coordinates are
 * the window's). The chain, outermost first:
 *
 * 1. `onSizeChanged` — feeds the controller the window width;
 * 2. `layout` — the group is **always measured** (composition, measured
 *    size and inner state survive, #577) and placed only when
 *    [ChromeFrame.placed]: unplaced = not drawn, not hit-tested, not in
 *    the a11y tree;
 * 3. `drawWithContent` — the cover by the incoming screen, a window-px
 *    clip at above's leading edge;
 * 4. `graphicsLayer` — the ride (`translationX`), the reduced-motion group
 *    alpha, and an offscreen layer while dimmed;
 * 5. `drawWithContent` — 24's scrim, black `SrcAtop` over the chrome's own
 *    pixels only (inside the offscreen layer, so it never tints the
 *    content below);
 * 6. `testTag("bottom-chrome")`.
 *
 * Every state read happens in placement/draw/layer lambdas: no
 * recomposition per frame.
 */
fun Modifier.chromeVisibility(controller: ChromeVisibilityController): Modifier = this
    .onSizeChanged { controller.widthPx = it.width.toFloat() }
    .layout { measurable, constraints ->
        // ALWAYS measured: the group stays composed and sized (#577).
        val p = measurable.measure(constraints)
        layout(p.width, p.height) {
            // Not placed = not drawn, not hit-tested, not in semantics.
            if (controller.frame().placed) p.place(0, 0)
        }
    }
    .drawWithContent {
        // The cover by "above", in window px (this node's coordinates are
        // the window's: the layout fills the edge-to-edge shell).
        val f = controller.frame()
        if (f.clipLeft.isInfinite() && f.clipRight.isInfinite()) {
            drawContent()
        } else {
            clipRect(
                left = f.clipLeft.coerceAtLeast(0f),
                top = 0f,
                right = f.clipRight.coerceAtMost(size.width),
                bottom = size.height,
            ) {
                this@drawWithContent.drawContent()
            }
        }
    }
    .graphicsLayer {
        val f = controller.frame()
        translationX = f.translationX
        alpha = f.alpha
        // The SrcAtop dim below must not punch through to the window, so
        // the layer is offscreen exactly while a dim is drawn (as iOS sets
        // no layer otherwise).
        compositingStrategy = if (f.dim > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val dim = controller.frame().dim
        if (dim > 0f) {
            drawRect(Color.Black.copy(alpha = dim), blendMode = BlendMode.SrcAtop)
        }
    }
    .testTag("bottom-chrome")

/**
 * Consumes all pointer input over the window while the selected stack is
 * transitioning — UIKit disables interaction during transitions, and 24
 * blocks its own host the same way. Reads `transition` in composition, so
 * it recomposes only at start and end. Back gestures are not pointer
 * events, so a predictive back drives the transition underneath
 * unimpeded. [modifier] should make it cover the window
 * (`Modifier.matchParentSize()` in the shell's `Box`).
 */
@Composable
fun ChromeTransitionBlocker(controller: ChromeVisibilityController, modifier: Modifier = Modifier) {
    if (controller.stack.transition != null) {
        Box(
            modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) change.consume()
                    }
                }
            },
        )
    }
}
