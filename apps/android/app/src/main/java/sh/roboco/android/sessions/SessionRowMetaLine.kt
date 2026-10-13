package sh.roboco.android.sessions

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.PrBadge
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberBranchIconPainter
import sh.roboco.android.design.rememberProjectTilePainter
import sh.roboco.android.design.uiStyle

/**
 * The glyph's top inside its `AboveBaseline` placeholder, measured up from
 * the placeholder's bottom (= the text baseline) by the unrounded [riseDp].
 * Compose ceils `PlaceholderSpan.heightPx` to whole px, so the placeholder
 * top sits up to 1 px above `baseline − rise`; drawing from its top-left
 * lifted the tile and branch icon by that fraction (2 px probes on the
 * k = 1 row). Anchoring to the bottom restores the iOS `bounds.y` geometry.
 */
private fun DrawScope.baselineAnchor(riseDp: Float): Float = size.height - riseDp.dp.toPx()

/** The inline content ids: the tile, the branch gap and the branch icon. */
private const val TILE = "meta.tile"
private const val GAP = "meta.gap"
private const val BRANCH = "meta.branch"

/**
 * The session row's meta line (android-ui-parity #38) — the port of
 * `SessionCell.swift:112-137`: the project tile as an inline attachment
 * centred on the name's x-height, two spaces and the project name
 * (Geist Regular, `secondary`), then — when the session has a branch —
 * the measured 13 dp SF-17 gap, the git-branch icon tinted `subline`
 * (centred on the branch font's cap height) and the branch (smaller,
 * `subline`). One line, tail-faded by [FadingText] (ticket 10) — never
 * ellipsized. The PR badge (ticket 12) sits at the slot's right end,
 * vertically centred on the slot, with the text box ending 10 dp before
 * it (`SessionCell.swift:183-190`).
 *
 * This fills 37's meta slot and is its default content
 * (`SessionRow(..., meta = { SessionRowMetaLine(vm) })`): 37 measures the
 * slot with fixed max constraints and places it at (54, metaY), so this
 * composable always sees `maxWidth × maxHeight` = the slot and reports
 * exactly that — it requires bounded constraints (never compose it
 * unslotted).
 *
 * Light/dark: the tile painter and the palette resolve at composition, so
 * a theme flip redraws the tile, name, icon and branch on the next frame
 * with the same [vm] (the UIKit `registerForTraitChanges` re-configure,
 * `SessionCell.swift:71-74`). `text` is keyed on the palette (and the type
 * scale) for exactly that.
 *
 * The attachments are `Placeholder`s with
 * [PlaceholderVerticalAlign.AboveBaseline] (placeholder bottom at the
 * text baseline — NSTextAttachment `bounds.y`'s equivalent): their heights
 * are the rises from [SessionRowMetaMetrics.M], and the glyphs are drawn
 * with `drawBehind` from `rise` above each placeholder's bottom (the
 * baseline — see [baselineAnchor]), hanging below the
 * baseline — the hang (tile 3.29 dp at k = 1) always stays inside Geist's
 * descent, so FadingText's clip box never cuts it. The branch gap is a
 * **zero-height** placeholder (13 dp wide, not type-scaled): the same
 * advance as iOS's three SF-17 spaces without adding a taller font's
 * metrics to the Compose line.
 *
 * Semantics: none of its own. [FadingText] and [PrBadge] set their own,
 * and 37's row root `clearAndSetSemantics` removes them from the
 * accessibility tree — the row is one node with `sessionRowA11yLabel(vm)`.
 */
@Composable
fun SessionRowMetaLine(vm: SessionRowVM, modifier: Modifier = Modifier) {
    val ts = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val m = remember(ts.factor) { SessionRowMetaMetrics.of(ts.factor) }
    // Ticket 12's painters: the tile is light/dark-aware and NOT tintable
    // (`.alwaysOriginal`); only the branch icon is a tinted template.
    val tilePainter = rememberProjectTilePainter(tileName(vm), vm.colorIndex, m.tileSide.dp)
    val branchPainter = rememberBranchIconPainter()
    val nameStyle = uiStyle(Face.Sans, ts.fixed(m.nameSize), palette.secondary)
    val branch = vm.branch
    val prNumber = vm.prNumber
    val prState = vm.pr
    val showBadge = showsBadge(vm)

    // Keyed on the palette (the subline span recolours on a theme flip)
    // and on ts — fixed() depends on the density as well as the factor.
    val text = remember(vm.projectName, branch, palette, ts) {
        buildAnnotatedString {
            appendInlineContent(TILE)
            append("  " + vm.projectName)
            if (!branch.isNullOrEmpty()) {
                appendInlineContent(GAP)
                appendInlineContent(BRANCH)
                withStyle(SpanStyle(fontSize = ts.fixed(m.branchSize), color = palette.subline)) {
                    append(" " + branch)
                }
            }
        }
    }
    val inline = mapOf(
        TILE to InlineTextContent(
            Placeholder(ts.fixed(m.tileSide), ts.fixed(m.tileRise), PlaceholderVerticalAlign.AboveBaseline),
        ) {
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val s = m.tileSide.dp.toPx()
                    translate(top = baselineAnchor(m.tileRise)) {
                        with(tilePainter) { draw(Size(s, s)) }
                    }
                },
            )
        },
        // The three SF-17 spaces as a neutral zero-height advance.
        GAP to InlineTextContent(
            Placeholder(ts.fixed(SessionRowMetaMetrics.BRANCH_GAP), 0.sp, PlaceholderVerticalAlign.AboveBaseline),
        ) {},
        BRANCH to InlineTextContent(
            Placeholder(ts.fixed(m.branchSide), ts.fixed(m.branchRise), PlaceholderVerticalAlign.AboveBaseline),
        ) {
            // palette.subline already carries alpha 0.5; tint is SrcIn, so
            // it keeps it — never multiply the alpha again.
            val tint = palette.subline
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val s = m.branchSide.dp.toPx()
                    translate(top = baselineAnchor(m.branchRise)) {
                        with(branchPainter) { draw(Size(s, s), colorFilter = ColorFilter.tint(tint)) }
                    }
                },
            )
        },
    )
    Layout(
        content = {
            FadingText(text, nameStyle, inlineContent = inline)
            if (prNumber != null && prState != null) {
                PrBadge(prNumber, prState, size = m.badgeSize)
            }
        },
        modifier = modifier,
    ) { measurables, c ->
        // 37's slot: bounded max width/height (min 0).
        val w = c.maxWidth
        val h = c.maxHeight
        // The badge at its intrinsic size (ticket 12 measures the label),
        // right-aligned to the slot's end — the same point as the row's
        // W − 20, since the slot ends there.
        val bp = if (showBadge) measurables[1].measure(Constraints()) else null
        val bx = bp?.let { w - it.width }
        val textW = if (bx != null) {
            max(0, bx - SessionRowMetaMetrics.BADGE_GAP.dp.roundToPx())
        } else {
            w
        }
        val tp = measurables[0].measure(Constraints.fixedWidth(textW))
        layout(w, h) {
            // Physical, like the iOS frames: the text line's top at
            // textTop (the SF-17 strut baseline), the badge centred on
            // the slot ((metaH − H)/2, unrounded in dp; the px conversion
            // rounds — −0.5 dp at k = 1.6).
            tp.place(0, m.textTop.dp.toPx().roundToInt())
            if (bp != null && bx != null) {
                bp.place(bx, ((h - bp.height) / 2f).roundToInt())
            }
        }
    }
}
