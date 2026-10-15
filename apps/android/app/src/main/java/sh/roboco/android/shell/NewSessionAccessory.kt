package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.uiStyle

/**
 * The accessory's geometry, exported for the tickets that place it
 * (android-ui-parity #27). Every value is iOS-verbatim from
 * `ios:Shell/MainTabController.swift:252-296` except the three the system
 * container (`UITabAccessory`) owns, which come from the reference
 * screenshot `1-off.png` (412×54 px capsule at ≈1.147 px/pt, 10 px gap
 * above the pill) — side-by-side tuning values, per the ticket's Comments.
 */
object NewSessionAccessoryMetrics {

    /** The capsule height (measured 47 pt; `UITabAccessory` nominal 48). */
    val Height = 48.dp

    /**
     * The preview/fixture margin: 21 from the screen edges at 402 dp —
     * ticket 26's [GlassTabBarDefaults.SideMargin]. In the shell the capsule
     * takes ticket 28's row box (rowL/rowW: 398 cap, centred), not this
     * margin.
     */
    val SideMargin = GlassTabBarDefaults.SideMargin

    /** Expanded: capsule bottom to the tab pill's top (ticket 30 applies). */
    val GapAbovePill = 8.dp

    /** The lavender circle: 34 × 34, radius 17 (`:252-256, 275-278`). */
    val PlateSize = 34.dp

    /** The plate's leading inset from the capsule start (`:275`). */
    val PlateInset = 7.dp

    /** The label's start = plate end + 11 (`:281`), 52 from the capsule start. */
    val LabelGap = 11.dp

    /** The label's end limit: ≥ 10 before the glyph slot's start (`:294-296`). */
    val LabelEndGap = 10.dp

    /** The glyph slot, 12 × 12, always reserved (`:285-288`). */
    val GlyphSize = 12.dp

    /** The glyph slot's end is 7 before the summary's start (`:285`). */
    val GlyphGap = 7.dp

    /** The summary's end: 16 before the capsule end (`:283`). */
    val SummaryEnd = 16.dp

    /** Pressed content alpha (`PressableControl`, `ios:Voice/VoiceControls.swift:142-147`). */
    const val PressedAlpha = 0.6f

    /** The press dim's duration: `UIView.animate(withDuration: 0.15)`. */
    const val PressMillis = 150

    /** The iOS `accessibilityIdentifier` (`MainTabController.swift:233`). */
    const val TestTag = "new-session"
}

/**
 * The tab bar's bottom accessory — the port of `AskAnythingAccessory`
 * (`ios:Shell/MainTabController.swift:224-317`): a full-width glass capsule
 * with a lavender plate and violet plus, the words "New session", and a
 * live summary of what is running ("2 working · 1 needs you"). The whole
 * 48 dp capsule is one button that opens the New Session page sheet
 * (tickets 30/32 wire [onTap]).
 *
 * Where it sits, when it is visible and how it fades are ticket 30's
 * (this composable only exposes the [contentAlpha] hook and never leaves
 * composition to hide — upstream #577). Ticket 28 owns the minimized row:
 * it passes [inline] and, if it wants the label to follow its morph
 * progress instead of snapping, [labelAlpha]. Ticket 26 owns the tab pill
 * under it.
 *
 * Voice is dropped (android-app#19 D4): with no `VoiceAccessoryView`, the
 * iOS `restConstraints` always apply — the button spans the capsule, and
 * the summary and glyph are never hidden for a call.
 *
 * The press is two effects, exactly as on iOS: the **content** dims to
 * [NewSessionAccessoryMetrics.PressedAlpha] over 150 ms
 * (`PressableControl`), while the **glass** does not dim — it swells to
 * `Glass.PressScale` with the white highlight over the fill, ticket 07's
 * `interactive = true` response through the shared [MutableInteractionSource].
 *
 * The modifier order on the capsule is deliberate:
 * `testTag → clickable → clearAndSetSemantics` — one merged semantics node
 * carries the button role, the [LiveCounts.accessibilityLabel] and the
 * click action, while the label and summary texts are hidden from
 * accessibility (current Compose semantics keep every property set on the
 * same layout node and clear only the descendants). Note this also merges
 * ticket 07's glass test keys out of the merged tree — a test that wants
 * `GlassProgressKey`/`GlassPressScaleKey` near an accessory must query the
 * unmerged tree.
 *
 * @param counts the live summary; `LiveCounts()` draws no glyph and no summary
 * @param onTap the capsule tap (iOS `init(onTap:)`)
 * @param modifier ticket 30's width/offset/positioning; the capsule always
 *   applies its 48 dp height and fills the width
 * @param inline true in the minimized tab bar (ticket 28): the label is
 *   laid out but drawn at alpha 0, like the `UITraitTabAccessoryEnvironment`
 *   inline trait (`MainTabController.swift:308-312`)
 * @param labelAlpha ticket 28's per-frame label alpha (its minimize
 *   progress); null snaps with [inline]. A lambda read in the draw phase,
 *   so per-frame progress never recomposes the row
 * @param contentAlpha ticket 30's content fade (the after-Search reveal,
 *   the pop-progress reveal); read in the draw phase, so per-frame
 *   progress never recomposes the row. Fades the content only — the glass
 *   stays opaque (iOS dims the control, not the `UITabAccessory`)
 * @param enabled ticket 29: false while the accessory is faded under the
 *   IME (alpha < 0.01), so taps reach the results list
 */
@Composable
fun NewSessionAccessory(
    counts: LiveCounts,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    labelAlpha: (() -> Float)? = null,
    contentAlpha: () -> Float = { 1f },
    enabled: Boolean = true,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()

    // PressableControl's dim (Voice/VoiceControls.swift:142-147): alpha
    // 1 → 0.6 over 0.15 s, `UIView.animate`'s default curveEaseInOut.
    // An Animatable (the ticket's `.value` read), like GlassSurface's own
    // press: the draw phase reads it, so the 150 ms dim never recomposes
    // the row. Not gated by Reduce Motion — an opacity change iOS does
    // not gate either.
    val pressAlpha = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        pressAlpha.animateTo(
            targetValue = if (pressed) NewSessionAccessoryMetrics.PressedAlpha else 1f,
            animationSpec = tween(
                durationMillis = NewSessionAccessoryMetrics.PressMillis,
                easing = Motion.EaseInOut,
            ),
        )
    }

    val summary = remember(counts) { counts.summaryText() }
    val label = remember(counts) { counts.accessibilityLabel() }
    val glyph = counts.glyph()

    GlassSurface(
        modifier = modifier
            .height(NewSessionAccessoryMetrics.Height)
            .fillMaxWidth()
            .testTag(NewSessionAccessoryMetrics.TestTag)
            .clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onTap,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        shape = CapsuleShape,
        interactive = true,
        interactionSource = src,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                // Content-only fade: the press dim and ticket 30's hook
                // multiply in the draw phase; the glass never fades here.
                .graphicsLayer { alpha = contentAlpha() * pressAlpha.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(NewSessionAccessoryMetrics.PlateInset))
            Box(
                Modifier
                    .size(NewSessionAccessoryMetrics.PlateSize)
                    .background(palette.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // SF `plus`, point size 15, weight semibold, tint accent
                // (MainTabController.swift:234, 250). Ticket 06's 18 dp box.
                Icon(
                    RobocoIcons.Plus,
                    tint = palette.accent,
                    sizePt = 15f,
                    weight = SymbolWeight.Semibold,
                )
            }
            Spacer(Modifier.width(NewSessionAccessoryMetrics.LabelGap))
            BasicText(
                text = "New session",
                modifier = Modifier
                    // weight(1f) reproduces the iOS compression
                    // priorities: the label (`.defaultHigh` end limit)
                    // truncates first; the summary (`.required`) never
                    // shrinks and always gets its intrinsic width.
                    .weight(1f)
                    .graphicsLayer { alpha = labelAlpha?.invoke() ?: if (inline) 0f else 1f },
                style = uiStyle(Face.SansMedium, ts.fixed(16f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(NewSessionAccessoryMetrics.LabelEndGap))
            Box(
                Modifier.size(NewSessionAccessoryMetrics.GlyphSize),
                contentAlignment = Alignment.Center,
            ) {
                // The 12 dp slot stays even with no glyph — Auto Layout
                // keeps a hidden view's constraints, so the label's end
                // limit always counts it (MainTabController.swift:285-288,
                // the ticket's Comments). Never compose StatusGlyph while
                // hidden: a hidden spinner must not hold ticket 11's clock.
                glyph?.let { kind ->
                    StatusGlyph(
                        if (kind == LiveGlyph.Spinner) StatusGlyphKind.Spinner
                        else StatusGlyphKind.Dot(palette.status.input),
                        Modifier.size(NewSessionAccessoryMetrics.GlyphSize),
                    )
                }
            }
            Spacer(Modifier.width(NewSessionAccessoryMetrics.GlyphGap))
            // The summary swaps instantly (iOS sets `summary.text` with no
            // animation) and never truncates or wraps.
            BasicText(
                text = summary,
                style = uiStyle(Face.SansMedium, ts.fixed(13f), palette.secondary)
                    .copy(textAlign = TextAlign.End),
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(NewSessionAccessoryMetrics.SummaryEnd))
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 6): the capsule padded 21 dp over the page background at
// 402 dp, for the idle, awaiting and both states; the inline preview at
// 200 dp shows the label hidden while plate, glyph and summary stay.
// ---------------------------------------------------------------------------

/** The page behind the previews: `palette.background` with 21 dp side margins. */
@Composable
private fun AccessoryPreviewPage(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(RobocoTheme.palette.background)
            .padding(horizontal = NewSessionAccessoryMetrics.SideMargin),
    ) {
        content()
    }
}

@Composable
private fun NewSessionAccessoryPreviewColumn() {
    AccessoryPreviewPage {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NewSessionAccessory(counts = LiveCounts(), onTap = {})
            NewSessionAccessory(counts = LiveCounts(awaiting = 1), onTap = {})
            NewSessionAccessory(counts = LiveCounts(working = 2, awaiting = 1), onTap = {})
        }
    }
}

@Preview(name = "Accessory – Light", widthDp = 402, heightDp = 260)
@Composable
private fun NewSessionAccessoryLightPreview() {
    RobocoTheme(Appearance.System) {
        NewSessionAccessoryPreviewColumn()
    }
}

@Preview(name = "Accessory – Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 402, heightDp = 260)
@Composable
private fun NewSessionAccessoryDarkPreview() {
    RobocoTheme(Appearance.System) {
        NewSessionAccessoryPreviewColumn()
    }
}

@Preview(name = "Accessory – Inline", widthDp = 200, heightDp = 120)
@Composable
private fun NewSessionAccessoryInlinePreview() {
    RobocoTheme(Appearance.System) {
        AccessoryPreviewPage {
            NewSessionAccessory(counts = LiveCounts(awaiting = 1), onTap = {}, inline = true)
        }
    }
}
