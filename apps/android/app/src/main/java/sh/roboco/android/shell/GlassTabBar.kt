package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.uiStyle

/**
 * The phone shell's bottom bar in its resting, **expanded** state
 * (android-ui-parity #26): the Android port of the iOS 26 floating Liquid
 * Glass tab bar (`ios:Shell/MainTabController.swift`) — a glass capsule
 * ("pill") holding Sessions and Settings with a grey `controlFill` platter
 * under the selected item, and a separate round glass search button 8 dp
 * to the side of the pill.
 *
 * Geometry (measured from the 460 × 1000 px reference captures, 1 pt =
 * 1.1443 px): the row is centred in the window's inset-adjusted width with
 * 21 dp side margins and capped at 398 dp (the widest iPhone); the pill and
 * circle are 62 dp tall and share the same top and bottom; the bottom gap
 * is [tabBarBottomGap] (21 dp on gesture nav, 12 dp above 3-button nav).
 * The composable's own height is pill + bottom gap, so it can be dropped
 * in as a bottom overlay over full-screen tab content.
 *
 * Press behaviour is ticket 07's: both pill cells and the pill's
 * [GlassSurface] share [pillInteractionSource], so holding either cell
 * swells the **whole pill** (scale + highlight); the search circle has its
 * own source and swells alone. The caller never wraps it in 08's
 * `glassPress` — the scale would apply twice.
 *
 * The platter slides between the cells with `Motion.ActionButton` and, in
 * the interim Search state (ticket 29 replaces it with the morph), the
 * pill platter fades out while a `controlFill` circle fades into the search
 * button, both with the glass fade tokens. Under Reduce Motion everything
 * snaps. All animated reads happen in `offset {}` / `graphicsLayer {}`
 * lambdas, so nothing recomposes per frame.
 *
 * No Material components and no ripple: plain `selectable` with
 * `indication = null` (01's `NoIndication` covers the default).
 *
 * @param selected the tab to show as selected (drives the platter).
 * @param onTabTap called on a tap of any bar item; route it through
 *   [MainTabState.onTabTap] for the haptic / re-select behaviour.
 * @param modifier applied to the bar's root; the bar sets its own width
 *   (fill) and height (62 dp + bottom gap).
 * @param pillInteractionSource shared by both pill cells and the pill's
 *   [GlassSurface]. Exposed so the gallery's "hold" toggle and the pressed
 *   golden can emit a `PressInteraction` into it.
 */
@Composable
fun GlassTabBar(
    selected: TabId,
    onTabTap: (TabId) -> Unit,
    modifier: Modifier = Modifier,
    pillInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val palette = RobocoTheme.palette
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val reduce = LocalReduceMotion.current

    // The horizontal system insets: navigation bar ∪ display cutout,
    // physical left/right (the insets getters are direction-agnostic).
    val horizontalInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
    val insetLeftPx = horizontalInsets.getLeft(density, layoutDirection)
    val insetRightPx = horizontalInsets.getRight(density, layoutDirection)

    // The bottom gap: 21 dp (gesture nav) or 12 dp above a 3-/2-button bar.
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val tappableBottomPx = WindowInsets.tappableElement.getBottom(density)
    val bottomGap = tabBarBottomGap(
        navBottom = with(density) { navBottomPx.toDp() },
        tappableBottom = with(density) { tappableBottomPx.toDp() },
    )

    BoxWithConstraints(modifier.fillMaxWidth().height(GlassTabBarDefaults.PillHeight + bottomGap)) {
        // The row is centred inside the inset-adjusted width: the wrapping
        // box eats the horizontal insets, the row centres in what remains.
        val available = maxWidth - with(density) { (insetLeftPx + insetRightPx).toDp() }
        val rowWidth = tabBarRowWidth(available)
        val pillWidth = rowWidth - GlassTabBarDefaults.Gap - GlassTabBarDefaults.SearchSize
        val cellWidth = (pillWidth - GlassTabBarDefaults.PlatterInset * 2) / 2
        val startInset = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetRightPx else insetLeftPx).toDp()
        }
        val endInset = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetLeftPx else insetRightPx).toDp()
        }

        // ------------------------------------------------ platter motion
        // The platter index 0 ↔ 1 (float so it slides), the pill-platter and
        // search-platter alphas (the interim Search look). In Search the
        // index is left where the pill last had it — it fades instead.
        val platterIndex = remember { Animatable(if (selected == TabId.Settings) 1f else 0f) }
        val platterAlpha = remember { Animatable(if (selected != TabId.Search) 1f else 0f) }
        val searchPlatterAlpha = remember { Animatable(if (selected == TabId.Search) 1f else 0f) }
        LaunchedEffect(selected, reduce) {
            if (reduce) {
                if (selected != TabId.Search) {
                    platterIndex.snapTo(if (selected == TabId.Settings) 1f else 0f)
                }
                platterAlpha.snapTo(if (selected != TabId.Search) 1f else 0f)
                searchPlatterAlpha.snapTo(if (selected == TabId.Search) 1f else 0f)
            } else {
                coroutineScope {
                    if (selected != TabId.Search) {
                        // Interruptible: always animate from the current value.
                        launch {
                            platterIndex.animateTo(
                                if (selected == TabId.Settings) 1f else 0f,
                                Motion.ActionButton.spec(),
                            )
                        }
                    }
                    launch { platterAlpha.glassFadeTo(selected != TabId.Search) }
                    launch { searchPlatterAlpha.glassFadeTo(selected == TabId.Search) }
                }
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .padding(start = startInset, end = endInset),
        ) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomGap)
                    .width(rowWidth)
                    .height(GlassTabBarDefaults.PillHeight)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(GlassTabBarDefaults.Gap),
            ) {
                // ---------------------------------------------------- the pill
                GlassSurface(
                    modifier = Modifier
                        .width(pillWidth)
                        .fillMaxHeight()
                        .testTag("tab-pill"),
                    shape = CapsuleShape,
                    interactive = true,
                    interactionSource = pillInteractionSource,
                ) {
                    // The selection platter first (under the cells), inset 4
                    // dp on all sides. Modifier.offset mirrors in RTL, and
                    // both state reads live in the placement/draw lambdas.
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (GlassTabBarDefaults.PlatterInset + cellWidth * platterIndex.value).roundToPx(),
                                    GlassTabBarDefaults.PlatterInset.roundToPx(),
                                )
                            }
                            .size(cellWidth, GlassTabBarDefaults.PlatterHeight)
                            .graphicsLayer { alpha = platterAlpha.value }
                            .background(palette.controlFill, CapsuleShape)
                            .testTag("tab-platter"),
                    )
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = GlassTabBarDefaults.PlatterInset),
                    ) {
                        TabCell(
                            tab = TabId.Sessions,
                            icon = RobocoIcons.TabChat,
                            selected = selected == TabId.Sessions,
                            onTabTap = onTabTap,
                            pillSource = pillInteractionSource,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        TabCell(
                            tab = TabId.Settings,
                            icon = RobocoIcons.TabSettings,
                            selected = selected == TabId.Settings,
                            onTabTap = onTabTap,
                            pillSource = pillInteractionSource,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }

                // --------------------------------------------- the search button
                val searchSource = remember { MutableInteractionSource() }
                GlassSurface(
                    modifier = Modifier
                        .size(GlassTabBarDefaults.SearchSize)
                        .selectable(
                            selected = selected == TabId.Search,
                            interactionSource = searchSource,
                            indication = null,
                            role = Role.Tab,
                        ) { onTabTap(TabId.Search) }
                        .semantics { contentDescription = "Search" }
                        .testTag("tab-search"),
                    shape = CircleShape,
                    interactive = true,
                    interactionSource = searchSource,
                    contentAlignment = Alignment.Center,
                ) {
                    // The interim selection circle until ticket 29's morph.
                    Box(
                        Modifier
                            .size(GlassTabBarDefaults.SearchSize - GlassTabBarDefaults.PlatterInset * 2)
                            .graphicsLayer { alpha = searchPlatterAlpha.value }
                            .background(palette.controlFill, CircleShape),
                    )
                    Icon(
                        RobocoIcons.Magnifyingglass,
                        tint = palette.text,
                        sizePt = GlassTabBarDefaults.SearchIconPt,
                    )
                }
            }
        }
    }
}

/**
 * One tab cell of the pill: a 24 dp icon box 12 dp below the cell top and
 * the Geist SemiBold 10 label with its baseline 49 dp below the cell top,
 * both in `palette.text` — the same ink whether selected or not (the
 * platter carries the selection, not the colour). The whole cell is the
 * hit target and the semantics node: `Role.Tab`, the label merged in, the
 * tag `tab-<identifier>`.
 */
@Composable
private fun TabCell(
    tab: TabId,
    icon: RobocoIcon,
    selected: Boolean,
    onTabTap: (TabId) -> Unit,
    pillSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    // Baseline 49 dp below the cell top = paddingFromBaseline(13) under the
    // 24 dp icon that starts 12 dp below the cell top (12 + 24 + 13 = 49).
    val labelFromBaseline = GlassTabBarDefaults.LabelBaseline -
        GlassTabBarDefaults.IconTop - GlassTabBarDefaults.IconBox
    Column(
        modifier
            .selectable(
                selected = selected,
                interactionSource = pillSource,
                indication = null,
                role = Role.Tab,
            ) { onTabTap(tab) }
            .semantics(mergeDescendants = true) { }
            .testTag("tab-${tab.identifier}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(GlassTabBarDefaults.IconTop))
        // The label carries the tab's name — the glyph stays decorative.
        IconInBox(icon, palette.text, box = GlassTabBarDefaults.IconBox)
        BasicText(
            tab.label,
            Modifier.paddingFromBaseline(top = labelFromBaseline),
            style = uiStyle(
                Face.SansSemibold,
                LocalTypeScale.current.fixed(GlassTabBarDefaults.LabelPt),
                palette.text,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Appearing with `Motion.glassIn()`, hiding with `Motion.glassOut()`. */
private suspend fun Animatable<Float, AnimationVector1D>.glassFadeTo(visible: Boolean) {
    val spec: AnimationSpec<Float> = if (visible) Motion.glassIn() else Motion.glassOut()
    animateTo(if (visible) 1f else 0f, spec)
}

/**
 * The measured bar geometry (dp), all from the reference captures: 21 dp
 * side and bottom margins, the 62 dp pill and circle, the 4 dp platter
 * inset, the 24 dp icon box 12 dp below the cell top, the label baseline
 * at 49 dp, and the 398 dp row-width cap (the widest iPhone — not an iOS
 * value, a Roboco choice for landscape phones and unfolded foldables).
 */
object GlassTabBarDefaults {
    /** The pill's (and the row's) height. */
    val PillHeight = 62.dp

    /** The round search button's size; same top and bottom as the pill. */
    val SearchSize = 62.dp

    /** The pill → search circle gap. */
    val Gap = 8.dp

    /** The screen → row margin, on top of the horizontal system insets. */
    val SideMargin = 21.dp

    /** The pill's bottom margin on gesture nav (and with no nav inset). */
    val MinBottomGap = 21.dp

    /** The extra clearance above a 3-/2-button navigation bar. */
    val ButtonNavGap = 12.dp

    /** The row never stretches past the widest iPhone's row. */
    val MaxRowWidth = 398.dp

    /** The platter's inset inside the pill, on all four sides. */
    val PlatterInset = 4.dp

    /** The platter (and the interim search circle): pill minus 2 × inset. */
    val PlatterHeight = PillHeight - PlatterInset * 2

    /** The tab glyph's layout box. */
    val IconBox = 24.dp

    /** The icon box's top below the cell top. */
    val IconTop = 12.dp

    /** The label baseline below the cell top. */
    val LabelBaseline = 49.dp

    /** The tab label's Geist point size, fixed (iOS tab labels ignore Dynamic Type). */
    const val LabelPt = 10f

    /** The search glyph's point size (ticket 06 measured 23.5 pt of ink). */
    const val SearchIconPt = 22f
}

/**
 * The pill's bottom margin: 12 dp above a 3-/2-button navigation bar
 * (`tappableBottom > 0`), otherwise `max(21, navBottom)` — the iOS 21 dp
 * margin that also never puts the pill over the gesture handle.
 * `tappableBottom` reads `WindowInsets.tappableElement`.
 */
fun tabBarBottomGap(navBottom: Dp, tappableBottom: Dp): Dp =
    if (tappableBottom > 0.dp) {
        tappableBottom + GlassTabBarDefaults.ButtonNavGap
    } else {
        maxOf(GlassTabBarDefaults.MinBottomGap, navBottom)
    }

/**
 * The row's width: `min(availableWidth − 2 × 21, 398)` — the measured
 * margins, capped at the widest iPhone so the items never stretch to
 * hundreds of dp on wide screens; the row stays centred instead.
 */
fun tabBarRowWidth(availableWidth: Dp): Dp =
    minOf(availableWidth - GlassTabBarDefaults.SideMargin * 2, GlassTabBarDefaults.MaxRowWidth)

// ---------------------------------------------------------------------------
// Previews (step 7): the bar on 22's striped GlassStage backdrop, 402 ×
// 140 dp, light and dark. The bar is the backdrop's sibling, never inside
// its hazeSource.
// ---------------------------------------------------------------------------

@Composable
private fun TabBarPreviewContent() {
    RobocoTheme {
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            GlassStage(
                height = 140.dp,
                backdropModifier = Modifier.hazeSource(haze),
            ) {
                GlassTabBar(TabId.Sessions, {}, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Preview(name = "tab bar light", widthDp = 402, heightDp = 140)
@Composable
private fun TabBarLightPreview() {
    TabBarPreviewContent()
}

@Preview(name = "tab bar dark", widthDp = 402, heightDp = 140, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TabBarDarkPreview() {
    TabBarPreviewContent()
}
