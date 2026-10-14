package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalPalette
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import sh.roboco.android.design.formatElapsed
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.ColorRole

/**
 * The trailer's fixed geometry — iOS `WorkingIndicatorView.layoutSubviews`
 * (`ios:Transcript/RowView.swift:445-446`). These two values are hard
 * platform constants, like the 14 of the Swift grid frame: they do **not**
 * scale with [sh.roboco.android.design.TypeScale.bodyFactor] or the font
 * scale; only the label's font size does. The 22 works out to 8 dp of air
 * after the 14-dp glyph.
 */
internal object WorkingIndicatorDefaults {

    /** `grid.frame = CGRect(x: 0, y: (h − 14) / 2, width: 14, height: 14)`. */
    val GlyphSize = 14.dp

    /** `label.frame = CGRect(x: 22, …)` — the label's left edge. */
    val LabelX = 22.dp

    /**
     * `Fonts.ui(.sansMedium, UIFontMetrics(forTextStyle: .body)
     * .scaledValue(for: 13.5))` (`RowView.swift:415`) — body-scaled (ticket
     * 03's call-site table lists the site as "B").
     */
    const val LabelPt = 13.5f
}

/**
 * iOS `ToolStatusView` (`ios:Transcript/RowView.swift:375-399`): the tool
 * state mark. `running` takes precedence over `failed` — a running tool
 * always shows the 2×3 spinner (`StatusGlyph(.spinner)` filling the whole
 * widget rect), never the cross. When it is not running, the mark is a
 * centred 10-pt `xmark` in `danger` (failed) or `checkmark` in `tertiary`
 * (done), at the symbol's natural size — never scaled to the rect.
 *
 * Decorative: no pointer input, no semantics (iOS
 * `isUserInteractionEnabled = false`, and the view never joins
 * `accessibilityElements`). Taps fall through to the row host. The Rust
 * core declares `WidgetKind.ToolStatus` but does not emit it today; the
 * painter handles it anyway, like iOS.
 */
@Composable
fun ToolStatusWidget(running: Boolean, failed: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (running) {
            // The mini spinner: the glyph box is the whole widget rect —
            // the caller's fillMaxSize wins over ticket 11's 12-dp default.
            StatusGlyph(
                StatusGlyphKind.Spinner,
                Modifier.fillMaxSize().testTag("tool-status-spinner"),
            )
        } else {
            val palette = LocalPalette.current
            // 10-pt Bold, centred: `UIImage.SymbolConfiguration(pointSize:
            // 10, weight: .bold)` with `contentMode = .center`. The Icon
            // lays out the 12-dp box (10 × 1.2) and never clips the ink.
            Icon(
                icon = if (failed) RobocoIcons.Xmark else RobocoIcons.Checkmark,
                tint = if (failed) palette.danger else palette.tertiary,
                sizePt = 10f,
                weight = SymbolWeight.Bold,
                modifier = Modifier.testTag("tool-status-icon"),
                contentDescription = null,
            )
        }
    }
}

/**
 * iOS `case .spinner: StatusGlyph(.spinner)` (`RowView.swift:235-236`):
 * the trailing mark of a running subagent card. The glyph box is the whole
 * widget rect — the Rust rect is `d(px, 14) = 15.75 × 15.75` at text scale
 * 1 (`tools.rs:1108`), and the cells stay 3.5 dp, centred, unclipped.
 * Decorative, like [ToolStatusWidget].
 */
@Composable
fun SpinnerWidget(modifier: Modifier = Modifier) {
    StatusGlyph(StatusGlyphKind.Spinner, modifier.fillMaxSize().testTag("transcript-spinner"))
}

/**
 * iOS `WorkingIndicatorView` (`ios:Transcript/RowView.swift:401-448`): the
 * tail-of-turn trailer — the 3×3 pastel glyph, then "Working…" or
 * "Writing…" in `secondary` with the elapsed suffix ("  1m 15s") in
 * `tertiary`, all in one body-scaled Geist Medium line.
 *
 * - **Ticks are draw-phase only.** The label is measured and painted in a
 *   [drawWithCache] block, so a tick re-runs that block — one
 *   `TextMeasurer.measure` per second, served from the measurer's cache —
 *   and redraws. No recomposition, no remeasure of the widget, the row or
 *   the transcript: Rust keeps the row at a fixed 36 × bodyFactor height
 *   and is never asked to relayout (`mob:layout/display.rs:173-174`).
 * - **Ticker lifecycle.** iOS runs a repeating `Timer` while the view is
 *   in a window; the port runs `repeatOnLifecycle(STARTED)` while
 *   composed, resampling the clock at once on every (re)start. The first
 *   sample comes from composition (`tick()` in `init`), so the very first
 *   frame already shows the right seconds. No ticker when `sinceMs` is
 *   null — the label cannot change. iOS's quirk of not firing while the
 *   user scrolls is not ported (an invisible stall).
 * - **Silent for TalkBack** (decision TRANSCRIPT-D1): the iOS trailer is
 *   not in `accessibilityElements` and the row's text element is empty, so
 *   nothing is announced; the composer's Stop button signals the running
 *   turn. Ticket 129 clears the subtree from the row's tree.
 * - **`sinceMs` is epoch ms**: only ever compare it with
 *   [System.currentTimeMillis], never an uptime clock.
 *
 * [clock] is injectable so tests drive the time; production passes
 * [WallClock].
 */
@Composable
fun WorkingIndicator(
    sinceMs: Long?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    clock: () -> Long = WallClock,
) {
    // SideEffect, not a bare increment: the count must survive recompose
    // aborts and only count successful compositions.
    SideEffect { WorkingIndicatorProbe.compositions++ }

    val palette = LocalPalette.current
    val style = uiStyle(Face.SansMedium, LocalTypeScale.current.bodyScaled(WorkingIndicatorDefaults.LabelPt))
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val currentClock by rememberUpdatedState(clock)

    // The one piece of state: the clock sample, read ONLY by the draw
    // cache below. A tick invalidates the cache, nothing else.
    val ticker = remember { WorkingTicker(currentClock()) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sinceMs, lifecycle) {
        if (sinceMs == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                ticker.nowMs = currentClock()
                delay(Motion.StatusTickMs)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                WorkingIndicatorProbe.measures++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .testTag("transcript-working")
            .drawWithCache {
                // Snapshot reads live here: a nowMs write invalidates the
                // cache only — this block and the draw below re-run, never
                // composition or layout.
                val secs = elapsedSeconds(sinceMs, ticker.nowMs)
                val text = workingLabel(streaming, secs, palette.secondary, palette.tertiary)
                val textLayout = measurer.measure(text, style, softWrap = false, maxLines = 1)

                WorkingIndicatorProbe.labelBuilds++
                WorkingIndicatorProbe.lastLabel = text.text

                // iOS: label at x = 22, vertically centred at its natural
                // one-line height, unclipped (no maxWidth, no ellipsis).
                val left = WorkingIndicatorDefaults.LabelX.toPx()
                val top = (size.height - textLayout.size.height) / 2f
                WorkingIndicatorProbe.lastLabelTopLeftPx = Offset(left, top)
                // The size the label was laid out at: through the measurer's
                // density (the composition's LocalDensity), not this
                // CacheDrawScope's — that one only carries density/fontScale
                // floats and re-derives the platform's non-linear sp curve,
                // so under a custom LocalDensity (a linear font-scale
                // override) it reports a size the text never used
                // (13.5 sp at 1.5 → 21.0 via the 1.5 table, vs the drawn 20.25).
                WorkingIndicatorProbe.lastLabelFontSizePx =
                    with(textLayout.layoutInput.density) { style.fontSize.toPx() }

                onDrawWithContent {
                    drawContent() // the trailer glyph child
                    drawText(textLayout, topLeft = Offset(left, top))
                }
            },
    ) {
        StatusGlyph(
            StatusGlyphKind.Trailer,
            Modifier
                .align(AbsoluteAlignment.CenterLeft)
                .size(WorkingIndicatorDefaults.GlyphSize)
                .testTag("transcript-working-glyph"),
        )
    }
}

/** The production clock: wall-clock epoch ms, like iOS `Date()`. */
internal val WallClock: () -> Long = { System.currentTimeMillis() }

/** `"Writing"` when the newest entry is streaming, else `"Working"`. */
internal fun workingWord(streaming: Boolean): String = if (streaming) "Writing" else "Working"

/**
 * Elapsed whole seconds: `max(0, Int(now − since))` with Long division
 * (truncates toward zero, exactly like Swift's `Int(Double)`), clamped at
 * [Int.MAX_VALUE]. `null` since → 0, like `since.map { … } ?? 0`.
 */
internal fun elapsedSeconds(sinceMs: Long?, nowMs: Long): Int =
    if (sinceMs == null) {
        0
    } else {
        maxOf(0L, (nowMs - sinceMs) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

/**
 * The trailer's label as an [AnnotatedString]: `Working…` / `Writing…`
 * (U+2026) in `secondary`, and — only when `secs > 0` — exactly two
 * U+0020 spaces and [formatElapsed] in `tertiary`, same face and size.
 */
internal fun workingLabel(streaming: Boolean, secs: Int, secondary: Color, tertiary: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = secondary)) {
            append(workingWord(streaming))
            append('…')
        }
        if (secs > 0) {
            withStyle(SpanStyle(color = tertiary)) {
                append("  ")
                append(formatElapsed(secs))
            }
        }
    }

/** The clock sample holder for [WorkingIndicator] — one Long of snapshot state. */
@Stable
internal class WorkingTicker(initialNowMs: Long) {
    var nowMs by mutableLongStateOf(initialNowMs)
}

/**
 * Test hooks (debug-safe, trivial cost): plain fields, no snapshot state,
 * so the probe itself never causes invalidation. Reset with [reset]
 * before each test.
 */
@VisibleForTesting
internal object WorkingIndicatorProbe {
    var compositions = 0
    var measures = 0
    var labelBuilds = 0
    var lastLabel: String = ""
    var lastLabelTopLeftPx: Offset = Offset.Zero
    var lastLabelFontSizePx: Float = 0f

    fun reset() {
        compositions = 0
        measures = 0
        labelBuilds = 0
        lastLabel = ""
        lastLabelTopLeftPx = Offset.Zero
        lastLabelFontSizePx = 0f
    }
}

// ---------------------------------------------------------------------------
// The fixture (previews, Roborazzi goldens, the gallery's static rows)
// ---------------------------------------------------------------------------

/**
 * The fixture's frozen clock: every [WorkingIndicator] here renders
 * deterministically — `Working…`, `Working…  1m 15s`, `Writing…  1h 1m`.
 */
internal const val FIXTURE_NOW = 1_760_000_000_000L

/**
 * The ticket's fixture column (gallery and screenshots): on
 * `palette.background`, 16-dp padding, rows 12 dp apart —
 *
 * 1. the three `ToolStatus` states in 15.75-dp boxes (running / done /
 *    failed — the Spinner's own rect size, since the core emits no
 *    ToolStatus today);
 * 2. a subagent-card mock (354 × 33.75, radius 10.125, `AGENT_CARD` fill
 *    + 1-px `AGENT_CARD_BORDER` hairline, 20.25 `AGENT_TILE` at x = 9)
 *    with the [SpinnerWidget] at the Rust rect (329.25, 9, 15.75, 15.75) —
 *    9 dp from the card's right edge;
 * 3. `WorkingIndicator(null, false)` → `Working…`;
 * 4. `WorkingIndicator(FIXTURE_NOW − 75 s, false)` → `Working…  1m 15s`;
 * 5. `WorkingIndicator(FIXTURE_NOW − 3 661 s, true)` → `Writing…  1h 1m`.
 *
 * Every Working row gets [nowMs] as its clock — [FIXTURE_NOW] by default.
 */
@Composable
internal fun StatusWidgetsFixture(nowMs: () -> Long = { FIXTURE_NOW }) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RobocoTheme.palette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(15.75.dp)) { ToolStatusWidget(running = true, failed = false) }
            Box(Modifier.size(15.75.dp)) { ToolStatusWidget(running = false, failed = false) }
            Box(Modifier.size(15.75.dp)) { ToolStatusWidget(running = false, failed = true) }
        }
        AgentCardMock()
        Box(Modifier.size(354.dp, 36.dp)) {
            WorkingIndicator(sinceMs = null, streaming = false, clock = nowMs)
        }
        Box(Modifier.size(354.dp, 36.dp)) {
            WorkingIndicator(sinceMs = FIXTURE_NOW - 75_000L, streaming = false, clock = nowMs)
        }
        Box(Modifier.size(354.dp, 36.dp)) {
            WorkingIndicator(sinceMs = FIXTURE_NOW - 3_661_000L, streaming = true, clock = nowMs)
        }
    }
}

/**
 * The subagent-card stand-in, from the Rust geometry (`tools.rs:1080-1112`):
 * 354 × 33.75 with `d(px, 9) = 10.125` circular corners (ticket 64's painter
 * draws circular arcs, so the mock matches the painted card), the
 * 20.25-dp `AGENT_TILE` at x = 9, and the running card's spinner — 15.75
 * square, `d(px, 8) = 9` inside the right edge, centred vertically.
 */
@Composable
private fun AgentCardMock() {
    val roles = TranscriptPalette(RobocoTheme.isDark)
    val cardShape = RoundedCornerShape(10.125.dp)
    Box(
        Modifier
            .size(354.dp, 33.75.dp)
            .clip(cardShape)
            .background(Color(roles.color(ColorRole.AGENT_CARD)))
            .hairlineBorder(Color(roles.color(ColorRole.AGENT_CARD_BORDER)), cardShape),
    ) {
        Box(
            Modifier
                .absoluteOffset(x = 9.dp, y = 6.75.dp)
                .size(20.25.dp)
                .background(Color(roles.color(ColorRole.AGENT_TILE)), RoundedCornerShape(5.625.dp)),
        )
        Box(
            Modifier
                .absoluteOffset(x = 329.25.dp, y = 9.dp)
                .size(15.75.dp),
        ) {
            SpinnerWidget()
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 6): the fixture, light and dark, frozen glyph clock
// nested inside the theme — the trailer never starts a frame loop here.
// ---------------------------------------------------------------------------

/** The preview body: the fixture at its own frozen clock, main-source only. */
@Composable
private fun StatusWidgetsPreviewColumn() {
    StatusWidgetsFixture(nowMs = { FIXTURE_NOW })
}

@Preview(name = "StatusWidgets light")
@Composable
private fun StatusWidgetsLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusWidgetsPreviewColumn() }
    }
}

@Preview(name = "StatusWidgets dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusWidgetsDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusWidgetsPreviewColumn() }
    }
}
