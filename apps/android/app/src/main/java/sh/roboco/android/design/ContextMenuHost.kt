package sh.roboco.android.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.roboco.android.design.glass.GlassMenuContent
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuPlacement
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The context-menu host (android-ui-parity #16): draws the lifted-row
 * overlay over the whole window — the dimmed, blurred backdrop, the
 * lifted preview and ticket 15's menu panel — and provides
 * [LocalContextMenuHost] to `Modifier.contextMenu`.
 *
 * This is not a `Popup` or `Dialog`: a different window would break the
 * shared [GraphicsLayer] (the live preview), the
 * [LayoutCoordinates]-relative geometry and the content blur. The content
 * box is the one `hazeSource` of the host's own [dev.chrisbanes.haze.HazeState]
 * and carries the `RenderEffect` blur; the overlay composes above it as a
 * sibling under `LocalHazeState provides ctxHaze` (ticket 07's convention:
 * the panel is never inside the source it blurs).
 *
 * Mount once wrapping the outermost app content, inside [RobocoTheme] and
 * outside the alert, toast, action-sheet, glass-menu and sheet hosts
 * (ticket 35's `RootHosts` order). Until then the gallery
 * (`GalleryProviders`) and the tests wrap content in it.
 */
val LocalContextMenuHost = staticCompositionLocalOf<ContextMenuHostState?> { null }

/**
 * Test-only: the lift's current progress (0…1), exposed for UI tests on an
 * invisible child of the overlay — the [ToastSlideKey] /
 * [GlassMenuProgressKey] convention. Production code never reads it.
 */
val ContextLiftKey = SemanticsPropertyKey<Float>("ContextLift")

/**
 * Draws context menus over the app content, above everything in the
 * window, and provides [LocalContextMenuHost].
 */
@Composable
fun ContextMenuHost(content: @Composable () -> Unit) {
    val state = remember { ContextMenuHostState() }
    val ctxHaze = rememberHazeState()
    CompositionLocalProvider(LocalContextMenuHost provides state) {
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { state.hostCoords = it },
        ) {
            // 1. The app content: the one haze source of this window's
            //    context-menu glass, blurred as the lift rises (the draw-phase
            //    read below animates without recomposition).
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(ctxHaze)
                    .graphicsLayer {
                        val lift = state.active?.lift?.value ?: 0f
                        val radius = ContextMenuTokens.BlurRadius.toPx() * lift
                        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
                    },
            ) { content() }
            // 2. The overlay: a sibling above the source, blurring the
            //    content below it through ctxHaze.
            state.active?.let { active ->
                CompositionLocalProvider(LocalHazeState provides ctxHaze) {
                    ContextMenuOverlay(active, state, state.hostCoords)
                }
            }
        }
    }
}

/**
 * The one-at-a-time state behind [ContextMenuHost]. `Modifier.contextMenu`
 * opens through [open]; anything (Back, outside tap, item pick, ON_STOP)
 * reverses through [dismiss].
 */
@Stable
class ContextMenuHostState internal constructor() {

    /** The active lift; `null` = idle. */
    internal var active by mutableStateOf<ActiveLift?>(null)
        private set

    /** The host box's coordinates, set by [ContextMenuHost]'s `onPlaced`. */
    internal var hostCoords: LayoutCoordinates? = null

    val isOpen: Boolean get() = active != null

    /**
     * Starts the reversal: ticket 15's panel fades over its 150 ms while
     * the lift springs back to 0; the overlay leaves when both have
     * finished. A no-op when closed or already reversing (a second
     * `dismiss` during the reverse does nothing).
     *
     * `animated = false` (ON_STOP, ticket 35's root swap) removes the
     * overlay in the same frame: the row draws itself again and the menu
     * goes without its fade.
     */
    fun dismiss(animated: Boolean = true) {
        val current = active ?: return
        if (!animated) {
            // The overlay's animation driver is cancelled by the removal
            // itself (its LaunchedEffect leaves composition), which cancels
            // the running lift animation — the same frame, no suspend call
            // needed from this non-suspending API.
            release(current)
            return
        }
        if (!current.menuOpen) return // already reversing
        current.menuOpen = false // 15's panel starts its 150 ms fade now
    }

    /**
     * Lifts [source] with [menu] for a touch at [posInRow] (row-local px).
     * Fails (no lift) when another lift is active, the row is disposed or
     * its coordinates are gone.
     */
    internal fun open(source: LiftSource, menu: Menu, posInRow: Offset): Boolean {
        if (active != null) return false
        if (source.disposed) return false
        val coords = source.coords ?: return false
        val host = hostCoords ?: return false
        if (!coords.isAttached || !host.isAttached) return false
        val touchInHost = host.localPositionOf(coords, posInRow)
        source.lifted = true
        source.liftState?.isLifted = true
        active = ActiveLift(source, menu, touchInHost)
        return true
    }

    /** Clears [lift] and lets the row draw itself again; idempotent. */
    internal fun release(lift: ActiveLift) {
        lift.source.lifted = false
        lift.source.liftState?.isLifted = false
        if (active === lift) active = null
    }
}

/**
 * The row's half of a lift: the recorded layer, the highlight preset and
 * the caller's lift state, plus the live geometry the overlay follows.
 */
internal class LiftSource(
    val layer: GraphicsLayer,
    val highlight: LiftHighlight,
    val liftState: ContextMenuLiftState?,
) {
    /** The row's latest coordinates; `null` before the first layout. */
    var coords: LayoutCoordinates? = null

    /** Bumped from `onGloballyPositioned` **while lifted** — never unconditionally (an invalidation loop). */
    var geometryTick by mutableIntStateOf(0)

    /** True while the overlay draws this row's layer and the row hides its own. */
    var lifted by mutableStateOf(false)

    /** True once the row's `contextMenu` modifier left composition. */
    var disposed by mutableStateOf(false)
}

/** One open context menu: the lift animation and ticket 15's panel state. */
internal class ActiveLift(
    val source: LiftSource,
    val menu: Menu,
    /** Where the long press landed, in host coordinates. */
    val touchInHost: Offset,
) {
    /** The lift progress: 0 = the row in place, 1 = lifted. */
    val lift = Animatable(0f)

    /** 15's `open`: flips false at the start of the close (the 150 ms fade). */
    var menuOpen by mutableStateOf(true)

    /** Set by the panel's `onClosed`, after the 150 ms close fade. */
    var menuGone by mutableStateOf(false)
}

/**
 * Places the menu for a lifted preview — the ticket's placement table,
 * pure pixel maths (all values host px, pinned by
 * [ContextMenuPlacementTest] on the plain JVM):
 *
 *  1. **Vertical**: **below** when `preview.bottom + gap + menu.height ≤
 *     safe.bottom`; else **above** when `preview.top − gap − menu.height ≥
 *     safe.top`; else **overlap**: `menuTop = clamp(touch.y, safe.top,
 *     max(safe.top, safe.bottom − menu.height))` — a row taller than the
 *     screen shows its menu at the touch. Overlap reports `below = true`
 *     (the panel grows downward from the top).
 *  2. **Horizontal**: the touch's side picks the aligned edge — the menu's
 *     left edge at `preview.left` when the touch is left of the preview's
 *     centre, else its right edge at `preview.right` — then clamped into
 *     `[safe.left, max(safe.left, safe.right − menu.width)]`. The rule is
 *     in absolute coordinates, so it is the same in RTL.
 *  3. **Height**: `maxHeight` is the room on the chosen side; in the
 *     overlap case the whole safe height (the panel scrolls inside ticket
 *     15's `maxHeight`).
 *
 * [safe] is the host bounds minus `WindowInsets.safeDrawing`, already
 * deflated by [ContextMenuTokens.WindowMargin]. [preview] is the **scaled**
 * (lifted) preview rect. Separate from ticket 15's `placeGlassMenu` on
 * purpose: 15 picks the side by the anchor's centre and shrinks the panel
 * to the larger side, while this uses the touch side and overlaps at the
 * touch for rows taller than the screen.
 */
internal fun placeContextMenu(
    preview: Rect,
    touch: Offset,
    menu: IntSize,
    safe: Rect,
    gapPx: Float,
): MenuPlacement {
    // 1. Vertical: below → above → overlap at the touch.
    val belowStart = preview.bottom + gapPx
    val aboveEnd = preview.top - gapPx
    val below: Boolean
    val y: Int
    val maxHeight: Int
    if (belowStart + menu.height <= safe.bottom) {
        below = true
        y = belowStart.roundToInt()
        maxHeight = (safe.bottom - belowStart).roundToInt().coerceAtLeast(1)
    } else if (aboveEnd - menu.height >= safe.top) {
        below = false
        y = (aboveEnd - menu.height).roundToInt()
        maxHeight = (aboveEnd - safe.top).roundToInt().coerceAtLeast(1)
    } else {
        // Overlap: at the touch, clamped into the safe area.
        below = true
        val ceiling = max(safe.top, safe.bottom - menu.height)
        y = touch.y.coerceIn(safe.top, ceiling).roundToInt()
        maxHeight = (safe.bottom - safe.top).roundToInt().coerceAtLeast(1)
    }

    // 2. Horizontal: the touch's side picks the aligned edge, then clamp.
    val alignEnd = !(touch.x < preview.center.x)
    val rawX = if (alignEnd) preview.right.toInt() - menu.width else preview.left.toInt()
    val x = rawX.coerceIn(safe.left.toInt(), max(safe.left.toInt(), safe.right.toInt() - menu.width))

    return MenuPlacement(IntOffset(x, y), maxHeight, below, alignEnd)
}

/**
 * The open overlay: the backdrop, the lifted preview and the menu panel.
 * Nothing here runs while idle — the host only composes it while
 * `active != null`.
 */
@Composable
private fun ContextMenuOverlay(
    active: ActiveLift,
    host: ContextMenuHostState,
    hostCoords: LayoutCoordinates?,
) {
    val density = LocalDensity.current
    val palette = RobocoTheme.palette
    val dark = LocalIsDark.current
    val reduce = LocalReduceMotion.current
    val source = active.source
    val highlight = source.highlight
    val dim = if (dark) ContextMenuTokens.DimDark else ContextMenuTokens.DimLight

    // Back is registered outside the menu presentation, so ticket 15's
    // submenu Back handler — composed later, deeper — wins while a submenu
    // is drilled in; at the root 15's pop dismisses, and this one covers
    // the close.
    BackHandler(enabled = true) { host.dismiss() }

    // App backgrounded (or ticket 35's root swap): out at once, no fade.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { host.dismiss(animated = false) }

    // The animation driver: lift up, wait for the dismiss, lift down, then
    // remove only when the lift has settled at 0 **and** 15's panel
    // finished its 150 ms fade. An early dismiss interrupts the open
    // flight (the Animatable's mutex cancels it) and springs back from
    // wherever it was.
    val liftSpec = if (reduce) ContextMenuTokens.ReducedLift else ContextMenuTokens.LiftSpring
    LaunchedEffect(active) {
        launch { active.lift.animateTo(1f, liftSpec) }
        snapshotFlow { active.menuOpen }.first { !it }
        active.lift.animateTo(0f, liftSpec)
        snapshotFlow { active.menuGone }.first { it }
        host.release(active)
    }

    // The source row left composition while open (Archive removed it):
    // stop drawing the preview at once, keep animating the backdrop and
    // menu out.
    LaunchedEffect(active) {
        snapshotFlow { source.disposed }.first { it }
        host.dismiss()
    }

    // ------------------------------------------------------------ geometry

    // The tick read registers the invalidation: the preview follows the
    // row while lifted (a layout change above it).
    val geometryTick = source.geometryTick
    val coords = source.coords
    val full: Rect
    val visible: Rect
    if (hostCoords != null && coords != null && coords.isAttached) {
        full = hostCoords.localBoundingBoxOf(coords, clipBounds = false)
        visible = hostCoords.localBoundingBoxOf(coords, clipBounds = true)
    } else {
        full = Rect.Zero
        visible = Rect.Zero
    }
    val hInset = with(density) { highlight.horizontalInset.toPx() }
    val vInset = with(density) { highlight.verticalInset.toPx() }
    val highlightRect = Rect(
        left = full.left + hInset,
        top = full.top + vInset,
        right = full.right - hInset,
        bottom = full.bottom - vInset,
    )
    val previewRect = highlightRect.intersect(visible)
    val previewVisible = !previewRect.isEmpty
    // A row scrolled fully out (or gone coordinates): dismiss at once.
    LaunchedEffect(previewVisible, geometryTick) {
        if (!previewVisible) host.dismiss()
    }

    // The scaled (lifted) preview rect — placement only.
    val halfW = previewRect.width * ContextMenuTokens.LiftScale / 2f
    val halfH = previewRect.height * ContextMenuTokens.LiftScale / 2f
    val scaled = Rect(
        left = previewRect.center.x - halfW,
        top = previewRect.center.y - halfH,
        right = previewRect.center.x + halfW,
        bottom = previewRect.center.y + halfH,
    )

    // The safe area: the host bounds minus safeDrawing, deflated by the
    // window margin (safeDrawing includes the IME, so a menu opened while
    // the keyboard is up stays above it).
    val insets = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val marginPx = with(density) { ContextMenuTokens.WindowMargin.toPx() }
    val hostSize = hostCoords?.size ?: IntSize.Zero
    val safe = Rect(
        left = insets.getLeft(density, layoutDirection) + marginPx,
        top = insets.getTop(density) + marginPx,
        right = hostSize.width - insets.getRight(density, layoutDirection) - marginPx,
        bottom = hostSize.height - insets.getBottom(density) - marginPx,
    )

    Box(Modifier.fillMaxSize().testTag("context-menu-overlay")) {
        // Test-only lift progress, isolated so its per-frame recomposition
        // never touches the overlay content (the GlassMenuProgressKey rule).
        ContextLiftSemantics(active)

        // 1. The backdrop: the full-window scrim. UIKit's VoiceOver label
        //    for the context-menu dimming view.
        Spacer(
            Modifier
                .fillMaxSize()
                .testTag("context-menu-backdrop")
                .drawBehind {
                    drawRect(Color.Black, alpha = (dim * active.lift.value).coerceIn(0f, 1f))
                }
                .semantics {
                    contentDescription = "Dismiss context menu"
                    onClick(label = "Dismiss context menu") {
                        host.dismiss()
                        true
                    }
                }
                .dismissOnTap(host),
        )

        // 2. The lifted preview: the row's own layer, drawn at the exact
        //    on-screen rect — no jump in, no jump out. Skipped once the
        //    row (or its layer) is gone.
        if (previewVisible && !source.disposed && !source.layer.isReleased) {
            val shape = rememberSmoothRoundedShape(highlight.cornerRadius)
            Box(
                Modifier
                    .offset { IntOffset(previewRect.left.roundToInt(), previewRect.top.roundToInt()) }
                    .size(with(density) { previewRect.size.toDpSize() })
                    .graphicsLayer {
                        // Reduce Motion: no scale, the lift cross-fades.
                        val scale = if (reduce) 1f else 1f + (ContextMenuTokens.LiftScale - 1f) * active.lift.value
                        scaleX = scale
                        scaleY = scale
                    }
                    .dropShadow(shape) {
                        radius = ContextMenuTokens.ShadowRadius.toPx()
                        offset = Offset(0f, ContextMenuTokens.ShadowOffsetY.toPx())
                        color = Color.Black
                        alpha = (ContextMenuTokens.ShadowAlpha * active.lift.value).coerceIn(0f, 1f)
                    }
                    .clip(shape)
                    .drawBehind {
                        drawRect(palette.background)
                        if (highlight.fill == LiftFill.Pressed) drawRect(palette.controlFill)
                        // The layer is recorded in the row's full coordinate
                        // space; shift it into the preview's clipped space.
                        translate(full.left - previewRect.left, full.top - previewRect.top) {
                            drawLayer(source.layer)
                        }
                    }
                    .dismissOnTap(host)
                    .testTag("context-menu-preview")
                    .semantics { invisibleToUser() },
            )
        }

        // 3. The menu: ticket 15's stateful panel, measured twice —
        //    unconstrained for the placement, then within it. 15 animates
        //    from the placement's origin corner and owns the levels,
        //    deferred loads, pick order and Back.
        var origin by remember { mutableStateOf(TransformOrigin.Center) }
        var menuMaxHeight by remember { mutableStateOf(Dp.Unspecified) }
        Layout(
            content = {
                GlassMenuContent(
                    menu = active.menu,
                    open = active.menuOpen,
                    origin = origin,
                    onDismissRequest = { host.dismiss() },
                    onClosed = { active.menuGone = true },
                    maxHeight = menuMaxHeight,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val child = measurables.first()
            val maxW = safe.width.toInt().coerceAtLeast(1)
            val gapPx = ContextMenuTokens.MenuGap.toPx()
            // The full panel size through intrinsics: a scrollable panel may
            // not be measured with an infinite height, and only once.
            val fullWidth = child.maxIntrinsicWidth(Constraints.Infinity).coerceIn(0, maxW)
            val fullHeight = child.maxIntrinsicHeight(maxW)
            val estimate = placeContextMenu(scaled, active.touchInHost, IntSize(fullWidth, fullHeight), safe, gapPx)
            menuMaxHeight = estimate.maxHeight.toDp()
            val placed = child.measure(Constraints(maxWidth = maxW, maxHeight = estimate.maxHeight))
            val placement = if (placed.width == fullWidth) {
                estimate
            } else {
                placeContextMenu(scaled, active.touchInHost, IntSize(placed.width, fullHeight), safe, gapPx)
            }
            origin = placement.origin
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.place(placement.offset.x, placement.offset.y)
            }
        }
    }
}

/** The test-only [ContextLiftKey] on an invisible, empty child. */
@Composable
private fun ContextLiftSemantics(active: ActiveLift) {
    val value = active.lift.value
    Box(Modifier.semantics { this[ContextLiftKey] = value })
}

/** A tap (down consumed, up inside) dismisses — the backdrop and the preview share it. */
private fun Modifier.dismissOnTap(host: ContextMenuHostState): Modifier =
    pointerInput(host) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            waitForUpOrCancellation()?.let { host.dismiss() }
        }
    }
