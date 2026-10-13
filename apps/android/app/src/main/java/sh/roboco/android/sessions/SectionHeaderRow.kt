package sh.roboco.android.sessions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.Icon
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/**
 * The text before the first hard line break — the single line UIKit's
 * `numberOfLines = 1` renders up to, the same cut ticket 10's `FadingText`
 * applies (so the measured natural width matches what is actually drawn).
 * LF, CR, LINE SEPARATOR and PARAGRAPH SEPARATOR; CR covers CRLF (the cut
 * lands before both).
 */
private val HardLineBreaks = charArrayOf('\n', '\r', '\u2028', '\u2029')

private fun firstLine(s: String): String {
    val i = s.indexOfAny(HardLineBreaks)
    return if (i < 0) s else s.substring(0, i)
}

/**
 * Child frames in px, physical (left-origin) coordinates — the port of
 * `SectionHeaderCell.layoutSubviews` (`ios:Threads/SessionCell.swift:295-306`)
 * as a pure function, so the geometry is testable without a device.
 *
 * [sectionHeaderFrames] converts every dp constant to px itself (the row
 * height and the two font sizes are the only values that scale with
 * [sh.roboco.android.design.TypeScale.factor]; all dp inputs here are
 * fixed points, converted by the caller with `dp.roundToPx()`).
 */
@Immutable
data class SectionHeaderFrames(
    /** `y = heightPx − 20 − 6` (14 px at k = 1): the 20-high text band's top. */
    val bandTop: Int,
    /** 20, physical left. */
    val titleLeft: Int,
    /** `min(titleNaturalPx, max(0, widthPx − 120))`. */
    val titleWidth: Int,
    /** `titleLeft + titleWidth + 7`. */
    val countLeft: Int,
    /** `countLeft + countNaturalPx + 7` — the 12 × 12 glyph slot's left. */
    val glyphLeft: Int,
    /** `bandTop + 4` (y + 20/2 − 6): the glyph slot's top. */
    val glyphTop: Int,
    /** `widthPx − 36` — the 16 × 20 chevron frame's left. */
    val chevronLeft: Int,
)

/**
 * The port of `layoutSubviews` (`SessionCell.swift:295-306`): every value
 * is a fixed point except the row height itself (which arrives as
 * [heightPx], already `ts.scaledDp(40)`).
 *
 * Nothing guards the count or glyph against running into the chevron — at
 * the maximum title width the count starts at `W − 93`, which leaves room
 * for 3–4 digits and the glyph; iOS has no guard either and the port must
 * not add one. All x values are physical: the row never mirrors in RTL.
 */
fun sectionHeaderFrames(
    widthPx: Int,
    heightPx: Int,
    titleNaturalPx: Int,
    countNaturalPx: Int,
    px20: Int,
    px6: Int,
    px120: Int,
    px7: Int,
    px4: Int,
    px36: Int,
): SectionHeaderFrames {
    val band = heightPx - px20 - px6
    val titleW = minOf(titleNaturalPx, maxOf(0, widthPx - px120))
    val titleL = px20
    val countL = titleL + titleW + px7
    val glyphL = countL + countNaturalPx + px7
    return SectionHeaderFrames(
        bandTop = band,
        titleLeft = titleL,
        titleWidth = titleW,
        countLeft = countL,
        glyphLeft = glyphL,
        glyphTop = band + px4,
        chevronLeft = widthPx - px36,
    )
}

/** The row's fixed geometry and pressed-fill constants (`SessionCell.swift:243,267-306`). */
private object SectionHeaderTokens {
    /** `(40 * TypeScale.factor).rounded()`: 40 at k = 1, 34 at 0.85, 64 at 1.6. */
    const val RowHeightPt = 40f

    /** `chevron.down` at 11 pt semibold — fixed, never scaled by k. */
    const val ChevronPt = 11f

    /** `bg.backgroundInsets` leading/trailing. */
    val FillHorizontalInset = 8.dp

    /** `bg.backgroundInsets` top/bottom. */
    val FillVerticalInset = 2.dp

    /** `bg.cornerRadius = 12`, continuous. */
    val FillCornerRadius = 12.dp
}

/**
 * The foldable section header row (android-ui-parity #39) — the visual
 * port of iOS `SectionHeaderCell` (`ios:Threads/SessionCell.swift:232-307`):
 * the title in `secondary`, the count in `tertiary`, an optional 12 dp
 * live glyph and the `chevron.down` pinned 36 dp from the right edge,
 * which springs to point right when the group folds.
 *
 * **Visual row only.** Ticket 41 hosts it in the list and owns the tap —
 * it passes `Modifier.clickable(src, indication = null, role = Role.Button)
 * { onToggleSection(id) }` (ticket 16's `contextMenu`, before the
 * `clickable`) in [modifier] — and hands the press state in as
 * [highlight]; ticket 40 owns the data types, the fold set, its
 * persistence and the selection haptic (this row fires none). The
 * modifier chain keeps [modifier] first, so 41's outer `clickable`
 * survives this row's [clearAndSetSemantics] (Compose combines a node's
 * semantics modifiers innermost-outwards; the clearing modifier drops
 * only what is inside it and the children).
 *
 * The pressed fill is `controlFill` (its own 0.075 alpha included) at
 * `alpha × highlight()` — rounded 12, inset 8 left/right and 2 top/bottom
 * (`updateConfiguration`, `:267-273`). [highlight] is read only in the
 * draw phase, so a change redraws without recomposing or re-laying-out.
 *
 * The chevron angle is `animateFloatAsState` with [Motion.SectionChevron]
 * (ζ 0.85, 0.3 s, settle-matched): it snaps on first composition (a
 * folded section loads already pointing right) and retargets from the
 * current angle and velocity when interrupted. Read inside the
 * `graphicsLayer` block so the spring only re-layers, never recomposes.
 * There is deliberately **no** `LocalReduceMotion` branch: iOS keeps this
 * spring under Reduce Motion (ticket 04's list).
 *
 * The frames are laid out by hand and placed with absolute
 * [androidx.compose.ui.layout.Placeable.place] — the iOS frames are
 * absolute and do not mirror in RTL (`FadingText` still fades the correct
 * edge for RTL text inside its own frame).
 *
 * @param highlight 0…1, read only in the draw phase: 41's
 *   `rememberRowHighlight(src, fadeOnRelease = false)` snaps it to 1 on
 *   press and 0 on release, so the fill appears and vanishes at once,
 *   like the `isHighlighted`-only background on iOS.
 */
@Composable
fun SectionHeaderRow(
    state: SectionHeaderState,
    highlight: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current
    val titleStyle = uiStyle(Face.SansSemibold, ts.scaled(13.5f), palette.secondary)
    val countStyle = uiStyle(Face.SansMedium, ts.scaled(13.5f), palette.tertiary)
    // iOS measures the title with `title.sizeThatFits` (`:300`) — the
    // ceil'd single-line width. Measuring here (not with an intrinsic
    // pass) matches what FadingText draws and keeps its `overflows` state
    // out of an intrinsic pass, which would flip it on every layout.
    val textMeasurer = rememberTextMeasurer()
    val titleLayout = remember(state.title, titleStyle, density) {
        textMeasurer.measure(firstLine(state.title), titleStyle, maxLines = 1, softWrap = false)
    }
    val fillShape = rememberSmoothRoundedShape(SectionHeaderTokens.FillCornerRadius)
    val angle = animateFloatAsState(
        targetValue = if (state.collapsed) -90f else 0f,
        animationSpec = Motion.SectionChevron.spec(),
        label = "sectionChevron",
    )
    // Draw-phase read: a press/redraw never recomposes the row.
    val hl by rememberUpdatedState(highlight)

    Layout(
        content = {
            FadingText(state.title, titleStyle, Modifier.layoutId("title"))
            SectionHeaderCount(state.count.toString(), countStyle, Modifier.layoutId("count"))
            if (state.live != null) {
                SectionLiveGlyph(state.live, Modifier.layoutId("glyph"))
            }
            Box(
                Modifier
                    .layoutId("chevron")
                    .size(16.dp, 20.dp)
                    .graphicsLayer { rotationZ = angle.value },
                contentAlignment = Alignment.Center,
            ) {
                Icon(RobocoIcons.ChevronDown, palette.tertiary, SectionHeaderTokens.ChevronPt, SymbolWeight.Semibold)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(ts.scaledDp(SectionHeaderTokens.RowHeightPt))
            .clearAndSetSemantics {
                contentDescription = "${state.title}, ${state.count}" +
                    if (state.live == SectionLive.Offline) ", Offline" else ""
                stateDescription = if (state.collapsed) "Collapsed" else "Expanded"
                testTag = "section-${state.id}"
            }
            .drawWithCache {
                // One pressed-fill outline per size: the row bounds inset
                // 8 left/right, 2 top/bottom, 12 dp continuous corners
                // (`updateConfiguration`, `:267-273`).
                val l = SectionHeaderTokens.FillHorizontalInset.toPx()
                val t = SectionHeaderTokens.FillVerticalInset.toPx()
                val outline = fillShape.createOutline(
                    Size(size.width - 2f * l, size.height - 2f * t),
                    layoutDirection,
                    this,
                )
                onDrawBehind {
                    // Draw-phase read: a fade redraws, never recomposes.
                    val a = hl().coerceIn(0f, 1f)
                    if (a > 0f) {
                        translate(l, t) { drawOutline(outline, palette.controlFill, alpha = a) }
                    }
                }
            },
        measurePolicy = { measurables, constraints ->
            val w = constraints.maxWidth // fillMaxWidth: bounded
            val h = constraints.maxHeight // height(ts.scaledDp(40))
            val px20 = 20.dp.roundToPx()
            val px6 = 6.dp.roundToPx()
            val px120 = 120.dp.roundToPx()
            val px7 = 7.dp.roundToPx()
            val px4 = 4.dp.roundToPx()
            val px36 = 36.dp.roundToPx()
            val px12 = 12.dp.roundToPx()
            val px16 = 16.dp.roundToPx()
            val band = Constraints.fixedHeight(px20)
            val titleM = measurables.first { it.layoutId == "title" }
            val countM = measurables.first { it.layoutId == "count" }
            val titleNatural = titleLayout.size.width
            // Natural width, 20 high — the iOS `ceil(count.sizeThatFits)`.
            val count = countM.measure(band)
            val f = sectionHeaderFrames(w, h, titleNatural, count.width, px20, px6, px120, px7, px4, px36)
            // Measured once, at exactly the (possibly capped) title width:
            // when the natural width is larger, FadingText detects the
            // overflow itself and fades its tail over the trailing 24 dp.
            val title = titleM.measure(
                Constraints(
                    minWidth = f.titleWidth,
                    maxWidth = f.titleWidth,
                    minHeight = px20,
                    maxHeight = px20,
                ),
            )
            val glyph = measurables.firstOrNull { it.layoutId == "glyph" }
                ?.measure(Constraints.fixed(px12, px12))
            val chevron = measurables.first { it.layoutId == "chevron" }
                .measure(Constraints.fixed(px16, px20))
            layout(w, h) {
                // place(), NOT placeRelative(): the iOS hand layout is
                // absolute and does not mirror in RTL.
                title.place(f.titleLeft, f.bandTop)
                count.place(f.countLeft, f.bandTop)
                glyph?.place(f.glyphLeft, f.glyphTop)
                chevron.place(f.chevronLeft, f.bandTop)
            }
        },
    )
}

/**
 * The 12 × 12 live-glyph slot — `live.frame` (`SessionCell.swift:304`).
 * `StatusGlyph` may draw outside its box (the spinner overhangs 0.75 dp
 * top and bottom), so it is never clipped and is not interactive
 * (`StatusGlyph.swift:38`). The exhaustive `when` (no `else`) makes
 * ticket 50's `Offline` fail to compile until its branch exists.
 */
@Composable
private fun SectionLiveGlyph(live: SectionLive, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    Box(modifier.size(12.dp)) {
        when (live) {
            SectionLive.Spinner -> StatusGlyph(StatusGlyphKind.Spinner)
            SectionLive.Input -> StatusGlyph(StatusGlyphKind.Dot(palette.status.input))
            // Ticket 50: the wifi-off line glyph in `tertiary` (web rule:
            // a glyph, never words — `space-filter.tsx:83-85`), so the
            // header reads as one grey family with its count and chevron.
            // `WifiOff` has the default opticalScale 1.0, SymbolWeight
            // Regular: ≈10.75 dp wide ink, stroke 0.75 dp — the same as
            // the web's 12 px icon.
            SectionLive.Offline -> IconInBox(RobocoIcons.WifiOff, palette.tertiary, box = 12.dp)
        }
    }
}

/**
 * A one-line label vertically centred in the 20 dp band and clipped to it
 * — what a `UILabel` gives the title and count for free
 * (`SessionCell.swift:298-303`): the line box (1.3 × size) is centred,
 * and at large factors it is taller than the band and clipped, which iOS
 * does too.
 */
@Composable
private fun SectionHeaderCount(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Layout(
        content = { BasicText(text, style = style, maxLines = 1, softWrap = false) },
        modifier = modifier.clipToBounds(),
        measurePolicy = { measurables, constraints ->
            val p = measurables.single().measure(Constraints())
            val h = constraints.maxHeight
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        },
    )
}
