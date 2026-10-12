package sh.roboco.android.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * The hard line breaks UIKit's `numberOfLines = 1` renders up to
 * (`FadingLabel`, `EdgeFade.swift:28-29`): LF, CR, LINE SEPARATOR and
 * PARAGRAPH SEPARATOR. CR covers CRLF (the cut lands before both).
 */
private val HardLineBreaks = charArrayOf('\n', '\r', '\u2028', '\u2029')

/**
 * TalkBack reads the full original text, all lines included — the visual
 * clip and fade never affect semantics (UILabel parity). A helper function
 * so the `text` property never resolves against the composable's parameter.
 */
private fun SemanticsPropertyReceiver.semanticsText(full: AnnotatedString) {
    text = full
}

/**
 * A single-line label that fades its tail instead of truncating with "…"
 * (android-ui-parity #10), the port of iOS `FadingLabel`
 * (`ios:Design/EdgeFade.swift:22-57`).
 *
 * - One line, clipping; it never shows "…" and never wraps.
 * - **Fitting** text is placed per [fitsAlignment] (iOS `fitsAlignment`,
 *   default `.natural` = [Alignment.Start]; the session header passes
 *   [Alignment.CenterHorizontally]) and carries no mask at all.
 * - **Overflowing** text (wider than the slot by more than
 *   [EdgeFade.OverflowTolerance]) starts at the layout-direction start edge
 *   so the fade lands on its tail: the physical right edge fades in LTR,
 *   the left edge in RTL (`EdgeFade.swift:44-48`).
 * - Vertically centred in the slot; when the slot has no fixed height, the
 *   slot height is the single line's height.
 * - Size: the width is the text width clamped to the incoming constraints —
 *   a fixed-width slot keeps the slot width, a `widthIn(max = …)` slot hugs
 *   the text up to the max.
 * - The text colour, font and size come from the caller's [style];
 *   [FadingText] never chooses them. Inline content (the session row's
 *   project tile and branch icon) is measured and faded with the text.
 * - Fade changes are instant, never animated (`setDisableActions(true)`).
 */
@Composable
fun FadingText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fitsAlignment: Alignment.Horizontal = Alignment.Start,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    fadeWidth: Dp = EdgeFade.Width,
) {
    // `numberOfLines = 1` shows only the text before the first hard line
    // break, and this is the part the overflow verdict measures. Semantics
    // keep the whole original text (Comments).
    val shown = remember(text) {
        val i = text.text.indexOfAny(HardLineBreaks)
        if (i < 0) text else text.subSequence(0, i)
    }
    // Written during measure, read only in the layer/draw phases: a text or
    // bounds change re-evaluates the mask (layoutSubviews parity) without
    // recomposing.
    var overflows by remember { mutableStateOf(false) }
    Layout(
        content = {
            BasicText(
                text = shown,
                style = style,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                inlineContent = inlineContent,
            )
        },
        modifier = modifier
            .clearAndSetSemantics { semanticsText(text) }
            .clipToBounds()
            .horizontalFadeMask(
                masked = { overflows },
                fades = { dir ->
                    if (!overflows) {
                        null
                    } else {
                        val fw = fadeWidth.toPx()
                        if (dir == LayoutDirection.Rtl) HorizontalFades(fw, 0f) else HorizontalFades(0f, fw)
                    }
                },
            ),
        measurePolicy = MeasurePolicy { measurables, constraints ->
            // Unbounded width: `softWrap = false` alone still lets a bounded
            // max width clamp the paragraph, so measure the real single-line
            // width here and clip against the slot ourselves (Pitfalls).
            val p = measurables.single().measure(Constraints(maxHeight = constraints.maxHeight))
            val w = constraints.constrainWidth(p.width)
            val h = constraints.constrainHeight(p.height)
            val o = w > 0 && p.width > w + EdgeFade.OverflowTolerance.toPx()
            if (o != overflows) overflows = o
            // Overflowing text aligns to `.natural` — the start of the UI
            // layout direction (EdgeFade.swift:41-42). align(size, space):
            // the child width first, the slot second — Start returns
            // w − p.width (negative) for an overflowing RTL slot, which
            // puts the text's right edge flush with the slot's.
            val x = (if (o) Alignment.Start else fitsAlignment).align(p.width, w, layoutDirection)
            val y = (h - p.height) / 2
            // place(), NOT placeRelative(): x is already physical.
            layout(w, h) { p.place(x, y) }
        },
    )
}

@Composable
fun FadingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fitsAlignment: Alignment.Horizontal = Alignment.Start,
    fadeWidth: Dp = EdgeFade.Width,
) = FadingText(
    text = AnnotatedString(text),
    style = style,
    modifier = modifier,
    fitsAlignment = fitsAlignment,
    inlineContent = emptyMap(),
    fadeWidth = fadeWidth,
)
