package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
import kotlin.math.roundToInt

/**
 * The phone shell's bottom bar in its resting, **expanded** state
 * (android-ui-parity #26, split by #28): the Android port of the iOS 26
 * floating Liquid Glass tab bar (`ios:Shell/MainTabController.swift`) — a
 * glass capsule ("pill") holding Sessions and Settings with a grey
 * `controlFill` platter under the selected item, and a separate round glass
 * search button 8 dp to the side of the pill.
 *
 * The bar is now the thin **expanded-only** wrapper around the #28 chrome:
 * it sizes itself to `PillHeight + bottomGap` and delegates placement to
 * [TabChromeLayout] with the morph progress pinned to 0, so the expanded
 * geometry has exactly one owner. [MainTabShell] uses the layout directly
 * with the live minimize state; this wrapper stays for the gallery frames
 * and the previews.
 *
 * Geometry (measured from the 460 × 1000 px reference captures, 1 pt =
 * 1.1443 px): the row is centred in the window's inset-adjusted width with
 * 21 dp side margins and capped at 398 dp (the widest iPhone); the pill and
 * circle are 62 dp tall and share the same top and bottom; the bottom gap
 * is [tabBarBottomGap] (21 dp on gesture nav, 12 dp above 3-button nav).
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
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

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
        val insetStart = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetRightPx else insetLeftPx).toDp()
        }
        val insetEnd = with(density) {
            (if (layoutDirection == LayoutDirection.Rtl) insetLeftPx else insetRightPx).toDp()
        }
        // The pill lays its expanded content out at the p = 0 width while the
        // #28 morph shrinks the glass: it must know that size.
        val expandedPill = tabPillExpandedWidth(maxWidth.value, insetStart.value, insetEnd.value).dp

        // The row's selectable group (26's Row owned it): one group over the
        // pill cells and the search item, so TalkBack announces "x of 3".
        TabChromeLayout(
            progress = { 0f },
            bottomInset = bottomGap,
            hasAccessory = false,
            modifier = Modifier.matchParentSize().selectableGroup(),
            pill = {
                GlassTabPill(
                    selected = selected,
                    onTabTap = onTabTap,
                    minimizeProgress = { 0f },
                    minimized = false,
                    onRestore = {},
                    expandedWidth = expandedPill,
                    pillInteractionSource = pillInteractionSource,
                )
            },
            search = { GlassSearchButton(selected, onTabTap) },
            accessory = {},
        )
    }
}

/**
 * The tab pill (android-ui-parity #26, split by #28): the glass capsule
 * with Sessions and Settings. **Fills its incoming constraints** — the
 * chrome layout measures it at the morph's current rect, so the glass
 * itself reshapes from 62 dp tall to the 48 dp minimized circle; nothing
 * inside may hard-code the size.
 *
 * The content morph (#28's step 8) is a two-layer custom [Layout]:
 *
 *  - the **expanded layer** — 26's platter + items, via
 *    [TabPillCellsAndPlatter] — is measured once at the expanded pill
 *    size ([expandedWidth] × 62 dp) so its internal layout never reflows,
 *    shifted vertically by `(h − 62) / 2` so it stays centred, and faded
 *    with `alpha = 1 − p̂` (draw-phase). The glass capsule's clip hides
 *    whatever sticks out. While [minimized] is true the layer is
 *    `clearAndSetSemantics` so the faded items are not focusable;
 *  - the **selected-icon layer** — the selected tab's 24 dp glyph in
 *    `palette.text` — sits at its expanded cell centre at p = 0 and
 *    travels horizontally to the circle's centre `(24, 24)` at p = 1,
 *    alpha 1 throughout (placement-phase read: no re-measure). The
 *    expanded layer omits the selected item's icon, so it never shows
 *    twice while the layer fades.
 *
 * While [minimized] a full-size overlay carries one `Role.Button` node —
 * `testTag("tab-bar-minimized")`, the selected tab's title verbatim —
 * that calls [onRestore]; it also swallows the taps the faded cells
 * underneath would otherwise still receive. Otherwise 26's per-item
 * clicks and semantics apply unchanged, and the whole-pill press swell
 * (07) works in both states through the shared [pillInteractionSource].
 *
 * While Search is selected the slot is **not composed** at all — ticket
 * 29's [SearchReturnCircle] owns the pill slot through the morph, which
 * is why this pill no longer carries 26's interim "Search selected"
 * platter fade (29 deleted it; the look belongs to the morph now).
 *
 * @param selected the tab to show as selected (drives the platter).
 * @param onTabTap called on a tap of either pill cell.
 * @param minimizeProgress the morph position 0…1, read in placement and
 *   draw lambdas only — never in a composable body.
 * @param minimized the **target** (flips at the start of a morph): one
 *   restore button while true.
 * @param onRestore called when the minimized circle is tapped; expands.
 * @param expandedWidth the p = 0 pill width (`tabPillExpandedWidth`), from
 *   the caller that knows the shell width and insets — the chrome layout
 *   cannot pass data into its slots, so the caller computes this from the
 *   same pure geometry.
 * @param modifier the caller's modifier; the pill fills it.
 * @param pillInteractionSource shared by both cells, the pill's glass and
 *   the minimized restore button.
 */
@Composable
fun GlassTabPill(
    selected: TabId,
    onTabTap: (TabId) -> Unit,
    minimizeProgress: () -> Float,
    minimized: Boolean,
    onRestore: () -> Unit,
    expandedWidth: Dp,
    modifier: Modifier = Modifier,
    pillInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val palette = RobocoTheme.palette
    val reduce = LocalReduceMotion.current

    // The pill's own selection: while Search is selected the pill slot is
    // ticket 29's [SearchReturnCircle], so this is never composed with
    // `selected == Search` in the shell — the wrapper/gallery may still
    // pass it, in which case the pill keeps its last pill selection (the
    // platter stays where the pill last had it).
    val pillSelection = if (selected == TabId.Settings) TabId.Settings else TabId.Sessions
    val selectedIndex = if (pillSelection == TabId.Settings) 1 else 0

    // ------------------------------------------------ platter motion (26)
    // The platter index 0 ↔ 1 (float so it slides), interruptible from
    // the current value. (The interim "Search selected" platter fade was
    // deleted by ticket 29: the search morph owns that look now.)
    val platterIndex = remember { Animatable(if (selected == TabId.Settings) 1f else 0f) }
    LaunchedEffect(selected, reduce) {
        if (reduce) {
            if (selected != TabId.Search) {
                platterIndex.snapTo(if (selected == TabId.Settings) 1f else 0f)
            }
        } else {
            if (selected != TabId.Search) {
                platterIndex.animateTo(
                    if (selected == TabId.Settings) 1f else 0f,
                    Motion.ActionButton.spec(),
                )
            }
        }
    }

    val cellWidth = (expandedWidth - GlassTabBarDefaults.PlatterInset * 2) / 2

    GlassSurface(
        modifier = modifier
            .fillMaxSize()
            .testTag("tab-pill"),
        shape = CapsuleShape,
        interactive = true,
        interactionSource = pillInteractionSource,
    ) {
        // The morph: expanded layer + selected icon (two measurables).
        Layout(
            content = {
                // ---- layer 0: the expanded look, at the expanded size ----
                Box(
                    Modifier
                        .then(if (minimized) Modifier.clearAndSetSemantics { } else Modifier)
                        .graphicsLayer { alpha = 1f - minimizeProgress().coerceIn(0f, 1f) },
                ) {
                    // 26's expanded look: the platter under the two cells,
                    // shared with ticket 29's return circle. The pill hides
                    // the selected cell's glyph — its icon layer draws it.
                    TabPillCellsAndPlatter(
                        platterIndex = { platterIndex.value },
                        selectedCell = when (selected) {
                            TabId.Settings -> TabId.Settings
                            TabId.Search -> null
                            else -> TabId.Sessions
                        },
                        onTabTap = onTabTap,
                        pillSource = pillInteractionSource,
                        hideSessionsIcon = pillSelection == TabId.Sessions,
                        hideSettingsIcon = pillSelection == TabId.Settings,
                        cellWidth = cellWidth,
                    )
                }
                // ---- layer 1: the selected tab's travelling icon ----
                IconInBox(
                    icon = if (pillSelection == TabId.Settings) RobocoIcons.TabSettings else RobocoIcons.TabChat,
                    tint = palette.text,
                    box = GlassTabBarDefaults.IconBox,
                )
            },
        ) { measurables, constraints ->
            val expandedHeightPx = GlassTabBarDefaults.PillHeight.roundToPx()
            val expandedWidthPx = expandedWidth.roundToPx()
            val expanded = measurables[0].measure(Constraints.fixed(expandedWidthPx, expandedHeightPx))
            val iconBoxPx = GlassTabBarDefaults.IconBox.roundToPx()
            val icon = measurables[1].measure(Constraints.fixed(iconBoxPx, iconBoxPx))

            layout(constraints.maxWidth, constraints.maxHeight) {
                // p is read in the placement block only: the spring moves
                // the layer shift and the icon without a re-measure.
                val p = minimizeProgress()
                val shift = (constraints.maxHeight - expandedHeightPx) / 2
                expanded.placeRelative(0, shift)

                // The icon's centre: its expanded cell centre lerped toward
                // the minimized circle's (24, 24). The centre y is 24 in
                // both states, so it only travels horizontally.
                val cellW = (expandedWidthPx - 2 * GlassTabBarDefaults.PlatterInset.roundToPx()) / 2
                val startCentreX = GlassTabBarDefaults.PlatterInset.roundToPx() +
                    cellW * selectedIndex + cellW / 2
                val endCentreX = (TabChromeMetrics.Minimized / 2f).dp.roundToPx()
                val centreY = TabChromeMetrics.IconCentreY.dp.roundToPx()
                val centreX = lerp(startCentreX.toFloat(), endCentreX.toFloat(), p)
                icon.placeRelative(
                    (centreX - iconBoxPx / 2).roundToInt(),
                    (centreY - iconBoxPx / 2f).roundToInt(),
                )
            }
        }

        // The minimized circle: one restore button above the faded content,
        // sized to the pill. It also takes the taps the faded cells would
        // otherwise still receive inside the shrinking capsule.
        if (minimized) {
            Box(
                Modifier
                    .matchParentSize()
                    .testTag("tab-bar-minimized")
                    .clickable(
                        interactionSource = pillInteractionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = null,
                    ) { onRestore() }
                    .clearAndSetSemantics {
                        contentDescription = pillSelection.label
                        role = Role.Button
                    },
            )
        }
    }
}

/**
 * The round glass search button (android-ui-parity #26, split by #28):
 * `Role.Tab`, "Search", the 22 pt magnifier glyph centred in
 * `palette.text`. **Fills its incoming constraints** — 62 dp expanded,
 * 48 dp minimized — so the glass itself reshapes; the glyph keeps its iOS
 * point size in both states.
 *
 * The interim `controlFill` selection circle that 26 drew while Search
 * was selected was **deleted by ticket 29**: the shell's search slot is
 * [SearchTabField], whose morph (not an inner platter) is the
 * Search-selected look. This wrapper stays for [GlassTabBar] (26's
 * expanded-only wrapper: the gallery frames and the previews) and the
 * #28 chrome fixtures.
 */
@Composable
fun GlassSearchButton(
    selected: TabId,
    onTabTap: (TabId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val searchSource = remember { MutableInteractionSource() }

    GlassSurface(
        modifier = modifier
            .fillMaxSize()
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
        Icon(
            RobocoIcons.Magnifyingglass,
            tint = palette.text,
            sizePt = GlassTabBarDefaults.SearchIconPt,
        )
    }
}

/**
 * 26's expanded pill look — the grey `controlFill` selection platter under
 * the two tab cells (internal): the one piece [GlassTabPill] and ticket
 * 29's [SearchReturnCircle] share, so the morph's fading layer and the
 * normal pill can never drift apart.
 *
 * The platter sits [GlassTabBarDefaults.PlatterInset] from every edge and
 * slides between the cells with [platterIndex] (0 = Sessions, 1 =
 * Settings; a snapshot read in the offset's placement lambda, so the
 * slide animates without re-measuring). [hideSessionsIcon] /
 * [hideSettingsIcon] leave a cell's 24 dp glyph box empty — 28's
 * travelling-icon trick — while [selectedCell] drives the cells'
 * `selectable(selected = …)` state (null while Search is selected).
 */
@Composable
internal fun TabPillCellsAndPlatter(
    platterIndex: () -> Float,
    selectedCell: TabId?,
    onTabTap: (TabId) -> Unit,
    pillSource: MutableInteractionSource,
    hideSessionsIcon: Boolean,
    hideSettingsIcon: Boolean,
    cellWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    Box(modifier) {
        // The selection platter first (under the cells), inset 4 dp on all
        // sides. Modifier.offset mirrors in RTL, and the state read lives
        // in the placement lambda.
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (GlassTabBarDefaults.PlatterInset + cellWidth * platterIndex()).roundToPx(),
                        GlassTabBarDefaults.PlatterInset.roundToPx(),
                    )
                }
                .size(cellWidth, GlassTabBarDefaults.PlatterHeight)
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
                selected = selectedCell == TabId.Sessions,
                onTabTap = onTabTap,
                pillSource = pillSource,
                hideIcon = hideSessionsIcon,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            TabCell(
                tab = TabId.Settings,
                icon = RobocoIcons.TabSettings,
                selected = selectedCell == TabId.Settings,
                onTabTap = onTabTap,
                pillSource = pillSource,
                hideIcon = hideSettingsIcon,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
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
 *
 * [hideIcon] (#28) leaves the 24 dp box empty: the pill's selected-icon
 * layer draws the selected item's glyph itself, at every `p`, so the
 * expanded layer must not also draw it (it would show twice while that
 * layer fades).
 */
@Composable
private fun TabCell(
    tab: TabId,
    icon: RobocoIcon,
    selected: Boolean,
    onTabTap: (TabId) -> Unit,
    pillSource: MutableInteractionSource,
    hideIcon: Boolean,
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
        if (hideIcon) {
            Spacer(Modifier.size(GlassTabBarDefaults.IconBox))
        } else {
            IconInBox(icon, palette.text, box = GlassTabBarDefaults.IconBox)
        }
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

/**
 * The measured bar geometry (dp), all from the reference captures: 21 dp
 * side and bottom margins, the 62 dp pill and circle, the 4 dp platter
 * inset, the 24 dp icon box 12 dp below the cell top, the label baseline
 * at 49 dp, and the 398 dp row-width cap (the widest iPhone — not an iOS
 * value, a Roboco choice for landscape phones and unfolded foldables).
 * The minimized morph's numbers live in ticket 28's [TabChromeMetrics],
 * which a test pins against these.
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
// Previews (26's step 7): the bar on 22's striped GlassStage backdrop, 402 ×
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
