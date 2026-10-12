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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
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
    topInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    contentPadding: PaddingValues = PaddingValues(),
    showBack: Boolean = LocalNavStack.current.entries.firstOrNull()?.id != LocalNavEntry.current.id,
    onBack: () -> Unit = LocalNavStack.current.let { stack -> { stack.pop() } },
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    pinnedAccessory: (@Composable () -> Unit)? = null,
    pinnedAccessoryHeight: Dp = 0.dp,
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
    // hand.
    val cutout = WindowInsets.displayCutout
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
    LaunchedEffect(listState, effectiveMode) {
        if (!hasLargeTitle) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                val c = collapseDp()
                val target = LargeTitleMetrics.snapTarget(c) ?: return@collect
                try {
                    listState.animateScrollBy(
                        with(density) { (target - c).dp.toPx() },
                        Motion.LargeTitleSnap.spec(),
                    )
                } catch (e: CancellationException) {
                    // A finger touching down mid-snap cancels
                    // animateScrollBy (MutationInterruptedException); without
                    // this catch the collect itself would end and every
                    // later release would silently stop snapping. A real
                    // scope cancellation rethrows here.
                    coroutineContext.ensureActive()
                }
            }
    }

    val hazeState = rememberHazeState()
    ProvideHazeState(state = hazeState) {
        Box(modifier.fillMaxSize().background(palette.background)) {
            // 1. The haze source: everything that scrolls, plus the backdrop
            //    behind it. The LazyColumn fills the screen, so rows draw
            //    into the padding region under the bar and the edge effect
            //    softens them.
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                backdrop()
                LazyColumn(
                    state = listState,
                    contentPadding = listContentPadding,
                    modifier = Modifier.fillMaxSize(),
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
                            IntOffset(0, y.dp.roundToPx())
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
