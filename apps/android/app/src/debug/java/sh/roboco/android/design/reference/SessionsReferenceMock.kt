package sh.roboco.android.design.reference

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.ceil
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BranchIcon
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.EdgeFade
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.HorizontalFades
import sh.roboco.android.design.Icon
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.PrBadge
import sh.roboco.android.design.ProjectTile
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.ScrollEdgeEffect
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults
import sh.roboco.android.design.horizontalFadeMask
import sh.roboco.android.design.prBadgeMetrics
import sh.roboco.android.design.prTone
import sh.roboco.android.design.rgba
import sh.roboco.android.design.uiStyle

/**
 * The static, pixel-placed mock of the iOS Sessions tab
 * (android-ui-parity #23) — the foundation set's integration check. It
 * builds **no new component**: it composes the parts shipped by tickets
 * 01–13 (theme, fonts, glass surface and buttons, scroll edge effect,
 * status glyphs, PR badges, project tiles, branch icons, harness marks,
 * icons, fading text) into the exact state of upstream's `1-off.png`,
 * using [ReferenceFixture]'s verbatim strings and
 * [ReferenceGeometry]'s fixed dp frames.
 *
 * - Fixed 402 × 874 dp canvas (iOS pt 1:1), 62 dp status-bar top, items at
 *   the PNG's measured tops. Nothing moves; the content sits inside
 *   [ProvideGlyphClock] frozen at 0 so nothing can tick.
 * - One [ProvideHazeState] per screen (ticket 07): the list is the only
 *   `hazeSource`; the title, glass buttons, accessory, tab pill and search
 *   circle are siblings drawn after it, never inside it.
 * - The mock is a single a11y node (`"iOS Sessions reference mock"`,
 *   tag `reference-mock`); children add nothing.
 * - Light and dark both render (only light has an iOS counterpart); the
 *   theme comes from the caller — the gallery, the previews and the board
 *   test all wrap it.
 * - No Material anywhere: Compose foundation only, no navigation, no
 *   list virtualization — the real Sessions screen is tickets 26/27/37-41.
 */
@Composable
fun SessionsReferenceMock(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    ProvideGlyphClock(frozenAtNanos = 0L) {
        ProvideHazeState {
            Box(
                modifier
                    .requiredSize(ReferenceGeometry.Width, ReferenceGeometry.Height)
                    .clipToBounds()
                    .background(palette.background)
                    .clearAndSetSemantics {
                        testTag = "reference-mock"
                        contentDescription = "iOS Sessions reference mock"
                    },
            ) {
                // 1. the list: the ONLY haze source (ticket 09's rule).
                Box(Modifier.matchParentSize().hazeSource(LocalHazeState.current!!)) {
                    ReferenceFixture.layout().forEach { (item, top) ->
                        Box(Modifier.offset(y = top).fillMaxWidth()) {
                            when (item) {
                                is RefItem.Row -> RefRow(item)
                                is RefItem.Header -> RefHeader(item)
                            }
                        }
                    }
                }
                // 2. the scroll-edge effect over the status-bar band.
                ScrollEdgeEffect(
                    height = ScrollEdgeEffectDefaults.height(ReferenceGeometry.StatusBarTop),
                    engaged = true,
                )
                // 3. chrome, all outside the source box.
                RefInlineTitle()
                GlassCircleButton(
                    icon = RobocoIcons.Ellipsis,
                    contentDescription = "More",
                    onClick = {},
                    modifier = Modifier.offset(ReferenceGeometry.More.left, ReferenceGeometry.More.top),
                    size = ReferenceGeometry.BarHeight,
                    iconPt = 17f,
                )
                RefAccessory(
                    Modifier
                        .offset(ReferenceGeometry.Accessory.left, ReferenceGeometry.Accessory.top)
                        .size(360.dp, 48.dp),
                )
                RefTabPill(
                    Modifier
                        .offset(ReferenceGeometry.TabPill.left, ReferenceGeometry.TabPill.top)
                        .size(289.dp, 62.dp),
                )
                GlassCircleButton(
                    icon = RobocoIcons.Magnifyingglass,
                    contentDescription = "Search",
                    onClick = {},
                    modifier = Modifier.offset(ReferenceGeometry.Search.left, ReferenceGeometry.Search.top),
                    size = 62.dp,
                    iconPt = 22f,
                    ink = palette.text,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The inline nav-bar title (MainTabController.swift:125-134)
// ---------------------------------------------------------------------------

/**
 * The collapsed bar's inline "Sessions": Geist SemiBold 17 in
 * `Palette.text`, centred in the 44 dp bar row at y 62 (centre (201, 84)),
 * drawn above the scroll-edge effect like the iOS title.
 */
@Composable
private fun RefInlineTitle(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Box(
        modifier
            .offset(y = ReferenceGeometry.StatusBarTop)
            .fillMaxWidth()
            .height(ReferenceGeometry.BarHeight),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Sessions",
            style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A measured text advance in px as a ceiled dp width — UIKit's
 * `ceil(sizeThatFits(...).width)`; the 1e-3 tolerance keeps an
 * exact-integer dp advance from ceiling up a whole dp after the px→dp
 * roundtrip.
 */
private fun ceiledDp(px: Int, pxPerDp: Float): Dp = ceil(px / pxPerDp - 1e-3f).dp

// ---------------------------------------------------------------------------
// RefRow — SessionCell.swift:49-191 at k = 1, fixed geometry
// ---------------------------------------------------------------------------

/**
 * One session row: harness mark, fading title, corner word + glyph, PR
 * badge and the meta line — every frame from [ReferenceGeometry], drawn
 * with absolute offsets (no Row flow, no card, no separator, no
 * background).
 */
@Composable
private fun RefRow(row: RefItem.Row, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current
    val pxPerDp = density.density
    val measurer = rememberTextMeasurer()

    // The corner word, colour and glyph (SessionCell.swift:95-104,139-151).
    val word = when (row.corner) {
        RefCorner.Done -> "Done"
        RefCorner.Failed -> "Failed"
        RefCorner.Input -> "Input"
        RefCorner.Time -> row.time ?: ""
    }
    val wordColor = when (row.corner) {
        RefCorner.Done -> palette.status.done
        RefCorner.Failed -> palette.status.failed
        RefCorner.Input -> palette.status.input
        RefCorner.Time -> palette.status.time
    }
    val glyphKind = when (row.corner) {
        RefCorner.Done -> StatusGlyphKind.Check(palette.status.done)
        RefCorner.Failed -> StatusGlyphKind.Dot(palette.status.failed)
        RefCorner.Input -> StatusGlyphKind.Dot(palette.status.input)
        RefCorner.Time -> null
    }
    val wordStyle = uiStyle(Face.SansMedium, ts.fixed(ReferenceGeometry.CORNER_PT), wordColor)
    // tw = ceil(sizeThatFits(width: 140).width) (SessionCell.swift:175-176);
    // Compose measures in px, so divide back to dp (1e-3 tolerance so an
    // exact-integer advance does not ceil up a whole dp).
    val wordLayout = measurer.measure(
        word, wordStyle, maxLines = 1, softWrap = false,
        constraints = Constraints(maxWidth = with(density) { 140.dp.roundToPx() }),
    )
    val wordWidth = ceiledDp(wordLayout.size.width, pxPerDp)
    val wordX = ReferenceGeometry.Width - ReferenceGeometry.Side - wordWidth
    // status.frame = (time.minX − 5 − 12, titleY + titleH/2 − 6, 12, 12).
    val glyphX = wordX - 5.dp - 12.dp
    val trailing = (if (glyphKind != null) glyphX else wordX) - 10.dp

    // The PR badge's pure width — ticket 12's prBadgeMetrics with the same
    // style (TextMotion.Animated) and the same ceil rule PrBadge measures
    // its own label with, so the right edge lands on x 382 exactly.
    val pr = row.pr
    val badgeWidth = if (pr == null) {
        null
    } else {
        val (_, state) = pr
        val style = uiStyle(Face.MonoMedium, ts.fixed(11f), prTone(state))
            .copy(textMotion = TextMotion.Animated)
        val layout = measurer.measure(pr.first.toString(), style, maxLines = 1, softWrap = false)
        val labelWidth = ceil((layout.getLineRight(0) - layout.getLineLeft(0)) / pxPerDp - 1e-3f)
        prBadgeMetrics(11f, labelWidth).width.dp
    }
    val badgeX = badgeWidth?.let { ReferenceGeometry.Width - ReferenceGeometry.Side - it }
    val metaRight =
        if (badgeX != null) badgeX - 10.dp else ReferenceGeometry.Width - ReferenceGeometry.Side

    // Title: SemiBold 17 full text (unseen) or Medium 17 at text@0.88,
    // through ticket 01's rgba so the alpha quantises like iOS's.
    val titleColor = if (row.emphasized) {
        palette.text
    } else if (palette.isDark) {
        rgba(0xE8E8EA, 0.88)
    } else {
        rgba(0x27272C, 0.88)
    }
    val titleStyle = uiStyle(
        if (row.emphasized) Face.SansSemibold else Face.SansMedium,
        ts.fixed(ReferenceGeometry.TITLE_PT),
        titleColor,
    )

    Box(modifier.fillMaxWidth().height(ReferenceGeometry.RowHeight)) {
        HarnessMark(
            row.harness,
            ReferenceGeometry.MarkSide,
            palette.text,
            Modifier.offset(
                ReferenceGeometry.Side,
                if (row.emphasized) ReferenceGeometry.MarkYEmphasized else ReferenceGeometry.MarkYSeen,
            ),
        )
        FadingText(
            row.title,
            titleStyle,
            Modifier
                .offset(ReferenceGeometry.TextX, ReferenceGeometry.TitleY)
                .size(trailing - ReferenceGeometry.TextX, ReferenceGeometry.TitleH),
        )
        Box(
            Modifier.offset(wordX, ReferenceGeometry.TitleY).size(wordWidth, ReferenceGeometry.TitleH),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(word, style = wordStyle, maxLines = 1, softWrap = false)
        }
        if (glyphKind != null) {
            StatusGlyph(glyphKind, Modifier.offset(glyphX, 15.dp))
        }
        if (pr != null && badgeX != null) {
            PrBadge(
                pr.first,
                pr.second,
                Modifier.offset(badgeX, ReferenceGeometry.MetaY),
                size = 11f,
            )
        }
        RefMeta(
            row,
            metaRight - ReferenceGeometry.TextX,
            Modifier.offset(ReferenceGeometry.TextX, 0.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// RefMeta — the meta line, absolutely placed (SessionCell.swift:112-131)
// ---------------------------------------------------------------------------

/**
 * The meta line: project tile, project name, branch icon and branch text,
 * placed by hand on the [ReferenceGeometry.MetaBaseline] strut.
 *
 * The box spans the meta run's width and the **whole row height** (y 0–62)
 * and clips horizontally only: the tile bottom (53.32) and the Geist-14
 * descenders (≈54.2) hang below the 18-dp meta frame, and UIKit does not
 * clip them either. A trailing 24 dp fade (ticket 10) is armed only when
 * the run overflows the box by more than 0.5 dp.
 */
@Composable
private fun RefMeta(row: RefItem.Row, boxWidth: Dp, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val branch = row.branch
    val nameStyle = uiStyle(Face.Sans, ts.fixed(ReferenceGeometry.META_PT), palette.secondary)
    val branchStyle = uiStyle(Face.Sans, ts.fixed(ReferenceGeometry.BRANCH_PT), palette.subline)

    // Written during measure, read only in the layer/draw phases — the
    // FadingText pattern, so a width change re-arms the mask without
    // recomposing.
    var overflows by remember { mutableStateOf(false) }

    Layout(
        content = {
            ProjectTile(row.tileName, row.colorIndex, side = ReferenceGeometry.TileSide)
            BasicText(row.project, style = nameStyle, maxLines = 1, softWrap = false)
            if (branch != null) {
                BranchIcon(palette.subline, side = ReferenceGeometry.BranchSide)
                BasicText(branch, style = branchStyle, maxLines = 1, softWrap = false)
            }
        },
        modifier = modifier
            .size(boxWidth, ReferenceGeometry.RowHeight)
            .clipToBounds()
            .horizontalFadeMask(
                masked = { overflows },
                fades = { dir ->
                    if (!overflows) {
                        null
                    } else {
                        val fw = EdgeFade.Width.toPx()
                        if (dir == LayoutDirection.Rtl) HorizontalFades(fw, 0f) else HorizontalFades(0f, fw)
                    }
                },
            ),
        measurePolicy = { measurables, constraints ->
            val tileSidePx = ReferenceGeometry.TileSide.roundToPx()
            val tileP = measurables[0].measure(Constraints.fixed(tileSidePx, tileSidePx))
            val nameP = measurables[1].measure(Constraints())
            val hasBranch = measurables.size == 4
            val branchSidePx = ReferenceGeometry.BranchSide.roundToPx()
            val iconP = if (hasBranch) {
                measurables[2].measure(Constraints.fixed(branchSidePx, branchSidePx))
            } else {
                null
            }
            val branchP = if (hasBranch) measurables[3].measure(Constraints()) else null

            // Box-local x: the box sits at the row's TextX, so the PNG's
            // row-relative frames shift by 54.
            val nameX = ReferenceGeometry.NameX - ReferenceGeometry.TextX
            val nameAdvance = nameP.width.toDp()
            val nameY =
                (ReferenceGeometry.MetaBaseline - (nameP[FirstBaseline] ?: 0).toDp()).roundToPx()
            val tileY = ReferenceGeometry.TileTop.roundToPx()
            val iconX = nameX + nameAdvance + ReferenceGeometry.NameToBranch
            val branchTextX = iconX + ReferenceGeometry.BranchSide + ReferenceGeometry.BranchToText
            val iconY = ReferenceGeometry.BranchTop.roundToPx()
            val branchTextY = if (branchP != null) {
                (ReferenceGeometry.MetaBaseline - (branchP[FirstBaseline] ?: 0).toDp()).roundToPx()
            } else {
                0
            }

            val contentRight =
                if (branchP != null) branchTextX + branchP.width.toDp() else nameX + nameAdvance
            val over = contentRight > boxWidth + EdgeFade.OverflowTolerance
            if (over != overflows) overflows = over

            layout(constraints.maxWidth, ReferenceGeometry.RowHeight.roundToPx()) {
                tileP.place(0, tileY)
                nameP.place(nameX.roundToPx(), nameY)
                if (iconP != null && branchP != null) {
                    iconP.place(iconX.roundToPx(), iconY)
                    branchP.place(branchTextX.roundToPx(), branchTextY)
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// RefHeader — SessionCell.swift:234-306 at k = 1
// ---------------------------------------------------------------------------

/**
 * One section header: SemiBold 14 title in `secondary`, Medium 14 count in
 * `tertiary` 7 dp after it, and a `chevron.down` (11 pt, Semibold,
 * `tertiary`) centred in the 16 × 20 box at x 366. All groups are
 * expanded in the mock, so the chevron is not rotated.
 */
@Composable
private fun RefHeader(header: RefItem.Header, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val titleStyle = uiStyle(Face.SansSemibold, ts.fixed(ReferenceGeometry.HEADER_PT), palette.secondary)
    val countStyle = uiStyle(Face.SansMedium, ts.fixed(ReferenceGeometry.HEADER_PT), palette.tertiary)
    // width = min(ceil(textWidth), width − 120) (SessionCell.swift:295-306).
    val titleLayout = measurer.measure(header.title, titleStyle, maxLines = 1, softWrap = false)
    val titleWidth = minOf(
        ceiledDp(titleLayout.size.width, density),
        ReferenceGeometry.Width - 120.dp,
    )
    val countX = ReferenceGeometry.Side + titleWidth + ReferenceGeometry.CountGap

    Box(modifier.fillMaxWidth().height(ReferenceGeometry.HeaderHeight)) {
        Box(
            Modifier
                .offset(ReferenceGeometry.Side, ReferenceGeometry.HeaderTextY)
                .size(titleWidth, ReferenceGeometry.HeaderTextH),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(header.title, style = titleStyle, maxLines = 1, softWrap = false)
        }
        Box(
            Modifier
                .offset(countX, ReferenceGeometry.HeaderTextY)
                .height(ReferenceGeometry.HeaderTextH),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(header.count.toString(), style = countStyle, maxLines = 1, softWrap = false)
        }
        // The chevron's 16 × 20 box at x = W − 36.
        Box(
            Modifier
                .offset(366.dp, ReferenceGeometry.HeaderTextY)
                .size(16.dp, ReferenceGeometry.HeaderTextH),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RobocoIcons.ChevronDown,
                palette.tertiary,
                sizePt = 11f,
                weight = SymbolWeight.Semibold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// RefAccessory — MainTabController.swift:224-330 (AskAnythingAccessory)
// ---------------------------------------------------------------------------

/**
 * The white glass accessory capsule (360 × 48, measured): a 34 dp
 * `accentSoft` circle 7 dp in with the accent `+` (15 pt Semibold),
 * "New session" (Medium 16, `text`) at x 52, and "1 needs you" (Medium 13,
 * `secondary`) ending 16 dp from the right, an input dot 7 dp before it.
 * Not interactive in the mock (no voice button — Roboco has none).
 */
@Composable
private fun RefAccessory(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val summary = "1 needs you"
    val summaryStyle = uiStyle(Face.SansMedium, ts.fixed(13f), palette.secondary)
    val summaryAdvance = measurer
        .measure(summary, summaryStyle, maxLines = 1, softWrap = false)
        .size.width / density
    val summaryX = 344.dp - summaryAdvance.dp

    GlassSurface(modifier, shape = CapsuleShape) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset(7.dp, 7.dp)
                    .size(34.dp)
                    .background(palette.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    RobocoIcons.Plus,
                    palette.accent,
                    sizePt = 15f,
                    weight = SymbolWeight.Semibold,
                )
            }
            Box(
                Modifier.offset(52.dp, 0.dp).height(48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    "New session",
                    style = uiStyle(Face.SansMedium, ts.fixed(16f), palette.text),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Box(
                Modifier.offset(summaryX, 0.dp).height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(summary, style = summaryStyle, maxLines = 1, softWrap = false)
            }
            // Vertically centred, its right edge 7 dp before the summary.
            StatusGlyph(
                StatusGlyphKind.Dot(palette.status.input),
                Modifier.offset(summaryX - 7.dp - 12.dp, 18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// RefTabPill — the system tab pill, measured from the PNG
// ---------------------------------------------------------------------------

/**
 * The white glass tab pill (289 × 62, measured): a `controlFill` capsule
 * selection behind "Sessions" (4, 4.5, 140.5 × 53), two 140.5 dp item
 * slots with a 24 dp tab glyph centred at y 25 and a SemiBold 10 label
 * with its baseline at y 50. Both labels are `Palette.text` — the PNG does
 * not dim the unselected tab.
 */
@Composable
private fun RefTabPill(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    GlassSurface(modifier, shape = CapsuleShape) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset(4.dp, 4.5.dp)
                    .size(140.5.dp, 53.dp)
                    .background(palette.controlFill, CapsuleShape),
            )
            RefTabItem(
                RobocoIcons.TabChat,
                "Sessions",
                Modifier.offset(4.dp, 0.dp).size(140.5.dp, 62.dp),
            )
            RefTabItem(
                RobocoIcons.TabSettings,
                "Settings",
                Modifier.offset(144.5.dp, 0.dp).size(140.5.dp, 62.dp),
            )
        }
    }
}

/** One tab slot: the 24 dp glyph centred horizontally at centre y 25 (m), label baseline y 50 (m). */
@Composable
private fun RefTabItem(icon: RobocoIcon, label: String, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Layout(
        content = {
            IconInBox(icon, palette.text, 24.dp)
            BasicText(
                label,
                style = uiStyle(Face.SansSemibold, ts.fixed(10f), palette.text),
                maxLines = 1,
                softWrap = false,
            )
        },
        modifier = modifier,
        measurePolicy = { measurables, constraints ->
            val sidePx = 24.dp.roundToPx()
            val iconP = measurables[0].measure(Constraints.fixed(sidePx, sidePx))
            val labelP = measurables[1].measure(Constraints())
            // Measured from the PNG: icon centre y 25, label baseline y 50.
            val iconCentreY = 25.dp.roundToPx()
            val labelBaselineY = 50.dp.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                iconP.place(
                    (constraints.maxWidth - iconP.width) / 2,
                    iconCentreY - iconP.height / 2,
                )
                val baseline = labelP[FirstBaseline] ?: 0
                labelP.place(
                    (constraints.maxWidth - labelP.width) / 2,
                    labelBaselineY - baseline,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "light", widthDp = 402, heightDp = 874)
@Composable
private fun SessionsReferenceMockLightPreview() {
    RobocoTheme(Appearance.Light) { SessionsReferenceMock() }
}

@Preview(name = "dark", widthDp = 402, heightDp = 874, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionsReferenceMockDarkPreview() {
    RobocoTheme(Appearance.Dark) { SessionsReferenceMock() }
}
