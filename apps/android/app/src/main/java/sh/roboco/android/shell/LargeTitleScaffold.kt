package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.PullToRefreshIndicator
import sh.roboco.android.design.PullToRefreshState
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.ScrollEdgeEffect
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults
import sh.roboco.android.design.glass.contentUnderTopEdge
import sh.roboco.android.design.uiStyle

/**
 * How a screen's title is shown (android-ui-parity #25), the port of UIKit's
 * `largeTitleDisplayMode`:
 *
 * - [Always] — the large title (52 dp row, the first item of the scroll
 *   content) collapses into the centred inline title as the list scrolls:
 *   Sessions, folders, Search, Settings, Licenses, engine detail, the tablet
 *   sidebar;
 * - [InlineOnly] — no large-title item, the inline title always visible:
 *   where `prefersLargeTitles` is off (the tablet detail column, via
 *   [LocalPrefersLargeTitles]);
 * - [None] — no nav row and no inline title: a screen that draws its own
 *   header and edge (none today; the Session screen 52 does not use the
 *   scaffold at all).
 */
enum class TitleMode { Always, InlineOnly, None }

/**
 * The scaffold's tunables (android-ui-parity #25). Values are UIKit's
 * navigation-bar geometry: a 44 pt inline row, a 52 dp large-title area
 * (96 − 44), 16 dp layout margins on phones. The two `*` values of the
 * ticket — the large-title bottom inset and the snap spring — are
 * approximations of system internals, frozen once compared against an iOS 26
 * simulator capture; see [LargeBottomInset] and [SnapStiffness].
 */
object LargeTitleDefaults {

    /** The nav row height (UIKit bar, `1-off.png`). */
    val NavRowHeight = 44.dp

    /** The large-title row height (UIKit large-title area, 96 − 44). */
    val LargeRowHeight = 52.dp

    /** The large title's bottom inset inside its row. `*` tuned vs an iOS 26 simulator, then frozen. */
    val LargeBottomInset = 8.dp

    /** The horizontal layout margin of the bar items and the large title. */
    val SideMargin = 16.dp

    /** The inline title's minimum gap to the bar items. */
    val TitleItemGap = 8.dp

    /** The inline title's fade window, centred on the baseline crossing. */
    val InlineFadeDistance = 12.dp

    /** The large title: Geist SemiBold 30, `fixed` (no Dynamic Type). */
    const val LargeTitlePt = 30f

    /** The inline title: Geist SemiBold 17, `fixed` (no Dynamic Type). */
    const val InlineTitlePt = 17f

    /**
     * The end-of-scroll snap spring's stiffness (≈ 400), carried as
     * [Motion.LargeTitleSnap] — a 0.46 s critically damped spring, so no
     * stiffness literal is ever pasted (FOUNDATION-D1). UIKit's snap timing
     * is internal; the value is tunable against a real capture (the ticket's
     * `*`).
     */
    val SnapStiffness: Float get() = Motion.LargeTitleSnap.stiffness
}

/**
 * iOS `navigationBar.prefersLargeTitles` (ticket 25): the tab stacks keep it
 * `true` (set once in `MainTabController.nav`), the tablet detail column
 * turns it off. An [TitleMode.Always] scaffold below `false` behaves as
 * [TitleMode.InlineOnly]; tickets 33 (detail column) and 104 (Settings
 * dialog insets) are the providers.
 */
val LocalPrefersLargeTitles = staticCompositionLocalOf { true }

/**
 * The insets this scaffold's host already guarantees (android-ui-parity
 * #104): `null` (the default) = the scaffold is the window's own child and
 * reads the live status bar / display cutout; non-null = the host sits
 * inside the safe area already (the tablet Settings form-sheet card, whose
 * 25-derived chrome must not add a status-bar gap) and the scaffold uses
 * those insets instead — pushed screens (140's engine detail, 101's
 * Licenses, 45's folder) then need no `topInset` plumbing. Hosts provide
 * `WindowInsets(0, 0, 0, 0)` for "already inside".
 */
val LocalLargeTitleHostInsets = staticCompositionLocalOf<WindowInsets?> { null }

/**
 * The default `topInset` of every 25-convention screen: the host's own
 * insets when [LocalLargeTitleHostInsets] is provided (104's card — its
 * screens inset nothing), the live status bar otherwise. Screens that
 * forward a `topInset` to [LargeTitleScaffold] should default to this
 * and never re-read the status bar themselves, so being hosted in the
 * dialog (or any future inset-safe host) costs them no plumbing.
 */
@Composable
fun largeTitleDefaultTopInset(): Dp =
    LocalLargeTitleHostInsets.current
        ?.let { host -> with(LocalDensity.current) { host.getTop(this).toDp() } }
        ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Snaps per touch before the settle loop gives up (round 51: provably finite). */
private const val MaxSnapAttempts = 5

/** The `__large_title` item key — screen keys never start with `__`. */
private const val LargeTitleItemKey = "__large_title"

/** The `__pinned_accessory` item key (ticket 105's stacked search field). */
private const val PinnedAccessoryItemKey = "__pinned_accessory"

/** The inline title's `testTag` (the ticket's a11y/test contract). */
private const val InlineTitleTag = "nav-inline-title"

/**
 * Recomposition probe (the ticket's Verification: "check with a
 * recomposition counter in the test"): counts [LargeTitleScaffold] body
 * executions, so the test can assert the scroll budget — at most 2
 * recompositions per 300 dp of scroll (the `engaged` flip and the
 * `inlineVisible` flip). Production code never reads it; the counter is
 * main-thread-only and shared by every composed scaffold, so reset it before
 * the composition under test.
 */
internal object LargeTitleRecompositionProbe {
    var count = 0

    fun reset() {
        count = 0
    }
}

/**
 * The screen frame every iOS `UINavigationController` tab screen gets
 * (android-ui-parity #25): a transparent 44 dp nav row under the status bar
 * (back glass circle, centred inline title, trailing slot) over the list,
 * the large title as the **first item of the [LazyColumn]**, and the whole
 * bar backed by ticket 09's soft scroll-edge effect — rows scroll up under
 * the status bar and nav row, blurred and faded, exactly as on iOS 26 with
 * no bar appearance object.
 *
 * The collapse is pure scroll math ([LargeTitleMetrics]): the large title
 * moves 1:1 with the content and is clipped at the nav row's bottom while
 * the inline title fades in; at rest a release snaps to open or closed.
 * All values are read in the draw/layout phase (or as derived booleans), so
 * scrolling never recomposes this frame — see the recomposition budget in
 * `LargeTitleScaffoldTest`.
 *
 * **Haze convention (ticket 07):** one `HazeState` per screen; the
 * `LazyColumn` (plus [backdrop]) is the only `hazeSource`; the edge effect
 * and the nav row are siblings drawn after it, never inside it.
 *
 * @param title shown as both the large and the inline title; changes
 *   propagate in place without resetting the scroll position (a folder
 *   rename)
 * @param mode see [TitleMode]; `Always` downgrades to `InlineOnly` under
 *   [LocalPrefersLargeTitles] = false
 * @param listState the scaffold reads `firstVisibleItemIndex/Offset` only
 *   inside draw/derived lambdas — callers may share the state with
 *   pull-to-refresh (48), the connection line (50) and `RegisterScrollToTop`
 *   (26)
 * @param topInset the status bar height; **0** when hosted in a dialog or
 *   sheet that already sits below it (104, 31)
 * @param contentPadding extra padding (bottom chrome etc.) added to the
 *   scaffold's own top padding; delegates per side at measure time, so a
 *   caller reading insets in layout (49's IME padding) keeps doing so
 * @param showBack "this entry is not the stack's root entry" — not 24's
 *   stack-wide `canPop`, which is also true for the root while a pushed
 *   screen sits on top of it (the root stays composed under the push
 *   parallax). Previews, the gallery and tests outside a `NavStackHost`
 *   must pass it (and [onBack]) explicitly: 24's locals have no default.
 * @param leading replaces the back circle when non-null (a sheet's close ✕,
 *   93/110)
 * @param trailing the nav row's trailing slot: 44 dp [GlassCircleButton]s
 *   and `GlassTextCapsule`s (⋯ 46, Edit/Done 45/47, Done 104)
 * @param pinnedAccessory the stacked search field slot (105): drawn as
 *   chrome above the edge effect at `navBottom + max(0, L − c)`, covering
 *   its own spacer item under the large title; pass `null` when absent
 * @param pinnedAccessoryHeight the accessory's fixed height `h`
 * @param pullToRefresh ticket 48's pull-to-refresh state, or `null` (the
 *   default) for screens without a refresh — Settings, Licenses, engine
 *   detail. When non-null: the source box carries the state's
 *   nested-scroll connection, the list and the pinned accessory ride the
 *   gap (`Modifier.offset`, never `contentPadding` — the collapse math
 *   stays intact), the backdrop does **not** move, and the 21-dp cell
 *   spinner indicator is drawn above the source box, under the edge
 *   effect. Ignored in [TitleMode.None] — the session screen never passes
 *   one
 * @param backdrop content behind the list, inside the haze source (the
 *   wallpaper hero, 21/46)
 * @param content the list's items after the scaffold's own
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    mode: TitleMode = TitleMode.Always,
    listState: LazyListState = rememberLazyListState(),
    // 104: a host already inside the safe area resolves the default to
    // its own insets (the card passes WindowInsets(0, 0, 0, 0)); the
    // status-bar default is otherwise 25's own, unchanged.
    topInset: Dp = largeTitleDefaultTopInset(),
    contentPadding: PaddingValues = PaddingValues(),
    showBack: Boolean = LocalNavStack.current.entries.firstOrNull()?.id != LocalNavEntry.current.id,
    onBack: () -> Unit = LocalNavStack.current.let { stack -> { stack.pop() } },
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    pinnedAccessory: (@Composable () -> Unit)? = null,
    pinnedAccessoryHeight: Dp = 0.dp,
    pullToRefresh: PullToRefreshState? = null,
    backdrop: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LargeTitleRecompositionProbe.count++
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // prefersLargeTitles = false demotes Always to InlineOnly (iOS: the bar
    // shows the inline title only).
    val effectiveMode =
        if (mode == TitleMode.Always && !LocalPrefersLargeTitles.current) TitleMode.InlineOnly else mode
    val navBottom = topInset + LargeTitleDefaults.NavRowHeight
    val hasLargeTitle = effectiveMode == TitleMode.Always

    // Horizontal safe area: UIKit's layout margins include the cutout
    // (landscape notch). The inset getters are physical left/right
    // (direction-agnostic, like GlassTabBar), so start/end are mirrored by
    // hand. 104: a non-null LocalLargeTitleHostInsets replaces the live
    // cutout (the card's host insets), so its screens inset nothing.
    val cutout = LocalLargeTitleHostInsets.current ?: WindowInsets.displayCutout
    val cutoutStartPx =
        if (layoutDirection == LayoutDirection.Ltr) cutout.getLeft(density, layoutDirection)
        else cutout.getRight(density, layoutDirection)
    val cutoutEndPx =
        if (layoutDirection == LayoutDirection.Ltr) cutout.getRight(density, layoutDirection)
        else cutout.getLeft(density, layoutDirection)
    val cutoutStart = with(density) { cutoutStartPx.toDp() }
    val cutoutEnd = with(density) { cutoutEndPx.toDp() }

    // c (dp), read lazily so scroll frames never recompose this function:
    // only the draw lambdas, the accessory's offset lambda, the derived
    // booleans and the snap effect read it.
    val collapseDp: () -> Float = remember(listState, density) {
        {
            LargeTitleMetrics.collapse(
                listState.firstVisibleItemIndex,
                with(density) { listState.firstVisibleItemScrollOffset.toDp().value },
            )
        }
    }
    // Exactly one title is exposed to TalkBack at a time: the inline title is
    // in while the large title is out. A derived boolean, so the flip costs
    // one recomposition of the item's scope, never a frame.
    val inlineVisible by remember(listState, effectiveMode) {
        derivedStateOf {
            !hasLargeTitle || LargeTitleMetrics.inlineAlpha(collapseDp()) >= 0.5f
        }
    }

    // The caller's padding is delegated per side at measure time; only the
    // scaffold's own top is added here, so inset-reading paddings keep
    // working without recomposition.
    val listContentPadding = remember(contentPadding, navBottom, effectiveMode) {
        ScaffoldContentPadding(
            base = contentPadding,
            top = if (effectiveMode == TitleMode.None) 0.dp else navBottom,
        )
    }

    // End-of-scroll snap (UIKit never rests half-collapsed): fires when a
    // scroll stops with 0 < c < L and animates to the 50 % threshold's side.
    // The flow's initial value (the composition-time state) is dropped: it
    // is not a scroll end, and without the drop a screen composed at a
    // restored mid position (the mid golden, a reopened folder) would snap
    // immediately on appearing.
    //
    // Only a scroll the user dragged snaps (UIKit snaps at the end of
    // dragging/deceleration, never after a programmatic setContentOffset).
    // A programmatic or accessibility scroll — a semantics ScrollBy (TalkBack,
    // performScrollToNode), bring-into-view — that rests inside 0 < c < L is
    // left there: snapping it back undid the scroll, so a caller bringing a
    // row a few px into view (Settings' Remove Wallpaper, straddling the
    // bottom edge at 874 dp) re-issued the same scroll forever (round 45).
    //
    // "The user scrolled" is read from the finger itself (round 49), not
    // from foundation's DragInteractions: [snapGate] is set by a pointer
    // down on the list (an Initial-pass observer that consumes nothing,
    // [observeFingerForSnap]) and cleared once that touch has settled — a
    // tap that scrolled nothing on its up, or a scroll end with nothing (left)
    // to snap, or a completed snap. A semantics/a11y scroll has no pointer,
    // so it never snaps. The flag must survive the release's first scroll
    // end: the drag's scroll block ends, then onDragStopped launches the
    // fling as a second one (even at zero velocity), whose UserInput
    // priority cancels a snap started in between.
    val snapGate = remember(listState) { SnapGate() }
    LaunchedEffect(listState, effectiveMode) {
        if (!hasLargeTitle) return@LaunchedEffect
        if (LargeTitleSnapDiagnostics.enabled) {
            launch {
                listState.interactionSource.interactions.collect { interaction ->
                    LargeTitleSnapDiagnostics.log("interaction ${interaction::class.simpleName}")
                }
            }
        }
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .onEach { inProgress ->
                LargeTitleSnapDiagnostics.log(
                    "isScrollInProgress=$inProgress userScroll=${snapGate.userScroll} c=${collapseDp()}",
                )
            }
            .drop(1)
            .filter { !it }
            .collect {
                // The settle loop (round 50): snap, and if something
                // interrupts the snap, wait for THAT scroll to end and snap
                // again from wherever it left c — until the touch settles.
                // The interrupting scroll's end cannot come back through the
                // edge flow above: snapshotFlow re-reads the value when this
                // collector resumes, so the interrupting true→false is
                // conflated to `false`, equal to the last emitted value, and
                // distinctUntilChanged drops it (CI 9863226e: "snap cancelled
                // at c=20.0", then nothing).
                //
                // Provably finite (round 51; CI 88b067d0 hung for 73 min):
                // at most [MaxSnapAttempts] snaps per touch, and every retry
                // first suspends for a frame. When nothing is scrolling,
                // `first { !it }` returns without suspending, so a snap that
                // is refused at once (a higher-priority mutator holding the
                // list) would otherwise spin on the main thread without
                // yielding. A retry does not wait for a NEW scroll to be
                // observed: the release's zero-velocity fling can start and
                // end before this catch runs, and that is exactly the case
                // that must re-snap.
                var attempts = 0
                while (snapGate.userScroll) {
                    if (attempts == MaxSnapAttempts) {
                        LargeTitleSnapDiagnostics.log("snap gave up after $attempts attempts at c=${collapseDp()}")
                        snapGate.userScroll = false
                        break
                    }
                    attempts++
                    val c = collapseDp()
                    val target = LargeTitleMetrics.snapTarget(c)
                    if (target == null) {
                        // Settled at 0, at L or past the title: nothing to
                        // snap. A completed snap lands here too if its own
                        // end is ever seen, so it can never re-trigger.
                        snapGate.userScroll = false
                        break
                    }
                    LargeTitleSnapDiagnostics.log("snap c=$c -> $target")
                    try {
                        listState.animateScrollBy(
                            with(density) { (target - c).dp.toPx() },
                            Motion.LargeTitleSnap.spec(),
                        )
                        snapGate.userScroll = false
                    } catch (e: CancellationException) {
                        // The release's fling (UserInput priority) or a new
                        // drag interrupted the snap (MutationInterruptedException).
                        // A real scope cancellation rethrows here; otherwise
                        // `userScroll` stays set and the loop waits for the
                        // interrupting scroll to end, then snaps from there.
                        coroutineContext.ensureActive()
                        LargeTitleSnapDiagnostics.log("snap cancelled at c=${collapseDp()}")
                        snapshotFlow { listState.isScrollInProgress }.first { !it }
                        // Always suspend between attempts (see above).
                        withFrameNanos { }
                        LargeTitleSnapDiagnostics.log("interrupting scroll ended at c=${collapseDp()}")
                    }
                }
            }
    }

    val hazeState = rememberHazeState()

    // Pull-to-refresh (48): wired only under a nav row — TitleMode.None
    // draws its own header and never passes a state. The connection sits
    // on the source box, the *parent* of the list (never on the list
    // itself: it must receive the list's own scroll leftovers), the list
    // reports its viewport height and rides the gap.
    val pull = if (effectiveMode == TitleMode.None) null else pullToRefresh

    ProvideHazeState(state = hazeState) {
        Box(modifier.fillMaxSize().background(palette.background)) {
            // 1. The haze source: everything that scrolls, plus the backdrop
            //    behind it. The LazyColumn fills the screen, so rows draw
            //    into the padding region under the bar and the edge effect
            //    softens them. With a pull-to-refresh the box also carries
            //    the gesture's nested-scroll connection.
            Box(
                if (pull != null) {
                    Modifier.fillMaxSize().hazeSource(hazeState).nestedScroll(pull.nestedScrollConnection)
                } else {
                    Modifier.fillMaxSize().hazeSource(hazeState)
                },
            ) {
                backdrop()
                LazyColumn(
                    state = listState,
                    contentPadding = listContentPadding,
                    modifier = if (pull != null) {
                        // Translation, not contentPadding: offset is
                        // placement-only, so the large-title collapse math
                        // and firstVisibleItemScrollOffset are untouched.
                        // offsetPx is read in the layout phase only — a pull
                        // frame never recomposes this scaffold. The
                        // backdrop above stays fixed (iOS backgroundView).
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { pull.viewportPx = it.height.toFloat() }
                            .offset { IntOffset(0, pull.offsetPx.roundToInt()) }
                            .observeFingerForSnap(snapGate, listState)
                    } else {
                        Modifier.fillMaxSize().observeFingerForSnap(snapGate, listState)
                    },
                ) {
                    if (hasLargeTitle) {
                        item(key = LargeTitleItemKey, contentType = "largeTitle") {
                            LargeTitleRow(
                                title = title,
                                collapseDp = collapseDp,
                                inlineVisible = inlineVisible,
                                cutoutStart = cutoutStart,
                                cutoutEnd = cutoutEnd,
                            )
                        }
                    }
                    if (pinnedAccessory != null) {
                        item(key = PinnedAccessoryItemKey, contentType = "pinnedAccessory") {
                            Spacer(Modifier.fillMaxWidth().height(pinnedAccessoryHeight))
                        }
                    }
                    content()
                }
            }
            // 1½. Pull-to-refresh indicator (48): the 21-dp cell spinner
            //     centred in the gap between the nav row and the
            //     (pushed-down) large-title row — above the source box, so
            //     the backdrop never covers it, and under the edge effect
            //     and the nav row (the ticket's z-order). A plain overlay:
            //     transparent, no pointer input, composed only while the
            //     gap is open or the spinner runs/closes.
            if (pull != null) PullToRefreshIndicator(pull, navBottom)
            // 2. The soft scroll edge (not in None): over the content, under
            //    the nav row. With a pinned accessory the height follows the
            //    accessory (see EdgeEffectSlot); without one it is constant,
            //    and the only composition read here is `engaged`, which
            //    flips exactly once per scroll.
            if (effectiveMode != TitleMode.None) {
                if (pinnedAccessory == null) {
                    ScrollEdgeEffect(
                        height = navBottom + ScrollEdgeEffectDefaults.Extension,
                        engaged = listState.contentUnderTopEdge,
                        hazeState = hazeState,
                    )
                } else {
                    EdgeEffectSlot(
                        listState = listState,
                        hazeState = hazeState,
                        navBottom = navBottom,
                        accessoryHeight = pinnedAccessoryHeight,
                        inlineOnly = !hasLargeTitle,
                    )
                }
            }
            // 3. The pinned accessory (105): chrome above the edge effect,
            //    outside the haze source, riding up with the large title and
            //    pinning at navBottom. Read in the offset (layout) lambda.
            pinnedAccessory?.let { accessory ->
                Box(
                    Modifier
                        .offset {
                            val y = LargeTitleMetrics.accessoryTop(
                                navBottom = navBottom.value,
                                c = collapseDp(),
                                inlineOnly = !hasLargeTitle,
                            )
                            // Pull-to-refresh (48): the accessory rides the
                            // gap with the list (read in the layout phase).
                            IntOffset(0, y.dp.roundToPx() + (pull?.offsetPx?.roundToInt() ?: 0))
                        }
                        .fillMaxWidth()
                        .height(pinnedAccessoryHeight),
                ) {
                    accessory()
                }
            }
            // 4. The nav row: full width, topInset + 44 high, transparent,
            //    blocking touches as a UIKit bar does (only its buttons
            //    react). A traversal group read before the content.
            if (effectiveMode != TitleMode.None) {
                var navWidthPx by remember { mutableStateOf(0) }
                var leadWidthPx by remember { mutableStateOf(0) }
                var trailWidthPx by remember { mutableStateOf(0) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(navBottom)
                        .onSizeChanged { navWidthPx = it.width }
                        .blockTouches()
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = -1f
                        },
                ) {
                    Box(
                        Modifier
                            .padding(top = topInset)
                            .fillMaxWidth()
                            .height(LargeTitleDefaults.NavRowHeight),
                    ) {
                        if (leading != null || showBack) {
                            Row(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = LargeTitleDefaults.SideMargin + cutoutStart)
                                    .onSizeChanged { leadWidthPx = it.width },
                            ) {
                                if (leading != null) {
                                    leading()
                                } else {
                                    // The system back button: a 44/17
                                    // Semibold glass circle, the measured iOS
                                    // 26 bar-item ink (08's default,
                                    // SESSIONS-D1). The glyph mirrors itself
                                    // in RTL (06's autoMirror) — nothing to
                                    // do here.
                                    GlassCircleButton(
                                        icon = RobocoIcons.ChevronBackward,
                                        contentDescription = "Back",
                                        onClick = onBack,
                                        modifier = Modifier.testTag("nav-back"),
                                        weight = SymbolWeight.Semibold,
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = LargeTitleDefaults.SideMargin + cutoutEnd)
                                .onSizeChanged { trailWidthPx = it.width },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            trailing()
                        }
                        // The centred inline title: centred on the FULL
                        // scaffold width (the screenshot: 200.6 vs 201), not
                        // between the items; narrowed only where it would
                        // collide. Fades with c; exactly one of the two
                        // titles is ever exposed to TalkBack.
                        val inlineMaxWidth = if (navWidthPx > 0) {
                            with(density) {
                                LargeTitleMetrics.inlineMaxWidth(
                                    width = navWidthPx.toFloat(),
                                    leadingExtent = LargeTitleDefaults.SideMargin.toPx() + leadWidthPx,
                                    trailingExtent = LargeTitleDefaults.SideMargin.toPx() + trailWidthPx,
                                    gap = LargeTitleDefaults.TitleItemGap.toPx(),
                                ).toDp()
                            }
                        } else {
                            Dp.Infinity
                        }
                        BasicText(
                            title,
                            Modifier
                                .align(Alignment.Center)
                                .widthIn(max = inlineMaxWidth)
                                .graphicsLayer {
                                    alpha = if (hasLargeTitle) LargeTitleMetrics.inlineAlpha(collapseDp()) else 1f
                                }
                                .semantics {
                                    heading()
                                    if (!inlineVisible) hideFromAccessibility()
                                }
                                .testTag(InlineTitleTag),
                            style = uiStyle(
                                Face.SansSemibold,
                                typeScale.fixed(LargeTitleDefaults.InlineTitlePt),
                                palette.text,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The large-title row — the `LazyColumn`'s first item (`__large_title`). A
 * 52 dp box whose content is clipped at `c` from its top, which lands
 * exactly at the nav row's bottom on screen, so the large title disappears
 * under the bar instead of overlapping the inline title. The clip is
 * scroll-driven draw work: `collapseDp` is read inside `drawWithContent`, so
 * scrolling never recomposes this row.
 */
@Composable
private fun LargeTitleRow(
    title: String,
    collapseDp: () -> Float,
    inlineVisible: Boolean,
    cutoutStart: Dp,
    cutoutEnd: Dp,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(LargeTitleDefaults.LargeRowHeight)
            .drawWithContent {
                val top = collapseDp().dp.toPx().coerceIn(0f, size.height)
                clipRect(top = top) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        BasicText(
            title,
            Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = LargeTitleDefaults.SideMargin + cutoutStart,
                    end = LargeTitleDefaults.SideMargin + cutoutEnd,
                    bottom = LargeTitleDefaults.LargeBottomInset,
                )
                // The item stays composed in the padding region while
                // clipped under the bar, so without this TalkBack would find
                // two headings.
                .semantics {
                    heading()
                    if (inlineVisible) hideFromAccessibility()
                }
                .testTag("nav-large-title"),
            style = uiStyle(
                Face.SansSemibold,
                typeScale.fixed(LargeTitleDefaults.LargeTitlePt),
                palette.text,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The scroll-edge effect while a pinned accessory is present: the band
 * grows as the accessory pins — `navBottom + 16 + h · (c / L)`, per frame.
 * The height is a composition parameter of `ScrollEdgeEffect`, so this slot
 * is its own composable: the per-frame `c` read recomposes only this slot,
 * never [LargeTitleScaffold] (the ticket's `EdgeEffectSlot` rule).
 */
@Composable
private fun EdgeEffectSlot(
    listState: LazyListState,
    hazeState: HazeState,
    navBottom: Dp,
    accessoryHeight: Dp,
    inlineOnly: Boolean,
) {
    val density = LocalDensity.current
    val height = if (inlineOnly) {
        navBottom + accessoryHeight + ScrollEdgeEffectDefaults.Extension
    } else {
        val c = LargeTitleMetrics.collapse(
            listState.firstVisibleItemIndex,
            with(density) { listState.firstVisibleItemScrollOffset.toDp().value },
        )
        navBottom + ScrollEdgeEffectDefaults.Extension +
            accessoryHeight * (c / LargeTitleDefaults.LargeRowHeight.value)
    }
    ScrollEdgeEffect(
        height = height,
        engaged = listState.contentUnderTopEdge,
        hazeState = hazeState,
    )
}

/**
 * The scaffold's `contentPadding` + its own top: the caller's object is
 * delegated per side at measure time (a caller reading insets in layout
 * keeps doing so), with the scaffold's top added in
 * `calculateTopPadding()` — never `Modifier.padding`, which would clip rows
 * at the bar and kill the "rows under the bar" effect.
 */
private class ScaffoldContentPadding(
    private val base: PaddingValues,
    private val top: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = base.calculateTopPadding() + top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding()
}

/**
 * The end-of-scroll snap's "a finger scrolled this list" flag (round 49).
 * Plain field, not snapshot state: written by the pointer observer and the
 * snap collector, both on the main thread, and never read in composition.
 */
private class SnapGate {
    var userScroll = false
}

/**
 * Observes (never consumes) the list's pointer stream in the Initial pass,
 * before the list's own scrollable sees it: a finger down marks the coming
 * scroll as the user's; an up with no scroll in progress (a tap, or a touch
 * that never passed the slop) clears it again. A drag's up still sees its
 * scroll in progress here (the scrollable ends it in the Main pass, after
 * this), so the flag survives into the release's scroll ends.
 */
private fun Modifier.observeFingerForSnap(gate: SnapGate, listState: LazyListState): Modifier =
    pointerInput(gate, listState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) {
                    gate.userScroll = true
                    LargeTitleSnapDiagnostics.log("pointer down -> userScroll=true")
                } else if (
                    event.changes.isNotEmpty() &&
                    event.changes.none { it.pressed } &&
                    !listState.isScrollInProgress &&
                    gate.userScroll
                ) {
                    gate.userScroll = false
                    LargeTitleSnapDiagnostics.log("pointer up with no scroll -> userScroll=false")
                }
            }
        }
    }

/**
 * Round 49 diagnostics for the snap gate (CI prints a test's stdout in its
 * report; the drag test also quotes [lines] in its failure message). Off by
 * default since round 50 ([enabled]); a test turns it on before composing.
 */
internal object LargeTitleSnapDiagnostics {
    /** Off by default (round 50); the drag test turns it on for its own evidence. */
    var enabled = false

    val lines = mutableListOf<String>()

    fun log(message: String) {
        if (!enabled) return
        val line = "LTS-DIAG $message"
        println(line)
        synchronized(lines) {
            lines += line
            if (lines.size > 200) lines.removeAt(0)
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun reset() {
        synchronized(lines) { lines.clear() }
    }
}

/**
 * The nav row's UIKit hit-testing: the bar's bounds swallow taps and drags,
 * so a row scrolled under the bar is never selected — only the buttons
 * inside react. The loop awaits without consuming; composing this
 * `pointerInput` makes the row the hit target that ends dispatch.
 */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
