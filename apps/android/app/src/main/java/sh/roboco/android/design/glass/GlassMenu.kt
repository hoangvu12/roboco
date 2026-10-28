package sh.roboco.android.design.glass

import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.Icon
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.rgba
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `GlassMenu` (android-ui-parity #15): Roboco's port of the iOS system
 * menu (`UIMenu` / SwiftUI `Menu`), drawn with Compose foundation over
 * ticket 07's glass — **no Material** anywhere (no `DropdownMenu`, no
 * ripple). The measured geometry comes from the iOS 26 system-menu capture
 * (`appshots-ios-queue-actions.png`, §15 "System-menu capture"): leading
 * icons, 42 dp row pitch, 10 dp panel padding, 250/300 dp width, 22 dp
 * continuous corners, disabled titles grey (not faded), disabled icons
 * full ink.
 *
 * This file holds the tokens, the stateless [GlassMenuPanel], the stateful
 * window-less [GlassMenuContent], and the in-window [GlassMenuHost] with
 * its triggers. Ticket 16 embeds [GlassMenuContent] inside its own
 * context-menu overlay (it does **not** use the host or `placeGlassMenu`).
 */
internal object GlassMenuTokens {
    /** Panel width: `max(250, intrinsic content width)`. */
    val MinWidth = 250.dp

    /** Panel width cap (tuning: no capture pinned a maximum). */
    val MaxWidth = 300.dp

    /** Continuous corner radius (22 × 1.6 smoothing ≈ 35 dp straightening). */
    val Radius = 22.dp

    /** Vertical panel padding (capture: 10.3 / 9.9 pt). */
    val PadV = 10.dp

    /** Row min height (capture pitch 41.4 / 42.1 pt). */
    val RowHeight = 42.dp

    /** The leading icon box's centre from the panel's leading edge (capture 35.7). */
    val SlotCenter = 36.dp

    /** The leading icon box (a 20.4 dp icon may overhang 0.2; never clipped). */
    val SlotBox = 20.dp

    /** [SlotCenter] − [SlotBox] / 2: the slot Box's start offset. */
    val SlotOffsetX = SlotCenter - SlotBox / 2

    /** Title text start when the slot is reserved (capture 61.5 → 61). */
    val TextStart = 61.dp

    /** Title text start when no row reserves the slot. */
    val TextStartNoSlot = 20.dp

    /** Trailing padding to the panel edge (also the submenu chevron's edge). */
    val PadEnd = 20.dp

    /** Text → submenu chevron gap. */
    val SubmenuChevronGap = 8.dp

    /** The trailing `chevron.right` box: 13 pt × 1.2 (ticket 06's disclosure size). */
    val SubmenuChevronBox = 15.6.dp

    /** Title → subtitle gap. */
    val SubtitleGap = 2.dp

    /**
     * Row content padding, top and bottom (wraps grow past [RowHeight]).
     * The ticket's nominal 11 assumed a 20 dp line; Geist's natural line box
     * is 1.3 × 16 = 20.8 dp, so 11 + 20.8 + 11 made every single-line row
     * 42.8 dp (CI r23: a 128 px pitch at xxhdpi, not the AC's 42 dp ± 0.5).
     * 10.4 ((42 − 20.8) / 2 = 10.6, rounded down so the per-edge px
     * rounding never pushes one line past 42 at any density) keeps one line
     * inside the 42 dp minimum; larger text and wraps still grow the row
     * through it.
     */
    val RowPadV = 10.4.dp

    /** Menu title line padding (tuning values). */
    val TitlePadTop = 6.dp
    val TitlePadBottom = 8.dp

    /** Inline section header (tuning values). */
    val HeaderHeight = 30.dp
    val HeaderPadTop = 8.dp
    val HeaderPadBottom = 4.dp

    /** Brand marks in the leading slot (iOS `BrandMarks.image(for:side:16)`). */
    val BrandSide = 16.dp

    /** SF point size of row icons and the checkmark (ticket 06: 17 pt Regular). */
    const val IconPt = 17f

    /** Separator: an 8 dp block, 1 px hairline inset from both edges. */
    val SeparatorBlock = 8.dp
    val SeparatorInset = 20.dp

    /** Press highlight (tuning values). */
    val HighlightInset = 8.dp
    val HighlightRadius = 14.dp

    /** The placement margin (from `pr621`'s `AnchoredMenu`). */
    val Margin = 12.dp

    /** Open scale from `placement.origin` (approximation: UIKit animates `UIMenu`). */
    const val OpenScale = 0.9f

    /**
     * Open spring, UIKit 0.35 s / ζ 0.86 (settle-matched stiffness 526.67 —
     * computed, never pasted). Ticket 04 has no menu token, so it lives
     * here; the easing is 04's `Motion.EaseInOut`.
     */
    val OpenSpring = IosSpring("menuOpen", 0.35, 0.86f)

    /** Pre-built open spec for [animateContentSize] and the scale/alpha flight. */
    val openSpring: SpringSpec<Float> = OpenSpring.spec()

    /** Close fade duration. */
    const val CloseMs = 150

    /** Drill-in cross-fade duration. */
    const val DrillFadeMs = 150

    /** The Reduce-Motion open fade (same 150 ms cross-fade). */
    const val ReducedFadeMs = 150
}

/**
 * Test-only: the menu's open/close progress (the animating alpha, 0…1),
 * exposed for UI tests on an invisible child of the panel layer. Production
 * code never reads it.
 */
val GlassMenuProgressKey = SemanticsPropertyKey<Float>("GlassMenuProgress")

/** How an element opens its menu: `showsMenuAsPrimaryAction`.
 *
 * - [Tap] — a single tap opens the menu (`= true`, chip buttons, ⋯ bars);
 * - [LongPress] — a long press opens it and fires [Haptic.LongPress] at
 *   the moment it opens (`= false`, the send button); a tap still runs the
 *   element's own `onClick`.
 */
enum class MenuTrigger { Tap, LongPress }

// ---------------------------------------------------------------------------
// The host: one menu at a time over the whole window
// ---------------------------------------------------------------------------

/** One presentation handed to [GlassMenuHostState]. */
@Immutable
internal class GlassMenuPresentation(
    val id: Long,
    /** The anchor in **window** coordinates (`boundsInWindow()`), px. */
    val anchor: Rect,
    val menu: Menu,
    val cover: Boolean,
    val onDismissed: (() -> Unit)?,
)

/**
 * The host's presenting state: at most one menu, replaced instantly by the
 * next `present` (like the toaster); [close] starts the fade and [finished]
 * clears the presentation when it ends.
 */
@Stable
internal class GlassMenuHostState {

    var presentation: GlassMenuPresentation? by mutableStateOf(null)
        private set

    val isPresenting: Boolean get() = presentation != null

    /** True from the moment a close starts until the fade ends. */
    var isClosing: Boolean by mutableStateOf(false)
        private set

    /**
     * Presents [menu] anchored at [anchor]. Returns the presentation id, or
     * `null` when the menu has no content (nothing opens — no haptic).
     * One menu at a time: the next `present` replaces the current one
     * instantly (no fade, like the toaster) and fires its dismiss callback.
     */
    fun present(
        anchor: Rect,
        menu: Menu,
        cover: Boolean = true,
        onDismissed: (() -> Unit)? = null,
    ): Long? {
        if (!menu.hasContent()) return null
        presentation?.let { superseded -> superseded.onDismissed?.invoke() }
        val presentation = GlassMenuPresentation(nextId++, anchor, menu, cover, onDismissed)
        this.presentation = presentation
        isClosing = false
        return presentation.id
    }

    /** Starts the close (the fade, then removal). No-op when already closing. */
    fun close() {
        if (presentation != null && !isClosing) isClosing = true
    }

    /** Starts the close for [id] only (a stale [GlassMenuPopup] leaving composition). */
    internal fun dismiss(id: Long) {
        if (presentation?.id == id) close()
    }

    /** Called by the overlay when the fade finished: remove, reset, notify. */
    internal fun finished(id: Long) {
        val current = presentation
        if (current?.id == id) {
            presentation = null
            isClosing = false
            current.onDismissed?.invoke()
        }
    }

    private var nextId = 1L
}

/**
 * The ambient [GlassMenuHostState]; `null` outside a [GlassMenuHost] (menus
 * in previews do nothing; `rememberGlassMenuState()` throws instead).
 */
internal val LocalGlassMenuHost = staticCompositionLocalOf<GlassMenuHostState?> { null }

/**
 * Draws menus over the app content, above everything in the window, and
 * provides [LocalGlassMenuHost]. Mount once, wrapping the app root
 * (ticket 35). The content is the one `hazeSource` of this window's
 * [HazeState]; the menu panel is a **sibling** drawn after it (never
 * inside the source it blurs) — ticket 07's convention, and the reason
 * this is not a `Popup`: Haze cannot blur across windows, and a focusable
 * popup would dismiss the IME (the composer's attach and send menus must
 * leave the keyboard up, as on iOS).
 *
 * Must be inside `RobocoTheme` (the panel needs the palette and the type
 * scale).
 */
@Composable
fun GlassMenuHost(
    hazeState: HazeState = rememberHazeState(),
    content: @Composable () -> Unit,
) {
    val host = remember { GlassMenuHostState() }
    CompositionLocalProvider(LocalGlassMenuHost provides host) {
        Box(Modifier.fillMaxSize()) {
            // 1. The app content: the only haze source of this window.
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) { content() }
            // 2. The overlay: a sibling above the source, keyed by the
            //    presentation id so a replacement leaves composition the
            //    same frame (instant, like the toaster).
            host.presentation?.let { presenting ->
                key(presenting.id) {
                    CompositionLocalProvider(LocalHazeState provides hazeState) {
                        GlassMenuOverlay(host, presenting)
                    }
                }
            }
        }
    }
}

/** The full-host undimmed tap catcher plus the anchored panel. */
@Composable
private fun GlassMenuOverlay(host: GlassMenuHostState, presentation: GlassMenuPresentation) {
    val density = LocalDensity.current
    // Window insets, read in composition so an IME change re-places the menu.
    val insetsTop = WindowInsets.statusBars.getTop(density)
    val insetsBottom = max(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.ime.getBottom(density),
    )
    // The overlay's own origin in the window (usually 0,0: the host fills
    // the window) — used to convert the window-space anchor into host space.
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    // The placement decided at open, frozen while the panel resizes.
    var forceBelow by remember { mutableStateOf<Boolean?>(null) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }

    Box(Modifier.fillMaxSize()) {
        // The catcher: not dimmed (pull-downs never dim), consumes the
        // dismissing tap so it never reaches the app beneath, and closes on
        // a real up. The panel sits above it and swallows the taps that land
        // on it, so they never get here.
        Box(
            Modifier
                .fillMaxSize()
                .testTag("glass-menu-scrim")
                .semantics {
                    contentDescription = "Dismiss menu"
                    onClick(label = "Dismiss menu") {
                        host.close()
                        true
                    }
                }
                .pointerInput(host) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            host.close()
                        }
                    }
                },
        )
        // The panel: its full size read through intrinsics (a scrollable
        // panel may not be measured with an infinite height, and a child may
        // be measured only once), placed by [placeGlassMenu], then measured
        // within the room so content beyond it scrolls.
        Layout(
            content = {
                GlassMenuContent(
                    menu = presentation.menu,
                    open = !host.isClosing,
                    origin = origin,
                    onDismissRequest = { host.close() },
                    onClosed = { host.finished(presentation.id) },
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostOrigin = it.positionInWindow() },
        ) { measurables, constraints ->
            val child = measurables.first()
            val margin = GlassMenuTokens.Margin.roundToPx()
            val panelMaxWidth = min(
                GlassMenuTokens.MaxWidth.roundToPx(),
                (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
            )
            val fullWidth = child.maxIntrinsicWidth(Constraints.Infinity).coerceIn(0, panelMaxWidth)
            val fullHeight = child.maxIntrinsicHeight(panelMaxWidth)
            val anchor = IntRect(
                left = (presentation.anchor.left - hostOrigin.x).roundToInt(),
                top = (presentation.anchor.top - hostOrigin.y).roundToInt(),
                right = (presentation.anchor.right - hostOrigin.x).roundToInt(),
                bottom = (presentation.anchor.bottom - hostOrigin.y).roundToInt(),
            )
            val bounds = IntRect(
                left = 0,
                top = insetsTop,
                right = constraints.maxWidth,
                bottom = max(insetsTop, constraints.maxHeight - insetsBottom),
            )
            fun place(width: Int) = placeGlassMenu(
                anchor = anchor,
                panel = IntSize(width, fullHeight),
                bounds = bounds,
                margin = margin,
                cover = presentation.cover,
                forceBelow = forceBelow,
            )
            val estimate = place(fullWidth)
            if (forceBelow == null) forceBelow = estimate.below
            val placed = child.measure(
                Constraints(maxWidth = panelMaxWidth, maxHeight = estimate.maxHeight),
            )
            // The measured width (min-width clamps included) decides the
            // horizontal edge; the direction and room stay the estimate's.
            val placement = if (placed.width == fullWidth) estimate else place(placed.width)
            origin = placement.origin
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.place(placement.offset.x, placement.offset.y)
            }
        }
    }
}

/**
 * Presents [menu] at [anchor] through the ambient [GlassMenuHost] for as
 * long as this composable is composed: a controlled, state-driven menu
 * (the parent decides "show this now"). [anchor] is in window coordinates
 * (`boundsInWindow()`). [onDismissed] fires once when the menu finishes
 * closing — outside tap, pick, Back, or this composable leaving
 * composition.
 *
 * The menu is re-presented when `anchor`/`menu`/`cover` compare unequal,
 * and [Menu] equality includes the row lambdas, so callers must hoist the
 * `Menu` (`remember(keys) { … }`) — the trigger APIs (`Modifier.glassMenu`,
 * `rememberGlassMenuState`) evaluate the menu lazily and never have this
 * problem.
 */
@Composable
fun GlassMenuPopup(
    anchor: Rect,
    menu: Menu,
    onDismissed: () -> Unit,
    cover: Boolean = true,
) {
    val host = LocalGlassMenuHost.current
        ?: throw IllegalStateException("No GlassMenuHost: GlassMenuPopup presents through one")
    val dismissed by rememberUpdatedState(onDismissed)
    DisposableEffect(host, anchor, menu, cover) {
        val id = host.present(anchor, menu, cover) { dismissed() }
        onDispose { id?.let(host::dismiss) }
    }
}

// ---------------------------------------------------------------------------
// Triggers
// ---------------------------------------------------------------------------

/**
 * The convenience trigger modifier: records the element's window bounds,
 * installs the gesture, and calls [menu] **only when the trigger fires** —
 * never during composition (uncached content stays fresh, the lambda runs
 * once per open). The modifier adds **no visual** press state; the host
 * element owns its own press look.
 *
 * `enabled = false` does nothing (iOS `b.menu != nil`).
 *
 * When the element already has its own `clickable` (a ticket 08 glass
 * button), use [rememberGlassMenuState] and `state.open(anchor, menu())`
 * from its `onClick` instead.
 */
fun Modifier.glassMenu(
    trigger: MenuTrigger = MenuTrigger.Tap,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    menu: () -> Menu,
): Modifier = composed {
    val host = LocalGlassMenuHost.current
    val feedback = LocalFeedback.current
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var openedId by remember { mutableStateOf<Long?>(null) }
    val currentMenu by rememberUpdatedState(menu)
    val currentOnClick by rememberUpdatedState(onClick)

    fun openNow(): Boolean {
        val target = anchor ?: return false
        val menuHost = host ?: run {
            Log.w("GlassMenu", "No GlassMenuHost mounted; a menu trigger did nothing")
            return false
        }
        val id = menuHost.present(target, currentMenu()) ?: return false
        openedId = id
        return true
    }

    fun openLongPress() {
        val target = anchor ?: return
        val menuHost = host ?: run {
            Log.w("GlassMenu", "No GlassMenuHost mounted; a menu trigger did nothing")
            return
        }
        // The menu is built once per open — never during composition — and
        // the haptic fires only if it actually opens.
        val fresh = currentMenu()
        if (fresh.hasContent()) {
            feedback.haptic(Haptic.LongPress)
            openedId = menuHost.present(target, fresh)
        }
    }

    val modifier = this
        .onGloballyPositioned { anchor = it.boundsInWindow() }
        .pointerInput(trigger, enabled, host, feedback) {
            detectTapGestures(
                onTap = {
                    if (enabled) {
                        when (trigger) {
                            MenuTrigger.Tap -> openNow()
                            MenuTrigger.LongPress -> currentOnClick?.invoke()
                        }
                    }
                },
                onLongPress = {
                    if (enabled && trigger == MenuTrigger.LongPress) openLongPress()
                },
            )
        }
        .semantics {
            if (enabled) {
                when (trigger) {
                    MenuTrigger.Tap -> this.onClick(label = null) { openNow(); true }
                    MenuTrigger.LongPress ->
                        this.onLongClick(label = "Show menu") { openLongPress(); true }
                }
            }
        }
    // If the trigger leaves composition, the menu it opened closes (the
    // presentation table's "trigger leaves" rule; the anchor itself is
    // frozen at open, like iOS).
    DisposableEffect(host) {
        onDispose { openedId?.let { id -> host?.dismiss(id) } }
    }
    modifier
}

/**
 * Opens menus on the ambient host: `open` hands the anchor and menu to the
 * host (one menu at a time — opening a second closes the first);
 * [isClosing] is true from the moment a close starts until the fade ends.
 */
@Stable
class GlassMenuState internal constructor(private val host: GlassMenuHostState) {

    val isOpen: Boolean get() = host.isPresenting

    /** True from the moment a close starts until the fade ends. */
    val isClosing: Boolean get() = host.isClosing

    /** Presents [menu] at [anchor] (window px). True when it actually opened. */
    fun open(anchor: Rect, menu: Menu): Boolean = host.present(anchor, menu) != null

    fun close() = host.close()
}

/**
 * The [GlassMenuState] for the ambient [GlassMenuHost]. Throws when no host
 * is mounted — a caller that cannot open menus is a wiring bug, not a
 * silent no-op.
 */
@Composable
fun rememberGlassMenuState(): GlassMenuState {
    val host = LocalGlassMenuHost.current
        ?: throw IllegalStateException("No GlassMenuHost: wrap content in GlassMenuHost")
    return remember(host) { GlassMenuState(host) }
}

// ---------------------------------------------------------------------------
// Content: the stateful, window-less menu (the host and ticket 16 embed it)
// ---------------------------------------------------------------------------

/** True when the root level (with nothing resolved) yields any row, submenu or loading line. */
fun Menu.hasContent(): Boolean =
    flattenLevel(title, children, resolved = { null }, isRoot = true, backTitle = null)
        .hasRowLines()

/** One drilled level of the open menu. */
@Immutable
internal data class MenuLevel(
    val title: String?,
    val elements: List<MenuElement>,
    val isRoot: Boolean,
)

/** The a11y back label: parent level title, else menu title, else "menu". */
private fun backLabelFor(menu: Menu, levels: List<MenuLevel>): String? {
    val level = levels.last()
    if (level.isRoot) return null
    val parent = levels.getOrNull(levels.size - 2)
    return parent?.title?.takeIf { it.isNotBlank() }
        ?: menu.title?.takeIf { it.isNotBlank() }
        ?: "menu"
}

/**
 * The stateful, **window-less** menu (android-ui-parity #15): the level
 * stack, the deferred loads, the open spring and close fade, the drill-in
 * cross-fade, `BackHandler` and picks. [GlassMenuHost] embeds it; ticket 16
 * embeds it inside its own context-menu overlay with its own placement.
 *
 * It does no placement (the caller positions it) and installs no tap
 * catcher — but it **does** swallow every tap that no row takes (padding,
 * separators, disabled rows), so a tap on the panel never dismisses the
 * menu (UIKit ignores taps on disabled items; ticket 80's read-only menu
 * relies on it).
 *
 * Lifecycle: the open animation runs on first composition (scale
 * [GlassMenuTokens.OpenScale] → 1, alpha 0 → 1, from [origin]); when [open]
 * turns false the panel goes non-interactive at once and alpha fades to 0
 * over [GlassMenuTokens.CloseMs], then [onClosed] fires. A pick calls
 * [onDismissRequest] first and runs the action on the **next frame**, so an
 * alert the action opens appears while the menu fades. Under Reduce
 * Motion there is no scale, the fade is 150 ms, and drill-ins resize
 * instantly.
 */
@Composable
fun GlassMenuContent(
    menu: Menu,
    open: Boolean,
    origin: TransformOrigin,
    onDismissRequest: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(if (reduceMotion) 1f else GlassMenuTokens.OpenScale) }
    val alpha = remember { Animatable(0f) }
    val stack = remember {
        mutableStateListOf<MenuLevel>()
            .apply { add(MenuLevel(menu.title, menu.children, isRoot = true)) }
    }
    val resolved = remember { mutableStateMapOf<MenuElement.Deferred, List<MenuElement>>() }
    val inFlight = remember { mutableSetOf<MenuElement.Deferred>() }

    // ------------------------------------------------------- deferred loads

    /**
     * Launches every deferred reachable without drilling (inside inline
     * sections and inside already-resolved lists). `UNDISPATCHED`: a load
     * that never suspends completes before the first frame, so no
     * "Loading…" flash ever shows.
     */
    fun loadAround(elements: List<MenuElement>) {
        for (element in elements) {
            when (element) {
                is MenuItem -> Unit
                is MenuSection -> if (element.inline) loadAround(element.children)
                is MenuElement.Deferred -> {
                    if (element in inFlight) continue
                    val known = resolved[element]
                    if (known != null) {
                        loadAround(known)
                        continue
                    }
                    val cached = element.cache
                    if (element.cached && cached != null) {
                        resolved[element] = cached
                        loadAround(cached)
                        continue
                    }
                    inFlight += element
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val result = try {
                            element.load()
                        } catch (cancellation: CancellationException) {
                            throw cancellation // never cached, never resolved
                        } catch (failure: Throwable) {
                            Log.w("GlassMenu", "Deferred menu element load failed", failure)
                            emptyList()
                        }
                        inFlight -= element
                        if (element.cached) element.cache = result
                        resolved[element] = result
                        loadAround(result)
                        // A load that emptied the whole root level closes the menu.
                        if (stack.size == 1 &&
                            !flattenLevel(
                                menu.title, menu.children, resolved::get,
                                isRoot = true, backTitle = null,
                            ).hasRowLines()
                        ) {
                            onDismissRequest()
                        }
                    }
                }
            }
        }
    }

    // The root's loads start inside the first composition (UNDISPATCHED), so
    // a load that never suspends has resolved before the panel below reads
    // `resolved`: no frame ever holds its "Loading…" line (ticket #15). A
    // LaunchedEffect alone runs only after that first frame was composed.
    // Drilled levels load from the effect; the guards make repeats no-ops.
    remember { stack.first().elements.also { loadAround(it) } }
    LaunchedEffect(stack.size) { loadAround(stack.last().elements) }

    // --------------------------------------------------------- animation

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            // No scale; iOS cross-fades menus under Reduce Motion.
            alpha.animateTo(1f, tween(GlassMenuTokens.ReducedFadeMs, easing = Motion.EaseInOut))
        } else {
            launch { scale.animateTo(1f, GlassMenuTokens.openSpring) }
            alpha.animateTo(1f, GlassMenuTokens.openSpring)
        }
    }

    LaunchedEffect(open) {
        if (!open) {
            alpha.animateTo(0f, tween(GlassMenuTokens.CloseMs, easing = Motion.EaseInOut))
            onClosed()
        }
    }

    // --------------------------------------------------------- interaction

    fun pick(item: MenuItem) {
        if (!open || item.disabled || item.informational) return
        onDismissRequest() // 1. start the close (isClosing flips at once)
        // 2. Haptic: none. 3. the action on the next frame, after the close
        //    has started, so an alert it opens never flashes under the menu.
        scope.launch { withFrameNanos { }; item.action() }
    }

    fun push(section: MenuSection) {
        if (open) stack.add(MenuLevel(section.title, section.children, isRoot = false))
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismissRequest()
    }

    BackHandler(enabled = open) { pop() }

    // Accessibility: move focus to the first enabled row, but only while a
    // screen reader (touch exploration) is on — a programmatic requestFocus
    // without a focus target would otherwise fight the composer's field.
    val context = LocalContext.current
    val a11yFocus = remember(context) {
        runCatching {
            context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled
        }.getOrDefault(false) == true
    }
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (a11yFocus) firstRowFocus.requestFocus() }

    // --------------------------------------------------------- the panel

    val levels = stack.toList()
    val resize = if (reduceMotion) {
        Modifier
    } else {
        // The size spring is the same open spring, over IntSize.
        Modifier.animateContentSize(GlassMenuTokens.OpenSpring.spec())
    }
    val swallowTaps = remember {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                waitForUpOrCancellation()?.consume()
            }
        }
    }
    val panelModifier = resize.then(swallowTaps)
    val focusRequester = if (a11yFocus) firstRowFocus else null

    Box(
        modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value.coerceIn(0f, 1f) // the spring may overshoot
            transformOrigin = origin
        },
    ) {
        // Test-only progress key, isolated so its per-frame recomposition
        // never touches the panel content (the GlassSurface convention).
        MenuProgressSemantics(alpha)
        if (reduceMotion) {
            GlassMenuLevelPanel(
                level = levels.last(),
                resolved = resolved,
                backLabel = backLabelFor(menu, levels),
                onRow = ::pick,
                onSubmenu = ::push,
                onBack = ::pop,
                modifier = panelModifier,
                maxHeight = maxHeight,
                focusRequester = focusRequester,
            )
        } else {
            Crossfade(
                targetState = levels,
                animationSpec = tween(GlassMenuTokens.DrillFadeMs, easing = Motion.EaseInOut),
            ) { levelStack ->
                GlassMenuLevelPanel(
                    level = levelStack.last(),
                    resolved = resolved,
                    backLabel = backLabelFor(menu, levelStack),
                    onRow = ::pick,
                    onSubmenu = ::push,
                    onBack = ::pop,
                    modifier = panelModifier,
                    maxHeight = maxHeight,
                    focusRequester = focusRequester,
                )
            }
        }
    }
}

/** The test-only [GlassMenuProgressKey] on an invisible, empty child. */
@Composable
private fun MenuProgressSemantics(alpha: Animatable<Float, AnimationVector1D>) {
    val value = alpha.value
    Box(Modifier.semantics { this[GlassMenuProgressKey] = value })
}

/** Flattens one level and renders it through [GlassMenuPanel]. */
@Composable
private fun GlassMenuLevelPanel(
    level: MenuLevel,
    resolved: SnapshotStateMap<MenuElement.Deferred, List<MenuElement>>,
    backLabel: String?,
    onRow: (MenuItem) -> Unit,
    onSubmenu: (MenuSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    focusRequester: FocusRequester? = null,
) {
    val lines = flattenLevel(
        level.title,
        level.elements,
        resolved::get,
        level.isRoot,
        backTitle = level.title,
    )
    val withBackLabel = if (backLabel == null) {
        lines
    } else {
        lines.map { if (it is MenuLine.Back) it.copy(backTo = backLabel) else it }
    }
    GlassMenuPanel(
        lines = withBackLabel,
        onRow = onRow,
        onSubmenu = onSubmenu,
        onBack = onBack,
        modifier = modifier,
        maxHeight = maxHeight,
        focusRequester = focusRequester,
    )
}

// ---------------------------------------------------------------------------
// The panel: stateless lines → glass
// ---------------------------------------------------------------------------

/**
 * The stateless menu panel (no animation, no state): ticket 07's
 * [GlassSurface] at radius 22 under the flattened [lines]. Previews,
 * screenshots and the gallery render it directly (with no ambient
 * `LocalHazeState` it draws the fallback fill); [GlassMenuContent] and
 * ticket 16 embed it.
 *
 * Width: `IntrinsicSize.Max` — as wide as the widest single-line row,
 * clamped to [GlassMenuTokens.MinWidth]…[GlassMenuTokens.MaxWidth] (and
 * the incoming constraints); longer titles wrap to two lines. Height: the
 * content, capped by [maxHeight] / the incoming constraints, scrolling
 * past that.
 *
 * @param focusRequester attached (with `focusable()`) to the first enabled
 *   row — accessibility focus on open. `null` normally.
 */
@Composable
fun GlassMenuPanel(
    lines: List<MenuLine>,
    onRow: (MenuItem) -> Unit,
    onSubmenu: (MenuSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    focusRequester: FocusRequester? = null,
) {
    val palette = RobocoTheme.palette
    val slotReserved = reservesLeadingSlot(lines)
    val paneTitleText = (lines.firstNotNullOfOrNull { it as? MenuLine.Title })?.text ?: "Menu"
    val focusIndex = focusRequester?.let {
        lines.indexOfFirst { line ->
            (line is MenuLine.Row && !line.item.disabled && !line.item.informational) || line is MenuLine.Back
        }.takeIf { index -> index >= 0 }
    } ?: -1
    val shape = rememberSmoothRoundedShape(GlassMenuTokens.Radius)

    GlassSurface(
        modifier = modifier
            .testTag("glass-menu")
            .semantics {
                paneTitle = paneTitleText
                isTraversalGroup = true
            },
        shape = shape,
        interactive = false,
        tint = null,
        visible = true,
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = GlassMenuTokens.MinWidth, max = GlassMenuTokens.MaxWidth)
                .heightIn(max = maxHeight)
                .contentHeightWhenUnbounded()
                .verticalScroll(rememberScrollState())
                .padding(vertical = GlassMenuTokens.PadV),
        ) {
            lines.forEachIndexed { index, line ->
                when (line) {
                    is MenuLine.Title -> MenuTitleLine(line, slotReserved)
                    is MenuLine.Back -> MenuBackLine(
                        line = line,
                        onBack = onBack,
                        focusModifier = focusModifierAt(index, focusIndex, focusRequester),
                    )
                    is MenuLine.Header -> MenuHeaderLine(line, slotReserved)
                    MenuLine.Separator -> MenuSeparatorLine(palette.isDark)
                    is MenuLine.Row -> MenuRowLine(
                        line = line,
                        slotReserved = slotReserved,
                        onRow = onRow,
                        focusModifier = focusModifierAt(index, focusIndex, focusRequester),
                    )
                    is MenuLine.Submenu -> MenuSubmenuLine(
                        line = line,
                        slotReserved = slotReserved,
                        onSubmenu = onSubmenu,
                        focusModifier = focusModifierAt(index, focusIndex, focusRequester),
                    )
                    is MenuLine.Loading -> MenuLoadingLine(slotReserved)
                }
            }
        }
    }
}

/** The first-enabled-row focus modifier, empty when this line is not the target. */
private fun focusModifierAt(
    index: Int,
    focusIndex: Int,
    focusRequester: FocusRequester?,
): Modifier =
    if (focusRequester != null && index == focusIndex) {
        Modifier.focusRequester(focusRequester).focusable()
    } else {
        Modifier
    }

/**
 * The panel's "capped by the incoming constraints" in a parent with no
 * height bound: the content's own height, nothing to scroll. Without it,
 * the `verticalScroll` below would be measured with an infinite max height
 * and throw. That happens to a static panel inside a scrolling column (the
 * gallery specimens). [GlassMenuHost] always passes a bounded height
 * (`estimate.maxHeight`), which goes through unchanged, so presented menus
 * size and scroll exactly as before.
 */
private fun Modifier.contentHeightWhenUnbounded(): Modifier = layout { measurable, constraints ->
    val bounded = if (constraints.hasBoundedHeight) {
        constraints
    } else {
        val content = measurable.maxIntrinsicHeight(constraints.maxWidth)
        constraints.copy(maxHeight = content.coerceAtLeast(constraints.minHeight))
    }
    val placeable = measurable.measure(bounded)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The level's text column: 61 dp when the slot is reserved, else 20 dp. */
private fun textColumnStart(slotReserved: Boolean): Dp =
    if (slotReserved) GlassMenuTokens.TextStart else GlassMenuTokens.TextStartNoSlot

/**
 * The `Menu.title` line: secondary 13, two lines, from the text column to
 * −20. A title with a line break (the session ⋯ menu's title over where it
 * runs, wave-10 33) keeps each line to one, ellipsized, so a long first
 * line never pushes the second out.
 */
@Composable
private fun MenuTitleLine(line: MenuLine.Title, slotReserved: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val style = uiStyle(Face.Sans, typeScale.scaled(13f), palette.secondary)
    val modifier = Modifier
        .fillMaxWidth()
        .padding(
            start = textColumnStart(slotReserved),
            end = GlassMenuTokens.PadEnd,
            top = GlassMenuTokens.TitlePadTop,
            bottom = GlassMenuTokens.TitlePadBottom,
        )
    val lines = line.text.split('\n')
    if (lines.size == 1) {
        BasicText(line.text, modifier, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    Column(modifier) {
        for (text in lines.take(2)) {
            BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The drill-in back row: Medium 16, the backward chevron in the leading slot. */
@Composable
private fun MenuBackLine(
    line: MenuLine.Back,
    onBack: () -> Unit,
    focusModifier: Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        focusModifier
            .fillMaxWidth()
            .heightIn(min = GlassMenuTokens.RowHeight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onBack,
            )
            .pressHighlight(palette, pressed)
            .testTag("glass-menu-back")
            .semantics { contentDescription = "Back to ${line.backTo ?: "menu"}" },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = GlassMenuTokens.SlotOffsetX)
                .size(GlassMenuTokens.SlotBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RobocoIcons.ChevronBackward,
                palette.text,
                sizePt = GlassMenuTokens.IconPt,
                weight = SymbolWeight.Semibold,
            )
        }
        BasicText(
            line.text,
            Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = GlassMenuTokens.TextStart,
                    end = GlassMenuTokens.PadEnd,
                    top = GlassMenuTokens.RowPadV,
                    bottom = GlassMenuTokens.RowPadV,
                ),
            style = uiStyle(Face.SansMedium, typeScale.scaled(16f), palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An inline section header: secondary 13, the icon centred in the leading slot. */
@Composable
private fun MenuHeaderLine(line: MenuLine.Header, slotReserved: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassMenuTokens.HeaderHeight)
            .padding(
                top = GlassMenuTokens.HeaderPadTop,
                bottom = GlassMenuTokens.HeaderPadBottom,
            ),
    ) {
        if (slotReserved) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = GlassMenuTokens.SlotOffsetX)
                    .size(GlassMenuTokens.SlotBox),
                contentAlignment = Alignment.Center,
            ) {
                LeadingContent(line.icon, checked = false, ink = palette.secondary)
            }
        }
        BasicText(
            line.text ?: "",
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = GlassMenuTokens.TextStart, end = GlassMenuTokens.PadEnd),
            style = uiStyle(Face.Sans, typeScale.scaled(13f), palette.secondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The 8 dp separator block: a 1 px hairline centred, inset 20 from both edges. */
@Composable
private fun MenuSeparatorLine(dark: Boolean) {
    // Palette.hairline disappears on the glass panel; these alphas are the
    // ticket's tuning values (pr621's divider).
    val color = if (dark) rgba(0xE8E8EA, 0.14) else rgba(0x27272C, 0.12)
    Box(
        Modifier
            .fillMaxWidth()
            .height(GlassMenuTokens.SeparatorBlock)
            .drawBehind {
                val inset = GlassMenuTokens.SeparatorInset.toPx()
                val y = size.height / 2f
                drawLine(
                    color,
                    Offset(inset, y),
                    Offset(size.width - inset, y),
                    strokeWidth = 1f, // one physical pixel
                )
            },
    )
}

/** One action row. */
@Composable
private fun MenuRowLine(
    line: MenuLine.Row,
    slotReserved: Boolean,
    onRow: (MenuItem) -> Unit,
    focusModifier: Modifier,
) {
    val item = line.item
    MenuRowLayout(
        title = item.title,
        subtitle = item.subtitle,
        icon = item.icon,
        checked = item.checked,
        destructive = item.destructive,
        disabled = item.disabled,
        trailingChevron = false,
        slotReserved = slotReserved,
        onClick = { onRow(item) },
        testTag = item.testTag ?: "glass-menu-item",
        focusModifier = focusModifier,
        informational = item.informational,
    )
}

/** One drill-in submenu row: the section title, its icon, a trailing chevron. */
@Composable
private fun MenuSubmenuLine(
    line: MenuLine.Submenu,
    slotReserved: Boolean,
    onSubmenu: (MenuSection) -> Unit,
    focusModifier: Modifier,
) {
    val section = line.section
    MenuRowLayout(
        title = section.title ?: "",
        subtitle = null,
        icon = section.icon,
        checked = false,
        destructive = false,
        disabled = false,
        trailingChevron = true,
        slotReserved = slotReserved,
        onClick = { onSubmenu(section) },
        testTag = "glass-menu-item",
        a11yDescription = "Submenu",
        focusModifier = focusModifier,
    )
}

/** The "Loading…" placeholder row: secondary 16, a polite live region, not clickable. */
@Composable
private fun MenuLoadingLine(slotReserved: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GlassMenuTokens.RowHeight)
            .testTag("glass-menu-loading")
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            "Loading\u2026",
            Modifier.padding(
                start = textColumnStart(slotReserved),
                end = GlassMenuTokens.PadEnd,
            ),
            style = uiStyle(Face.Sans, typeScale.scaled(16f), palette.secondary),
            maxLines = 1,
        )
    }
}

/**
 * The shared row anatomy: the 20×20 leading slot centred at 36 dp, the
 * text block centred in the 42 dp min height (growing through the 10.4 dp
 * padding once it wraps), and the trailing `chevron.right` on submenu
 * rows. The press highlight is a `controlFill` rounded rect drawn while a
 * finger is down — no ripple, `indication = null`.
 */
@Composable
private fun MenuRowLayout(
    title: String,
    subtitle: String?,
    icon: MenuIcon?,
    checked: Boolean,
    destructive: Boolean,
    disabled: Boolean,
    trailingChevron: Boolean,
    slotReserved: Boolean,
    onClick: () -> Unit,
    testTag: String,
    a11yDescription: String? = null,
    focusModifier: Modifier = Modifier,
    informational: Boolean = false,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val titleInk = when {
        disabled -> palette.secondary // the capture: grey, also on destructive rows
        destructive -> palette.danger
        else -> palette.text
    }
    val iconInk = when {
        disabled -> palette.text // the capture: disabled icons keep full ink
        destructive -> palette.danger
        else -> palette.text
    }
    val subtitleInk = if (disabled) palette.secondary.copy(alpha = 0.6f) else palette.secondary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        focusModifier
            .fillMaxWidth()
            .heightIn(min = GlassMenuTokens.RowHeight)
            .then(
                if (informational) {
                    // A read-only line: no tap target (the panel swallows
                    // the tap, so the menu stays open), one merged node.
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = !disabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .pressHighlight(palette, pressed)
                },
            )
            .testTag(testTag)
            .semantics {
                if (!informational) selected = checked
                if (disabled) this@semantics.disabled()
                a11yDescription?.let { stateDescription = it }
            },
    ) {
        if (slotReserved) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = GlassMenuTokens.SlotOffsetX)
                    .size(GlassMenuTokens.SlotBox),
                contentAlignment = Alignment.Center,
            ) {
                LeadingContent(icon, checked, iconInk)
            }
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = textColumnStart(slotReserved),
                    end = if (trailingChevron) {
                        GlassMenuTokens.PadEnd +
                            GlassMenuTokens.SubmenuChevronGap +
                            GlassMenuTokens.SubmenuChevronBox
                    } else {
                        GlassMenuTokens.PadEnd
                    },
                    top = GlassMenuTokens.RowPadV,
                    bottom = GlassMenuTokens.RowPadV,
                ),
        ) {
            BasicText(
                title,
                style = uiStyle(Face.Sans, typeScale.scaled(16f), titleInk),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(GlassMenuTokens.SubtitleGap))
                BasicText(
                    subtitle,
                    style = uiStyle(Face.Sans, typeScale.scaled(13f), subtitleInk),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingChevron) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = GlassMenuTokens.PadEnd),
            ) {
                Icon(
                    RobocoIcons.ChevronRight,
                    palette.secondary,
                    sizePt = 13f,
                    weight = SymbolWeight.Semibold,
                )
            }
        }
    }
}

/**
 * The leading glyph: a checked row shows the checkmark **instead of** its
 * icon (one slot, every title on the text column); brand marks keep the
 * Claude orange, custom glyphs receive the ink and decide themselves.
 */
@Composable
private fun LeadingContent(icon: MenuIcon?, checked: Boolean, ink: Color) {
    when {
        checked -> Icon(RobocoIcons.Checkmark, ink, sizePt = GlassMenuTokens.IconPt)
        icon == null -> Unit
        icon is MenuIcon.Symbol -> Icon(icon.icon, ink, sizePt = GlassMenuTokens.IconPt)
        icon is MenuIcon.Brand -> HarnessMark(icon.harness, GlassMenuTokens.BrandSide, ink)
        icon is MenuIcon.Custom -> icon.content(ink)
    }
}

/**
 * The press highlight: [GlassMenuTokens.HighlightRadius] continuous
 * corners, [GlassMenuTokens.HighlightInset] from the row's edges, full row
 * height, `palette.controlFill` — while [pressed], drawn behind the
 * content.
 */
private fun Modifier.pressHighlight(palette: Palette, pressed: Boolean): Modifier =
    drawBehind {
        if (!pressed) return@drawBehind
        val inset = GlassMenuTokens.HighlightInset.toPx()
        val outline = HighlightShape.createOutline(
            Size(size.width - 2 * inset, size.height),
            layoutDirection,
            this,
        )
        translate(inset, 0f) { drawOutline(outline, palette.controlFill) }
    }

/** One shared highlight shape instance (ticket 01's outline cache is internal). */
private val HighlightShape = SmoothRoundedShape(GlassMenuTokens.HighlightRadius)
