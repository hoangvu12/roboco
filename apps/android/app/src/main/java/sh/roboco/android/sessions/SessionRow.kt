package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/**
 * The two-line session row (android-ui-parity #37) — the port of iOS
 * `SessionCell` (`ios:Threads/SessionCell.swift`), the title line only:
 * the harness BrandMark, the fading title, the status glyph and the corner
 * word. Ticket 38 fills the meta slot with [SessionRowMetaLine] (the
 * `meta` parameter's default); the gestures (tap, swipe,
 * long-press) are attached by the list tickets (41, 44) through
 * [modifier].
 *
 * Rows sit straight on the page background: no card, no separators, no
 * clip. The layout is by hand ([SessionRowGeometry] ports
 * `layoutSubviews`), placed with absolute [androidx.compose.ui.layout.Placeable.place]
 * — the iOS frames are absolute and do not mirror in RTL.
 *
 * The row owns **no press state**. [highlight] is a 0…1 value read only
 * in the draw phase: ticket 41's `rememberRowHighlight` snaps it to 1 on
 * press, fades 1 → 0 over 300 ms on release; ticket 44 forces 0 while the
 * context-menu lift is active. A running fade therefore redraws without
 * recomposing or re-laying-out the row.
 *
 * Fixed height `R(62k)` ([SessionRowVM] is `@Immutable` so unchanged rows
 * skip recomposition; the list redraws often — 1 Hz time labels, live
 * status). The corner word is measured once and right-aligned inside its
 * ceil'd frame, because Compose forbids measuring a child twice in one
 * pass; the ≤ 1 dp ceil slack is the documented rounding tolerance.
 *
 * @param isCurrent the session open beside the sidebar (tablet): a quiet
 *   [sh.roboco.android.design.Palette.rowActive] fill, replaced by the
 *   highlight while pressed (`SessionCell.swift:58-59`).
 * @param meta ticket 38's meta line ([SessionRowMetaLine]) — the slot's
 *   default, so every caller that omits it (41's list, 49's search
 *   results, 105's sidebar) draws the project tile, name, branch and PR
 *   badge; pass `{}` for an explicitly empty slot.
 */
@Composable
fun SessionRow(
    vm: SessionRowVM,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    /** 0…1, read in draw only. 41's rememberRowHighlight drives it; 44 forces 0 while lifted. */
    highlight: () -> Float = { 0f },
    meta: @Composable () -> Unit = { SessionRowMetaLine(vm) },
) {
    val p = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current
    val c = corner(vm)
    val titleFace = if (vm.unseen) Face.SansSemibold else Face.SansMedium
    val titleStyle = uiStyle(
        titleFace,
        ts.scaled(16.5f),
        if (vm.unseen || vm.status != SessionRowVM.Status.Idle) p.text else p.text.copy(alpha = 0.88f),
    )
    val wordStyle = uiStyle(Face.SansMedium, ts.scaled(13f), c?.color(p) ?: p.status.time)
    val word = c?.word ?: vm.timeLabel
    val glyph = c?.glyph(p)
    val shape = rememberSmoothRoundedShape(SessionRowTokens.CornerRadius)
    val hl by rememberUpdatedState(highlight)
    // android-next #06: the "● N" pill — its text measured here (one
    // measure per child per pass), so the frames know its width.
    val agentsText = if (vm.runningSubagents > 0) "● ${vm.runningSubagents}" else null
    val agentsStyle = uiStyle(Face.SansMedium, ts.scaled(11.5f), p.status.working)
    val agentsMeasurer = rememberTextMeasurer()
    val agentsTextWidthPx = remember(agentsText, agentsStyle, density) {
        agentsText?.let { agentsMeasurer.measure(it, agentsStyle, maxLines = 1, softWrap = false).size.width } ?: 0
    }

    // The content has exactly six children, always in this order, so the
    // measure policy's indices are stable. The glyph and the pill stay an
    // empty Box when there is nothing to draw, and the meta slot is always
    // present.
    Layout(
        content = {
            HarnessMark(vm.harness ?: "claude-code", SessionRowGeometry.MARK_SIDE.dp, p.text)
            FadingText(vm.title, titleStyle)
            BasicText(
                word,
                style = wordStyle.copy(textAlign = TextAlign.End),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (glyph != null) StatusGlyph(glyph) else Box(Modifier)
            Box { meta() }
            if (agentsText != null) RunningAgentsPill(agentsText, agentsStyle) else Box(Modifier)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(ts.scaledDp(62f))
            .drawWithCache {
                // One outline per size (SessionCell.swift:79-85): the row
                // bounds inset 8 left/right, 1 top/bottom, 16 dp continuous
                // corners. The row is never clipped — the status glyph may
                // draw outside its 12×12 box (ticket 11).
                val outline = shape.createOutline(
                    Size(
                        size.width - 2f * SessionRowTokens.HorizontalInset.toPx(),
                        size.height - 2f * SessionRowTokens.VerticalInset.toPx(),
                    ),
                    layoutDirection,
                    this,
                )
                onDrawBehind {
                    // Draw-phase read: a fade redrew, never recomposed.
                    val h = hl().coerceIn(0f, 1f)
                    translate(
                        SessionRowTokens.HorizontalInset.toPx(),
                        SessionRowTokens.VerticalInset.toPx(),
                    ) {
                        val under = 1f - h
                        if (isCurrent && under > 0f) drawOutline(outline, p.rowActive, alpha = under)
                        if (h > 0f) drawOutline(outline, p.controlFill, alpha = h)
                    }
                }
            }
            // The caller's modifier came first, so its clickable semantics
            // (tickets 41/44) stay on the node; this clears only the row's
            // own children — one a11y node, the mark/title/glyph not
            // separately focusable (SessionCell.swift:150-155).
            .clearAndSetSemantics {
                testTag = "session-${vm.id}"
                contentDescription = sessionRowA11yLabel(vm)
            },
    ) { measurables, constraints ->
        val d = density.density
        fun Float.px(): Int = (this * d).roundToInt()
        val W = constraints.maxWidth / d

        // The corner word, measured once at its single-line width under the
        // 140 dp safety cap (the "at most 140" of sizeThatFits). Compose
        // ceils to whole px; frames then ceils in dp, as iOS does. A
        // measurable may only be measured once per pass, so the placeable
        // is right-aligned inside the ceil'd frame at placement instead.
        val wordP = measurables[2].measure(Constraints(maxWidth = SessionRowGeometry.WORD_MAX_WIDTH.px()))
        val f = SessionRowGeometry.frames(
            W,
            ts.factor,
            titleFace,
            wordP.width / d,
            glyph != null,
            agentsTextWidth = agentsTextWidthPx / d,
        )

        val markP = measurables[0].measure(Constraints.fixed(f.mark.w.px(), f.mark.h.px()))
        val titleP = measurables[1].measure(Constraints.fixed(f.title.w.px(), f.title.h.px()))
        val glyphP = measurables[3].measure(
            if (f.glyph != null) Constraints.fixed(f.glyph.w.px(), f.glyph.h.px()) else Constraints.fixed(0, 0),
        )
        val metaP = measurables[4].measure(Constraints.fixed(f.metaSlot.w.px(), f.metaSlot.h.px()))
        val agents = f.agents
        val agentsP = measurables[5].measure(
            if (agents != null) Constraints.fixed(agents.w.px(), agents.h.px()) else Constraints.fixed(0, 0),
        )

        layout(constraints.maxWidth, f.height.px()) {
            // Absolute place(), never placeRelative(): the iOS hand layout
            // is absolute and does not mirror in RTL.
            markP.place(f.mark.x.px(), f.mark.y.px())
            titleP.place(f.title.x.px(), f.title.y.px())
            // Right-aligned in the word frame (TextAlign.End's geometry),
            // vertically centred in titleH.
            wordP.place(
                f.word.x.px() + (f.word.w.px() - wordP.width),
                f.word.y.px() + (f.word.h.px() - wordP.height) / 2,
            )
            if (f.glyph != null) {
                glyphP.place(f.glyph.x.px(), f.glyph.y.px())
            } else {
                glyphP.place(0, 0)
            }
            metaP.place(f.metaSlot.x.px(), f.metaSlot.y.px())
            if (agents != null) agentsP.place(agents.x.px(), agents.y.px()) else agentsP.place(0, 0)
        }
    }
}

/**
 * The "● N" running-agents pill (android-next #06, upstream #899's
 * `SessionCell.agents`): the count in the working tone, centred on a
 * `controlFill` capsule the row's measure sizes (`ceil(text) + 12` ×
 * `R(18k)`). Decorative — the row's label speaks the count.
 */
@Composable
private fun RunningAgentsPill(text: String, style: TextStyle) {
    Box(
        Modifier.background(RobocoTheme.palette.controlFill, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = style.copy(textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
    }
}
