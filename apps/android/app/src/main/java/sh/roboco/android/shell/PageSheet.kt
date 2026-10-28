package sh.roboco.android.shell

import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.sheet.GlassSheetDefaults
import sh.roboco.android.design.sheet.SheetGrabber
import sh.roboco.android.design.sheet.SmoothTopRoundedShape

/**
 * What the content of a [SheetHost] entry is composed in: its [key], the
 * iOS dismissal from inside a sheet, stacking from the top sheet, and the
 * page's geometry for ticket 94's handoff capture.
 */
interface PageSheetScope {
    val key: String

    /**
     * `self.dismiss(animated:)` from inside a sheet: this entry and every
     * entry above it go together — New Session's ✕ tears a stacked New
     * Project down with it, exactly as UIKit tears down what a presented
     * controller presented.
     */
    fun dismiss(animated: Boolean = true)

    /**
     * Stacks a sheet on this one — only when this entry is the top one
     * and not hidden for handoff; `false` (Log.w) otherwise. The default
     * style is a `grabber = false` page sheet, New Project's shape.
     */
    fun present(
        key: String,
        style: SheetStyle = SheetStyle.Page(grabber = false),
        onDismissed: (() -> Unit)? = null,
        content: @Composable PageSheetScope.() -> Unit,
    ): Boolean

    /** The page's bounds in window coordinates, for ticket 94's handoff capture. */
    val pageBoundsInWindow: Rect

    /** The page's clip shape (top corners 38, continuous). */
    val pageShape: Shape

    /**
     * Ticket 111's hook (UIKit `isModalInPresentation`): while true the
     * sheet ignores drag-to-dismiss, system and predictive back are
     * consumed with no visual progress, and the a11y Dismiss action is
     * gone — a write is in flight. Programmatic [dismiss] ignores the
     * flag (the success path dismisses through it). Default false, so
     * ticket 31's behaviour is unchanged for every other sheet.
     */
    var dismissBlocked: Boolean
}

/**
 * The PageSheet's tunables (android-ui-parity #31). The shared values are
 * **aliases of [GlassSheetDefaults]** — never re-typed — so 17 and 31 stay
 * one system; the starred iOS approximations (38, 0.92, the peek, the
 * tablet width) come from `android-app#19` §Page sheet and are tuned
 * side by side with an iPhone recording.
 */
object PageSheetDefaults {

    /** The gap between the status bar and the sheet's top (`LargeTopGap`, 10 dp). */
    val TopInsetBelowStatusBar = GlassSheetDefaults.LargeTopGap

    /** This fraction of `H` dismisses on a slow release. */
    const val DismissFraction = GlassSheetDefaults.DismissFraction

    /** A release at least this fast (dp/s) is a dismiss fling. */
    val DismissVelocity = GlassSheetDefaults.FlingVelocity

    /** Predictive back moves this fraction of `H` at progress 1. */
    const val PredictiveBackTravel = GlassSheetDefaults.PredictiveBackTravel

    /** Scrim: black at this alpha in the light mode. */
    const val ScrimAlphaLight = GlassSheetDefaults.ScrimAlphaLight

    /** Scrim: black at this alpha in the dark mode. */
    const val ScrimAlphaDark = GlassSheetDefaults.ScrimAlphaDark

    /**
     * The sheet spring — `GlassSheetDefaults.Spring` (damping 1.0,
     * stiffness (9.2334/0.5)² = 341.02) — with the 1 px visibility
     * threshold. Ticket 104's dialog calls this name.
     */
    fun spring(): SpringSpec<Float> = GlassSheetDefaults.Spring.spec(visibilityThreshold = 1f)

    /** The sheet's top-corner radius (UIKit system value, starred). */
    val CornerRadius = 38.dp

    /** The presenting content's scale at rest under a sheet (starred). */
    const val RecededScale = 0.92f

    /**
     * The receded card's downward shift — the tuning knob for the real
     * iPhone's lower-sitting card (see the ticket's Comments; 0 for now).
     */
    val RecessionTopOffset = 0.dp

    /** How far a covered sheet's top peeks above the one covering it (starred). */
    val StackedPeek = 10.dp

    /** The sheet's width on tablets: `min(704 dp, windowWidth)`, centred (starred). */
    val TabletMaxWidth = 704.dp

    /**
     * At this window width and above, the sheet is a centred tablet card
     * without recession — the split shell's [SplitBreakpoint] (wave-10 42:
     * 744 dp, an upright tablet), so a split window never gets phone sheets.
     */
    val TabletBreakpoint = SplitBreakpoint
}

/**
 * The pure half of the page sheet (android-ui-parity #31): the progress
 * model — one value drives the scrim, the recession and the covered peek
 * — plus the release rule (the large-only case of ticket 17's settle
 * thresholds) and the predictive-back mapping. No Compose state, so
 * `PageSheetMathTest` runs on the plain JVM.
 */
internal object PageSheetMath {

    /** `p = if (hidden) 0 else (1 − y/H).coerceIn(0, 1)`; 0 before the first layout. */
    fun progress(offset: Float, height: Float, hidden: Boolean): Float {
        if (hidden || height <= 0f || offset.isNaN()) return 0f
        return (1f - offset / height).coerceIn(0f, 1f)
    }

    /** The presenting content's scale at recession progress [p] (1 → 0.92). */
    fun recededScale(p: Float): Float = 1f - (1f - PageSheetDefaults.RecededScale) * p

    /**
     * The release rule: dismiss on a downward fling (≥ [thresholdVelocityPx]),
     * or past [PageSheetDefaults.DismissFraction] of the way down unless the
     * release flings **up** past −[thresholdVelocityPx] — an upward fling
     * cancels the dismiss even past 25 %, mirroring UIKit's velocity
     * projection in spirit (`android-app#19`).
     */
    fun shouldDismiss(offset: Float, height: Float, velocityPxPerSec: Float, thresholdVelocityPx: Float): Boolean =
        velocityPxPerSec >= thresholdVelocityPx ||
            (offset >= PageSheetDefaults.DismissFraction * height && velocityPxPerSec > -thresholdVelocityPx)

    /** Predictive back progress `b` maps to `y = 0.25·H·b` (a short drag's scrub). */
    fun backOffset(progress: Float, height: Float): Float =
        PageSheetDefaults.PredictiveBackTravel * height * progress
}

/** Where a hidden-for-handoff frame is parked: far above the window, so it composes but never hits. */
private const val ParkAboveWindowPx = -1_000_000f

/**
 * The `SheetStyle.Page` frame (android-ui-parity #31): one large-detent
 * sheet wrapping ticket 17's stateless parts — [SmoothTopRoundedShape] at
 * 38 dp, [SheetGrabber], the opaque `Palette.background` fill — with the
 * bottom slide-in/out, drag/fling/predictive-back dismissal and
 * `imePadding` for its content. [SheetEntry]'s offset `Animatable` is
 * owned here: the frame is the one place that presents it (snap to `H`
 * on first layout, spring to 0), so the host never launches an animation.
 *
 * A stacked sheet on top recedes this one to 0.92 about its top centre
 * with a 10 dp upward peek, read through [coveredProgress] in the draw
 * phase only. While the entry is hidden for handoff the frame parks
 * above the window: composed and inert, no gesture, no back handler.
 */
@Composable
internal fun PageSheet(
    host: SheetHostState,
    entry: SheetEntry,
    scope: PageSheetScopeImpl,
    isTop: Boolean,
    coveredProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = RobocoTheme.palette
    val grabber = (entry.style as? SheetStyle.Page)?.grabber == true
    val topShape = remember { SmoothTopRoundedShape(PageSheetDefaults.CornerRadius) }
    val animScope = rememberCoroutineScope()
    val flingPx = with(density) { PageSheetDefaults.DismissVelocity.toPx() }

    BoxWithConstraints(modifier.fillMaxSize().testTag("page-sheet:${entry.key}")) {
        val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val sheetTop = statusBarTopDp + PageSheetDefaults.TopInsetBelowStatusBar
        val sheetHeight = maxHeight - sheetTop
        val sheetTopPx = with(density) { sheetTop.toPx() }
        val heightPx = with(density) { sheetHeight.toPx() }
        // The sheet's height, written from composition so the very first
        // draw already sees it (progress and dismissal read it in the
        // draw phase only; nothing in composition reads it back).
        entry.heightPx = heightPx
        val sheetWidth: Dp =
            if (maxWidth >= PageSheetDefaults.TabletBreakpoint) {
                minOf(PageSheetDefaults.TabletMaxWidth, maxWidth)
            } else {
                maxWidth
            }

        // -------------------------------------------------------- present
        // NaN until the first layout: snap to H, then spring up. A preview
        // or restored entry (offset already 0) skips the replay.
        LaunchedEffect(entry, heightPx) {
            if (entry.offset.value.isNaN()) {
                entry.offset.snapTo(heightPx)
                entry.offset.animateTo(0f, PageSheetDefaults.spring())
            }
        }

        // --------------------------------------------------------- settle
        val settle: (Float) -> Unit = remember(entry, host, heightPx, flingPx) {
            { velocity ->
                if (!entry.dismissing && !entry.hiddenForHandoff) {
                    if (!entry.dismissBlocked &&
                        PageSheetMath.shouldDismiss(entry.offset.value, heightPx, velocity, flingPx)
                    ) {
                        host.dismissEntryAndAbove(host.entries.indexOf(entry), animated = true, velocity = velocity)
                    } else {
                        // Blocked (111's submitting) or a cancel release: the
                        // sheet springs back to rest, never away.
                        host.settleBack(entry, velocity)
                    }
                }
            }
        }
        val nestedConnection = remember(entry, heightPx, animScope, settle) {
            PageSheetNestedScroll(entry, heightPx, animScope, settle)
        }

        // --------------------------------------------------- the sheet card
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset {
                    // NaN = not yet presented: park the card below the window.
                    val y = entry.offset.value
                    val position = if (y.isNaN()) heightPx else y
                    val park = if (entry.hiddenForHandoff) ParkAboveWindowPx else 0f
                    IntOffset(0, (sheetTopPx + position + park).roundToInt())
                }
                .size(sheetWidth, sheetHeight)
                .graphicsLayer {
                    alpha = if (entry.hiddenForHandoff) 0f else 1f
                    val c = coveredProgress()
                    val s = PageSheetMath.recededScale(c)
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = -PageSheetDefaults.StackedPeek.toPx() * c
                    clip = true
                    shape = topShape
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val y = entry.offset.value
                        if (!y.isNaN()) {
                            val target = (y + delta).coerceIn(0f, heightPx)
                            if (target != y) animScope.launch { entry.offset.snapTo(target) }
                        }
                    },
                    orientation = Orientation.Vertical,
                    enabled = !entry.hiddenForHandoff && !entry.dismissing && !entry.dismissBlocked,
                    startDragImmediately = entry.offset.isRunning,
                    onDragStopped = { velocity -> settle(velocity) },
                )
                .nestedScroll(nestedConnection)
                .testTag("page-sheet")
                .semantics {
                    isTraversalGroup = true
                    // 111: while a write blocks dismissal there is no a11y
                    // Dismiss action either (programmatic dismiss still works).
                    if (!entry.dismissBlocked) {
                        customActions = listOf(
                            CustomAccessibilityAction(label = "Dismiss") {
                                scope.dismiss(true)
                                true
                            },
                        )
                    }
                }
                .onGloballyPositioned { coordinates -> scope.updatePageBounds(coordinates.boundsInWindow()) },
        ) {
            // Predictive back is registered **before** the content, so a
            // handler inside the content (a menu, an inner NavStack) wins.
            // The handler stays enabled while dismissal is blocked (111):
            // the gesture is consumed with no visual progress and no
            // dismissal, never passed to whatever is below the sheet.
            PredictiveBackHandler(enabled = isTop && !entry.hiddenForHandoff && !entry.dismissing) { events ->
                if (entry.dismissBlocked) {
                    try {
                        events.collect { }
                    } catch (e: CancellationException) {
                        throw e
                    }
                    return@PredictiveBackHandler
                }
                try {
                    events.collect { event ->
                        // b ∈ [0, 1] scrubs y to 0.25·H·b; commit dismisses.
                        entry.offset.snapTo(PageSheetMath.backOffset(event.progress, entry.heightPx))
                    }
                    scope.dismiss(animated = true)
                } catch (e: CancellationException) {
                    // Gesture abandoned: spring back to rest. This coroutine
                    // is already cancelling, so the animation runs in the
                    // composition scope.
                    if (!entry.dismissing) {
                        animScope.launch { entry.offset.animateTo(0f, PageSheetDefaults.spring()) }
                    }
                    throw e
                }
            }

            // The page. Its own HazeState (ticket 07): glass inside the
            // sheet samples the sheet's page — the background Box is the
            // source — never the receded screen behind it, and the
            // grabber sits outside the source.
            ProvideHazeState {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(palette.background)
                            .hazeSource(LocalHazeState.current!!),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            // The top inset is consumed here: a
                            // statusBarsPadding() inside the sheet adds
                            // nothing. The bottom nav-bar inset is not —
                            // contents apply navigationBarsPadding() where
                            // iOS uses the safe area.
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                            .imePadding(),
                    ) {
                        CompositionLocalProvider(LocalPageSheetScope provides scope) {
                            entry.content(scope)
                        }
                    }
                    if (grabber) {
                        SheetGrabber(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

/**
 * [PageSheetScope] over its [SheetHostState]: dismissal by index (the
 * entry and everything above it), stacking from the top entry only, and
 * the page geometry ticket 94 captures.
 */
internal class PageSheetScopeImpl(
    private val host: SheetHostState,
    private val entry: SheetEntry,
) : PageSheetScope {

    private var bounds by mutableStateOf(Rect.Zero)

    override val key: String get() = entry.key

    override fun dismiss(animated: Boolean) {
        host.dismissEntryAndAbove(host.entries.indexOf(entry), animated)
    }

    override fun present(
        key: String,
        style: SheetStyle,
        onDismissed: (() -> Unit)?,
        content: @Composable PageSheetScope.() -> Unit,
    ): Boolean {
        if (entry.hiddenForHandoff) {
            Log.w("SheetHost", "present(\"$key\") from the hidden sheet \"${entry.key}\" ignored")
            return false
        }
        if (host.entries.lastOrNull() !== entry) {
            Log.w("SheetHost", "present(\"$key\") from the covered sheet \"${entry.key}\" ignored: not on top")
            return false
        }
        return host.insertEntry(key, style, onDismissed, content) != null
    }

    override val pageBoundsInWindow: Rect get() = bounds

    override val pageShape: Shape = SmoothTopRoundedShape(PageSheetDefaults.CornerRadius)

    override var dismissBlocked: Boolean
        get() = entry.dismissBlocked
        set(value) {
            entry.dismissBlocked = value
        }

    /** Called from the frame's `onGloballyPositioned` (layout phase). */
    internal fun updatePageBounds(rect: Rect) {
        bounds = rect
    }
}

/**
 * The scroll/drag coupling of a page sheet with scrolling content:
 * pulling a scroller that is at its top moves the sheet instead, and
 * moving up takes an offset sheet back to 0 first. Only
 * [NestedScrollSource.UserInput] is handled — a content fling that
 * reaches its top dispatches as `SideEffect` and must not drag the sheet
 * (it would be left half-open, since nothing settles it after the
 * fling) — and `onPreFling` settles the sheet whenever it is offset.
 */
private class PageSheetNestedScroll(
    private val entry: SheetEntry,
    private val heightPx: Float,
    private val scope: CoroutineScope,
    private val settle: (Float) -> Unit,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || entry.hiddenForHandoff || entry.dismissing ||
            entry.dismissBlocked
        ) {
            return Offset.Zero
        }
        val y = entry.offset.value
        // Finger moving up with the sheet offset: the sheet goes back to
        // 0 before the content scrolls (consume up to the offset).
        if (available.y < 0f && !y.isNaN() && y > 0f) {
            val target = (y + available.y).coerceIn(0f, heightPx)
            val moved = target - y
            if (moved < 0f) {
                scope.launch { entry.offset.snapTo(target) }
                return Offset(0f, moved)
            }
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || entry.hiddenForHandoff || entry.dismissing ||
            entry.dismissBlocked
        ) {
            return Offset.Zero
        }
        val y = entry.offset.value
        // Finger moving down and the content is at its top: the sheet
        // takes the leftover delta and moves down with it.
        if (available.y > 0f && !y.isNaN()) {
            val target = (y + available.y).coerceIn(0f, heightPx)
            val moved = target - y
            if (moved > 0f) {
                scope.launch { entry.offset.snapTo(target) }
                return Offset(0f, moved)
            }
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val y = entry.offset.value
        // Release while the sheet is offset: settle it with the release
        // velocity and consume it all, so the content never starts a fling.
        // While dismissal is blocked (111) the settle springs the sheet
        // back to rest instead of dismissing it.
        if (!entry.hiddenForHandoff && !entry.dismissing && !y.isNaN() && y > 0f) {
            settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity.Zero
}
