package sh.roboco.android.shell

import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.MainThread
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.sessions.LocalSessionListOpener
import sh.roboco.android.sessions.SessionListOpener
import sh.roboco.android.settings.SettingsScreen

/**
 * The tablet Settings form-sheet dialog (android-ui-parity #104): the port
 * of `SplitRootController.showSettings`'s expanded branch
 * (`ios:Shell/SplitRootController.swift:125-133`) — a centred, opaque,
 * rounded card over a dimmed split, hosting its own nav stack with the
 * large-title "Settings" root (103) and a trailing **Done** circle that
 * dismisses the whole thing.
 *
 * **How it is hosted:** an entry of ticket 31's `SheetHost` through its
 * `SheetStyle.Custom` extension point — in the app's window, never an
 * Android `Dialog`/`Popup` window, so Haze keeps sampling, the Pair page
 * (72) stacks on it through [LocalEnclosingSheet], and alerts, action
 * sheets, menus and toasts draw above it; a root swap dismisses it
 * instantly through 35's `sheets.dismissAll(animated = false)`. The card
 * never registers via `RegisterRootPresentation`.
 *
 * - **Fresh every time** (KEEP): `present()` builds a new stack — nothing
 *   survives a reopen, as a new `UINavigationController` per `showSettings`.
 * - **Done on the root only** (KEEP): `UIBarButtonItem(systemItem: .done)`
 *   is set on `topViewController` at present time; pushed screens show the
 *   system back circle (25) and no Done.
 * - **Outside taps ignored** (KEEP): 31's scrim consumes them; the split
 *   underneath never receives them.
 * - **The "already presenting" no-op** (KEEP): `present()` while anything
 *   is presented (hidden included) logs and returns `false`.
 * - **Motion** (ADAPT): the scope fixes scale 0.94 → 1 plus fade with
 *   `SettingsDialogDefaults.Spring` (ζ 1.0, T 0.4 s) instead of iOS's
 *   default `.coverVertical` slide (see the ticket's Comments).
 * - **Dismissing** (ADAPT): Android predictive back scrubs the card at the
 *   root screen (`p = 1 − 0.25·b`), pops inside first when a screen is
 *   pushed; no iPadOS drag-down (DROP — no physics specified).
 * - **Sessions from inside** (ADAPT): routed to `LocalAppRouter`'s
 *   `openSession` (the detail column), never pushed inside the card — iOS
 *   pushes only because `splitViewController` is nil there
 *   (`SessionListController.swift:203-214`).
 */

/** The card's tunables; the starred values are UIKit system constants (see the ticket's Comments). */
object SettingsDialogDefaults {

    /** The host entry's key — one Settings card at a time. */
    const val Key = "settings"

    /** The card's maximum width (UIKit's default form-sheet width, starred). */
    val MaxWidth = 540.dp

    /** The card's maximum height (UIKit's default form-sheet height, starred). */
    val MaxHeight = 620.dp

    /** The window margin the card clamps to: 24 dp per side (starred). */
    val WindowMargin = 48.dp

    /** The card's corner radius — the system sheet corner, as 31's page sheets. */
    val CornerRadius = 38.dp

    /** The scrim's dim black over the split (the page sheets use 0.25 / 0.45). */
    const val ScrimAlpha = 0.2f

    /** The present motion's starting scale (the scope's fixed form-sheet scale-in). */
    const val PresentedScaleFrom = 0.94f

    /**
     * The form-sheet spring: critically damped, 0.4 s — settle-matched
     * stiffness (9.2334/0.4)² = 532.85 (FOUNDATION-D1), progress
     * `1 − (1 + ωt)·e^(−ωt)` with ω = 23.0835. Not an iOS
     * `usingSpringWithDamping` call site, so it deliberately stays out of
     * ticket 04's `Motion.allSprings` (its test counts exactly 9).
     */
    val Spring = IosSpring("settingsFormSheet", 0.40, 1.0f)

    /** Predictive back moves this fraction of `H` at gesture progress 1. */
    const val PredictiveBackTravel = 0.25f
}

/**
 * The pure half of the dialog: the card size clamp, the scale/alpha
 * mapping and the predictive-back offset. No Compose state, so
 * `SettingsDialogMathTest` runs on the plain JVM.
 */
internal object SettingsDialogMath {

    /**
     * The card size in dp from the available (inset) window size in dp:
     * `min(540, w − 48) × min(620, h − 48)`, floored at 0 (the IME is
     * never subtracted; a 0-size card composes but draws nothing).
     * 1280 × 800 → 540 × 620 at (370, 90); 560 × 600 → 512 × 552 at (24, 24).
     */
    fun cardSize(availW: Float, availH: Float): Pair<Float, Float> =
        minOf(SettingsDialogDefaults.MaxWidth.value, availW - SettingsDialogDefaults.WindowMargin.value)
            .coerceAtLeast(0f) to
            minOf(SettingsDialogDefaults.MaxHeight.value, availH - SettingsDialogDefaults.WindowMargin.value)
                .coerceAtLeast(0f)

    /** The card's layer scale at progress `p` (1 under reduce motion — fades only). */
    fun scale(p: Float, reduceMotion: Boolean): Float =
        if (reduceMotion) {
            1f
        } else {
            val from = SettingsDialogDefaults.PresentedScaleFrom
            from + (1f - from) * p.coerceIn(0f, 1f)
        }

    /** The card's alpha at progress `p` (0 while hidden for handoff — progress already is). */
    fun alpha(p: Float): Float = p.coerceIn(0f, 1f)

    /** Predictive back `b ∈ [0, 1]` scrubs the offset to `0.25·H·b`. */
    fun backOffset(gestureProgress: Float, heightPx: Float): Float =
        SettingsDialogDefaults.PredictiveBackTravel * heightPx * gestureProgress.coerceIn(0f, 1f)
}

/**
 * The card's own root route: [SettingsScreen] with the Done circle in the
 * nav row's trailing slot. Pushed routes are the shared ones (engine
 * detail 140, Licenses 101, Archived 45, …) through
 * [rememberSettingsDialogPresenter]'s `routeScreen`.
 */
internal data object SettingsDialogRootRoute : NavRoute {
    override val paneTitle: String get() = "Settings"
}

/**
 * The [PageSheetScope] of the sheet the caller is composed in — 31's
 * [LocalPageSheetScope] under the name tickets 72 (`openPairPage(over)`)
 * and 140 ("Pair again") use. A read-only getter alias, never a second
 * local: the host provides the value around every entry's content, so the
 * two names can never disagree inside the card.
 */
val LocalEnclosingSheet: CompositionLocal<PageSheetScope?> get() = LocalPageSheetScope

/**
 * The `.formSheet` entry style: a centred, opaque, rounded card that does
 * **not** recede the split (iPad form sheets dim, they never shrink), dims
 * it at [SettingsDialogDefaults.ScrimAlpha] through the host's scrim, and
 * animates its own `entry.offset` (px, `H` = the card's height) with the
 * form-sheet spring or the 150 ms reduce-motion fade.
 */
internal object SettingsFormSheetStyle : SheetStyle.Custom {

    override val recedesPresenter: Boolean get() = false

    /** The 0.2 dim (31's page-sheet scrims are 0.25 light / 0.45 dark). */
    override val scrimAlpha: Float get() = SettingsDialogDefaults.ScrimAlpha

    /**
     * The motion of this entry's present and every host-driven dismiss:
     * the form-sheet spring, or 24's reduced-motion cross-fade.
     */
    override fun motionSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(NavMotion.ReducedFadeMs, easing = Motion.EaseInOut)
        else SettingsDialogDefaults.Spring.spec()

    /**
     * The card's frame: insets the window (system bars ∪ cutout; the IME
     * is never subtracted), centres the card, animates `entry.offset`
     * itself (the frame is the present animation's only launcher — 31's
     * rule), and hosts [content] clipped to the rounded card. `progress`
     * is read inside the layer and the offset lambdas only, so a present,
     * scrub or dismiss frame redraws, never recomposes.
     */
    @Composable
    override fun Frame(entry: SheetEntry, content: @Composable () -> Unit) {
        val host = LocalSheetHost.current!!
        val reduce = LocalReduceMotion.current
        val palette = RobocoTheme.palette
        val isTop = host.entries.lastOrNull() === entry
        val cardShape = remember { SmoothRoundedShape(SettingsDialogDefaults.CornerRadius) }
        val hostScope = rememberCoroutineScope()

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) {
            val (w, h) = SettingsDialogMath.cardSize(maxWidth.value, maxHeight.value)

            // The present animation (the host never launches it): wait for
            // the first measure so `H` exists, snap the offset to it, then
            // spring to 0. A settled entry (presentSettledForPreview)
            // starts at 0 and never animates. A resize while shown leaves
            // a settled offset at 0 — the new H only re-scales p.
            LaunchedEffect(entry) {
                snapshotFlow { entry.heightPx }.first { it > 0f }
                if (entry.offset.value.isNaN()) {
                    entry.offset.snapTo(entry.heightPx)
                    entry.offset.animateTo(0f, motionSpec(reduce))
                }
            }

            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(w.dp, h.dp)
                    .onSizeChanged { entry.heightPx = it.height.toFloat() }
                    .graphicsLayer {
                        val p = entry.progress
                        alpha = if (entry.hiddenForHandoff) 0f else SettingsDialogMath.alpha(p)
                        val s = SettingsDialogMath.scale(p, reduce)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin.Center
                        clip = true
                        shape = cardShape
                    }
                    .background(palette.background)
                    .semantics {
                        // The VoiceOver escape gesture on a modal.
                        paneTitle = "Settings"
                        isTraversalGroup = true
                        customActions = listOf(
                            CustomAccessibilityAction(label = "Dismiss") {
                                host.dismissEntryAndAbove(host.entries.indexOf(entry), animated = true)
                                true
                            },
                        )
                    }
                    .testTag("settings-dialog"),
            ) {
                // (a) The card's own back handler — composed BEFORE the
                // content: OnBackPressedDispatcher priority is composition
                // order, so 24's pop handler inside the content and a
                // stacked Pair page (31) win while the card stays up. At
                // the root there is no inner handler: back dismisses the
                // card (commit animates from the scrubbed offset; on
                // 3-button navigation the flow completes with no progress
                // events and back is a plain animated dismiss).
                PredictiveBackHandler(enabled = isTop && !entry.dismissing && !entry.hiddenForHandoff) { events ->
                    try {
                        events.collect { event ->
                            entry.offset.snapTo(SettingsDialogMath.backOffset(event.progress, entry.heightPx))
                        }
                        host.dismissEntryAndAbove(host.entries.indexOf(entry), animated = true)
                    } catch (e: CancellationException) {
                        // Gesture abandoned: spring back to rest. Never in
                        // this handler's own coroutine — the system
                        // cancels it (ticket 24's rule); the frame's
                        // composition scope runs the animation.
                        if (!entry.dismissing) {
                            hostScope.launch { entry.offset.animateTo(0f, motionSpec(reduce)) }
                        }
                        throw e
                    }
                }

                // (b) The card's page: the dialog content, its own stack.
                content()

                // (c) The touch blocker while dismissing or hidden for
                // handoff: the card ignores all input until it is gone
                // (UIKit disables interaction on a presented hierarchy
                // during its dismissal).
                if (entry.dismissing || entry.hiddenForHandoff) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
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
        }
    }
}

/**
 * The presenter (android-ui-parity #104): what ticket 106's router calls
 * from `showSettings()` when the split is not collapsed. Held at the split
 * root via [rememberSettingsDialogPresenter], outside any `NavStackHost`.
 */
@Stable
class SettingsDialogPresenter internal constructor(
    private val sheetHost: SheetHostState,
    private val locals: () -> CompositionLocalContext,
    private val root: @Composable (done: @Composable RowScope.() -> Unit) -> Unit,
    private val routeScreen: @Composable (NavEntry) -> Unit,
    private val beforePresent: () -> Unit,
) {

    /** True while the card is one of the host's entries (hidden ones included). */
    val isPresented: Boolean
        get() = sheetHost.entries.any { it.key == SettingsDialogDefaults.Key }

    /**
     * `SplitRootController.showSettings`, expanded branch
     * (`SplitRootController.swift:125-133`): presents a **fresh** card — a
     * new nav stack every time, nothing survives a reopen. `false` (Log.w
     * "already presenting") while anything is presented in the host,
     * hidden entries included — UIKit's "already presenting" no-op. Puts
     * the presenting first responder's keyboard away first (the sidebar
     * search field). Main thread only (31 checks the looper).
     */
    @MainThread
    fun present(): Boolean {
        if (sheetHost.entries.isNotEmpty()) {
            Log.w(TAG, "already presenting")
            return false
        }
        beforePresent()
        return sheetHost.present(key = SettingsDialogDefaults.Key, style = SettingsFormSheetStyle) {
            SettingsDialogContent(scope = this, locals = locals, root = root, routeScreen = routeScreen)
        }
    }

    /**
     * The router's dismissal: `SheetHostState.dismiss` covers the card and
     * everything stacked on it (animated by default; `false` = removed in
     * the same frame — the root-swap / open-error path, which 35 and 106
     * also reach through the host directly).
     */
    @MainThread
    fun dismiss(animated: Boolean = true) {
        if (isPresented) sheetHost.dismiss(animated)
    }

    private companion object {
        const val TAG = "SettingsDialog"
    }
}

/**
 * Remembers the [SettingsDialogPresenter] at the split root (33's
 * SplitRoot / 106's router wiring), **outside any `NavStackHost`** — the
 * card must not inherit a stale `LocalNavStack`/`LocalNavEntry`; its own
 * `NavStackHost` provides fresh ones.
 *
 * [locals] is captured here because `SheetHost` composes its entries
 * outside the shells, so app-level locals provided inside them
 * ([LocalAppRouter] (32), 41's session route factory, 72's
 * `LocalPairPage`) would resolve to their defaults in the card. The
 * capture is re-read at content composition time, so a router attached
 * after the shell's first frame (36) is still the one the card sees. If
 * 35/106 later provide those locals above the `SheetHost`, the capture is
 * harmless.
 *
 * @param root the card's root screen; 103's [SettingsScreen] with the
 *   Done circle in the trailing slot. Its own `topInset` default is passed
 *   explicitly: 103's screen omits `topInset` when unspecified so this
 *   25 default (status bars) would apply inside the card too — 0 pins it.
 * @param routeScreen the pushed-route dispatcher the phone Settings tab
 *   uses (26's `ShellRouteContent`); one table serves both hosts.
 */
@Composable
fun rememberSettingsDialogPresenter(
    root: @Composable (done: @Composable RowScope.() -> Unit) -> Unit = { done ->
        SettingsScreen(topInset = 0.dp, trailing = done)
    },
    routeScreen: @Composable (NavEntry) -> Unit = { entry -> ShellRouteContent(entry.route) },
): SettingsDialogPresenter {
    val host = LocalSheetHost.current
        ?: error("SettingsDialog needs a SheetHost (android-ui-parity #31)")
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val rootLatest by rememberUpdatedState(root)
    val routeScreenLatest by rememberUpdatedState(routeScreen)
    val focus by rememberUpdatedState(LocalFocusManager.current)
    val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    return remember(host) {
        SettingsDialogPresenter(
            sheetHost = host,
            locals = { locals },
            root = { done -> rootLatest(done) },
            routeScreen = { entry -> routeScreenLatest(entry) },
            // iOS resigns the presenting first responder for a modal
            // presentation: the sidebar search keyboard goes away before
            // the card shows.
            beforePresent = {
                focus.clearFocus(force = true)
                keyboard?.hide()
            },
        )
    }
}

/**
 * The card's page: its own nav stack (24) with the Done-carrying root (or
 * the shared pushed screens), the locals the split root captured, and the
 * card-local insets: 25's chrome insets nothing
 * ([LocalLargeTitleHostInsets] = zero — the card sits inside the safe
 * area), the bottom chrome is off (30's `None` — the card's bottom is far
 * from the navigation bar), the enclosing sheet scope is re-provided (31
 * already provides it; harmless), and a session tap routes to the app
 * router instead of this stack.
 *
 * `internal` (not private) so the debug gallery's previews can build the
 * same card over the gallery's fake root through
 * `presentSettledForPreview`.
 */
@Composable
internal fun SettingsDialogContent(
    scope: PageSheetScope,
    locals: () -> CompositionLocalContext,
    root: @Composable (done: @Composable RowScope.() -> Unit) -> Unit,
    routeScreen: @Composable (NavEntry) -> Unit,
) {
    CompositionLocalProvider(locals()) {
        val router = LocalAppRouter.current
        CompositionLocalProvider(
            LocalLargeTitleHostInsets provides WindowInsets(0, 0, 0, 0),
            LocalBottomChrome provides BottomChromeInsets.None,
            LocalPageSheetScope provides scope,
            // iOS pushes the session inside the form sheet only because
            // splitViewController is nil there; Roboco routes it to the
            // detail column (106 dismisses the card) — the adaptation,
            // also keeping "one live view per session" (33/106/108).
            LocalSessionListOpener provides SessionListOpener { id -> router.openSession(id) },
        ) {
            // A fresh stack per presentation: no state survives a reopen
            // (SplitRootController builds a new nav controller each time).
            val stack = remember { NavStack(SettingsDialogRootRoute) }
            // The whole card goes away → close every entry, no lifecycle
            // callbacks (iOS deallocates the controller and every screen).
            DisposableEffect(stack) { onDispose { stack.closeAll() } }
            NavStackHost(stack = stack, active = true, modifier = Modifier.fillMaxSize()) { entry ->
                if (entry.route === SettingsDialogRootRoute) {
                    root { DoneButton(scope) }
                } else {
                    routeScreen(entry)
                }
            }
        }
    }
}

/**
 * The `.done` bar item (`SplitRootController.swift:128-131`, 17's `.done`
 * decision): a 44 × 44 [GlassCircleButton] with a 17 pt Medium ✓ — a
 * tint-filled circle. This stack's bar tint is `Palette.text`
 * (`MainTabController.swift:132`), overriding the window's accent; the ✓
 * ink is the contrast `Palette.background`, as 08's `ProminentGlassButton`.
 * Its press is 08's opaque-variant `glassPress`. Only on the root entry.
 */
@Composable
private fun RowScope.DoneButton(scope: PageSheetScope) {
    val palette = RobocoTheme.palette
    GlassCircleButton(
        icon = RobocoIcons.Checkmark,
        contentDescription = "Done",
        onClick = { scope.dismiss(animated = true) },
        modifier = Modifier.testTag("settings-done"),
        filled = palette.text,
        ink = palette.background,
    )
}
