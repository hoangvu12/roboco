package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/** The connection line's fixed geometry and pressed-fill constants (dp; the line height itself scales with k). */
private object ConnectionLineTokens {
    /** `ts.scaledDp(32f)`: 32 at k = 1, 27 at 0.85, 51 at 1.6 — one line, overflow line included. */
    const val LineHeightPt = 32f

    /** The 12 × 12 glyph slot's left edge — fixed, never scaled by k. */
    val GlyphLeft = 20.dp

    /** The caption's left edge: 20 + 12 + 8. */
    val CaptionLeft = 40.dp

    /** The caption's right margin (the frame is `W − 40 − 20`). */
    val CaptionRight = 20.dp

    /** The glyph slot's size — fixed like the header's 12 dp live glyph. */
    val GlyphSize = 12.dp

    /** The pressed fill's leading/trailing inset. */
    val FillHorizontalInset = 8.dp

    /** The pressed fill's top/bottom inset (2 from the item top). */
    val FillVerticalInset = 2.dp

    /** The pressed fill's continuous corner radius. */
    val FillCornerRadius = 12.dp

    /** The entrance rise: the item settles 4 dp up into place (desktop `motion::fade_in`). */
    val EntranceRise = 4.dp
}

/**
 * The multi-engine connection line (android-ui-parity #50) — the list
 * item placed directly under the large title "Sessions", before the
 * first section header. It is **absent** while every paired engine is
 * `Connected`; otherwise one 32 dp line per degraded engine: a 12 dp
 * glyph (the violet spinner, or a coloured dot) and a grey caption such
 * as "Reconnecting…" or "Studio — Session revoked". Tapping it opens
 * Settings (the Devices section lists the engines, ticket 102).
 *
 * The look is the Roboco desktop and web "connection line" — a bare
 * glyph and a quiet caption with **no surface and no border**
 * (`connection-pill.tsx:6-51`, `shell.rs:7110-7163`); the metrics are
 * this app's own (12 dp `StatusGlyph`s, Geist Medium 13, the list's
 * type scale — the web pill uses 11 px text and a 5 px dot).
 *
 * - One **merged** semantics node: a Button whose label joins the
 *   captions, a Polite live region (TalkBack announces a new engine
 *   going down) and the `connection-line` test tag. The glyphs are
 *   decorative (ticket 11).
 * - Pressed: a `controlFill` continuous rounded fill over the **whole**
 *   item — no fade in or out, no ripple (there is no indication).
 * - Entrance: one 500 ms ease-out-expo fade with a 4 dp rise, in the
 *   layer only — the layout slot never moves. Removal is instant
 *   (hide-fast, `connectivity.rs:8`); a label change inside a shown
 *   item swaps text without re-running the fade. Under Reduce Motion
 *   the entrance is skipped (alpha starts at 1) and the spinner holds
 *   its static alphas (ticket 11).
 * - Everything is placed physical (left-origin): like the other
 *   hand-laid rows (37, 39), the line does not mirror for RTL.
 *
 * @param ui never empty (a null [ConnectionLineUi] means the item is
 *   not emitted at all — [connectionLineItem] decides).
 */
@Composable
fun ConnectionLine(ui: ConnectionLineUi, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val reduce = LocalReduceMotion.current
    val style = uiStyle(Face.SansMedium, ts.scaled(13f), palette.secondary)
    val h = ts.scaledDp(ConnectionLineTokens.LineHeightPt)
    // One entrance per composition of the item: a label change keeps the
    // value (and the `LaunchedEffect(Unit)`), so the fade never restarts.
    val entrance = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) entrance.animateTo(1f, Motion.connectionLineIn())
    }
    val src = remember { MutableInteractionSource() }
    // The State itself, deliberately not `by`: the draw phase reads
    // `.value`, so a press change redraws without recomposing (and the
    // spec pins the read to the draw lambdas).
    val pressed = src.collectIsPressedAsState()
    val fillShape = rememberSmoothRoundedShape(ConnectionLineTokens.FillCornerRadius)
    // The semantics onClick survives recomposition with the latest tap
    // without re-running the semantics update; the clickable below gets
    // the latest lambda from the composition anyway.
    val onTapLatest by rememberUpdatedState(onTap)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Draw-phase read: the fade re-layers, never recomposes.
                // The layout slot never moves — only the layer does.
                val t = entrance.value
                alpha = t
                translationY = ConnectionLineTokens.EntranceRise.toPx() * (1f - t)
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = ui.entries.joinToString(", ") { it.caption }
                liveRegion = LiveRegionMode.Polite
                testTag = "connection-line"
                onClick {
                    onTapLatest()
                    true
                }
            }
            .drawWithCache {
                // One pressed-fill outline per size: the item's bounds
                // inset 8 left/right, 2 top/bottom, 12 dp continuous
                // corners — one fill for the whole item.
                val l = ConnectionLineTokens.FillHorizontalInset.toPx()
                val t = ConnectionLineTokens.FillVerticalInset.toPx()
                val outline = fillShape.createOutline(
                    Size(size.width - 2f * l, size.height - 2f * t),
                    layoutDirection,
                    this,
                )
                onDrawBehind {
                    // Draw-phase read: the press appears and vanishes at
                    // once, with no ripple (there is no indication).
                    if (pressed.value) {
                        translate(l, t) { drawOutline(outline, palette.controlFill) }
                    }
                }
            }
            .clickable(interactionSource = src, indication = null, onClick = onTap),
    ) {
        ui.entries.forEach { e ->
            Box(Modifier.fillMaxWidth().height(h)) {
                if (e.glyph != null) {
                    // Never clipped: the spinner overhangs 0.75 dp top and
                    // bottom (ticket 11), like the header's live glyph.
                    Box(
                        Modifier
                            .absoluteOffset(
                                x = ConnectionLineTokens.GlyphLeft,
                                y = (h - ConnectionLineTokens.GlyphSize) / 2,
                            )
                            .size(ConnectionLineTokens.GlyphSize)
                            .testTag("connection-line-glyph"),
                    ) {
                        StatusGlyph(
                            when (e.glyph) {
                                ConnectionGlyph.Spinner -> StatusGlyphKind.Spinner
                                ConnectionGlyph.DangerDot -> StatusGlyphKind.Dot(palette.danger)
                                ConnectionGlyph.WarningDot -> StatusGlyphKind.Dot(palette.warning)
                            },
                        )
                    }
                }
                // One line, no ellipsis, a trailing 24 dp fade on overflow
                // (ticket 10): the long INCOMPATIBLE sentence fades out
                // with no "…" added. Vertically centred in the line.
                FadingText(
                    e.caption,
                    style,
                    Modifier
                        .fillMaxSize()
                        .absolutePadding(
                            left = ConnectionLineTokens.CaptionLeft,
                            right = ConnectionLineTokens.CaptionRight,
                        )
                        .testTag("connection-line-caption"),
                )
            }
        }
    }
}

/**
 * Emits the connection line as the front page's **first** item —
 * directly under the large title (the scaffold emits the title itself
 * before `content`), before 41's section items. Emits nothing while
 * [ui] is null (every engine `Connected`): no item, no
 * `"connection-line"` key, the item count unchanged.
 *
 * The item key is stable (`"connection-line"`), so the entrance fade
 * runs once per first composition, and a label change keeps the
 * composition. `animateItem`'s fades are off — the entrance is the
 * item's own 500 ms fade (leaving 41's default fade-in on would double
 * it) and the removal is instant (hide-fast); the placement spec is
 * 41's own [Motion.ListItemPlacement], and its reduce-motion rule
 * applies: items jump into place instead.
 */
fun LazyListScope.connectionLineItem(ui: ConnectionLineUi?, onTap: () -> Unit) {
    if (ui == null) return
    item(key = "connection-line", contentType = "connectionLine") {
        val placement =
            if (LocalReduceMotion.current) null else Motion.ListItemPlacement
        ConnectionLine(
            ui,
            onTap,
            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement),
        )
    }
}

// ---------------------------------------------------------------------------
// Previews: the ticket's three fixture shapes — one Reconnecting line, a
// two-engine mixed item and the device-offline line, light and dark.
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionLinePreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        // Frozen at 0 ns: the spinner rows render the cycle-0 pattern and
        // the preview never starts a frame loop.
        ProvideGlyphClock(frozenAtNanos = 0L) {
            Column(Modifier.background(RobocoTheme.palette.background)) {
                ConnectionLine(
                    ConnectionLineUi(listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Reconnecting…"))),
                    onTap = {},
                )
                ConnectionLine(
                    ConnectionLineUi(
                        listOf(
                            ConnectionLineEntry(ConnectionGlyph.DangerDot, "Studio — Session revoked"),
                            ConnectionLineEntry(ConnectionGlyph.Spinner, "Laptop — Reconnecting…"),
                        ),
                    ),
                    onTap = {},
                )
                ConnectionLine(
                    ConnectionLineUi(listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline"))),
                    onTap = {},
                )
            }
        }
    }
}

@Preview(name = "Connection line – light", widthDp = 402, heightDp = 420)
@Composable
private fun ConnectionLineLightPreview() {
    ConnectionLinePreviewBody(Appearance.Light)
}

@Preview(name = "Connection line – dark", widthDp = 402, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectionLineDarkPreview() {
    ConnectionLinePreviewBody(Appearance.Dark)
}
