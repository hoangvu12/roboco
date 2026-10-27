package sh.roboco.android.transcript

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.LiftHighlight
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.contextMenu
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind
import kotlin.math.max

/**
 * The context a row's slots share (ticket 65): the state (offset, fonts,
 * engine toggles), the realized row (coordinates, fade), the row's kind
 * and model, the paint palette and the actions provider. [actions] is a
 * **provider**, not the actions: widgets call `ctx.actions()` at click
 * time, never at composition, so a screen can rebuild its callbacks
 * freely — except 66's image tile, which takes the actions as a value
 * (its synchronous cache peek must run during composition) and keeps
 * them fresh through `rememberUpdatedState`. [live] is "the row has a
 * `Shimmer` widget" — the tool rail (121) reads it.
 */
class RowContext(
    val state: TranscriptState,
    val row: RealizedRow,
    val kind: RowKind,
    val model: RowModel,
    val palette: IntArray,
    val actions: () -> TranscriptActions,
    val live: Boolean,
)

/**
 * One realized row (ticket 65): exactly **one** root layout node (the host
 * indexes measurables by placement order) — the row group of ticket 129's
 * semantics tree — its layer-0
 * canvas drawn behind everything at row-local origin, the fade-in applied
 * to the whole row through [Modifier.graphicsLayer], and the row's
 * scrollers and row-level widgets as children in iOS z-order (scrollers in
 * index order, then widgets in display order).
 *
 * 132 adds the Tools-row growth clip on the same layer: while a Tools row
 * has a live motion track it clips to its measured (animating) bounds, so
 * a growing group reveals top-down and nothing spills over the row below;
 * the painted content is never scaled (the row paints its model at
 * natural size from the row's top-left, iOS `contentMode = .topLeft`).
 * Every other row — and a Tools row at rest — never clips (glyph
 * overhangs, 65).
 *
 * 128 adds the row's long-press menu: [Modifier.contextMenu] on the same
 * root node (after the fade-in layer, before the layer-0 draw, so the
 * recorded preview contains everything the row paints but not the
 * fade-in alpha) and the [performer] shared by every row of one host.
 * 129 turns the root into a TalkBack **row group** (`isTraversalGroup` +
 * `CollectionItemInfo`, the `row-*` tag moved to the full-size text node
 * inside, the semantics long-click off — its custom actions replace it),
 * with the link nodes as the last children.
 */
@Composable
internal fun RowHost(
    state: TranscriptState,
    frame: LayoutFrame,
    p: RowPlacement,
    palette: IntArray,
    performer: RowMenuPerformer,
    actionsProvider: () -> TranscriptActions,
) {
    val d = LocalDensity.current.density

    // Composition replaces iOS's view pool: `key(p.key)` in the host
    // reuses this row when it stays realized and discards it (forgetting
    // the fade, scroll offsets and veil state) when it leaves the band.
    val row = remember(p.key) { RealizedRow(p.key, state, p).also { TranscriptPerf.viewsCreated++ } }

    // A never-seen key starts at alpha 0 and fades in; a known key (the
    // first non-empty frame pre-marked them) starts at 1 and never runs.
    val appear = remember(p.key) { Animatable(if (state.knownRows.claim(p.key)) 0f else 1f) }
    LaunchedEffect(p.key) { if (appear.value < 1f) appear.animateTo(1f, Motion.rowFadeIn()) }

    // 117: the model is resolved from the row's painted state and the
    // state's cache on every recomposition — a few comparisons, never
    // `remember(p.version)`, which would rebuild on every streaming
    // token and kill the upgrade path. The `upgradeTick` read first is
    // the subscription: an applied async upgrade recomposes this row,
    // and only it, and the row keeps painting its old model at the new
    // placement until the build lands.
    row.upgradeTick
    val model = state.resolveModel(row, frame, p)

    // Published in composition; equal writes do not invalidate.
    row.placement = p
    row.density = d
    row.model = model
    row.appear = appear

    // 118: the streaming veil. One instance per row key — the first model
    // it ever sees only becomes the diff baseline, so a re-realized row
    // shows its text fully. Chunks are recorded during composition, before
    // the new model is ever drawn (pr609 Transcript.kt:220-223): the
    // `remember(model)` block runs once per new model instance. Under
    // reduce motion nothing is recorded and what exists is dropped (iOS
    // RowView.swift:121 — the frame loop below is not Compose-animator
    // scaled, so the explicit gate is required).
    val reduced = LocalReduceMotion.current
    val veil = remember(p.key) { Veil() }
    val veilClock = remember(p.key) { mutableLongStateOf(0L) }
    if (model != null) remember(model) { veil.grow(model, animate = !reduced); Unit }
    // One frame loop for all chunks (each carries its own 220 ms clock):
    // while any is live, stamp unstarted ones and drop expired ones at
    // every frame tick, and write the tick's time into `veilClock` so the
    // draw below re-runs — read only there, never in composition. The
    // loop's last tick returns false and the final clock write redraws
    // once more with no live chunk: the plain Pass.All draw.
    LaunchedEffect(model) {
        while (veil.hasChunks) {
            val more = withFrameNanos { t -> veilClock.longValue = t; veil.tick(t) }
            if (!more) break
        }
    }
    row.veil = veil

    // 128: the last touch-down in this row (row-local px, NaN when no
    // finger is down) — the menu's "not on a control" rule reads it at
    // long-press time. `remember(p.key)` keeps one array per row key.
    val down = remember(p.key) { floatArrayOf(Float.NaN, Float.NaN) }

    Box(
        Modifier
            // 129: the row group — traversal group + collection item. The
            // `row-*` test tag moved to the full-size text node (a child
            // with identical bounds), so the tag's tests and touches keep
            // working; the row's custom actions live there too.
            .semantics {
                isTraversalGroup = true
                collectionItemInfo = CollectionItemInfo(p.index.toInt(), 1, 0, 1)
            }
            // 132: the test-only mirror of the layer clip below (the Tools
            // growth clip) — `RowMotionHostTest` reads it; TalkBack never
            // does. Reads `rowMotion.isAnimating`, which reads `version`:
            // a track appearing or ending re-applies semantics, never
            // recomposes the row.
            .semantics { this[RowClipKey] = p.kind == RowKind.TOOLS && state.rowMotion.isAnimating(p.key) }
            .graphicsLayer {
                alpha = appear.value // read in the layer phase
                // 132 (TLV:411-415): a Tools row clips to its animating
                // bounds while a track is live; the completion never comes
                // off early here (iOS clears it per animator). Everything
                // else stays unclipped, and the clip is never driven at
                // rest.
                clip = p.kind == RowKind.TOOLS && state.rowMotion.isAnimating(p.key)
                shape = RectangleShape
            }
            // 128: the row's context menu (16's gesture and lift, 15's
            // panel). The items are built here, at long-press time, never
            // cached in composition; a null menu (no model, a control
            // under the finger, or nothing to show) leaves the gesture
            // alone — no haptic, no lift, and the release is a normal tap.
            // 129: `semanticsLongClick = false` — the row container must
            // not be a TalkBack stop between the row text and its widgets.
            .contextMenu(LiftHighlight.TranscriptRow, semanticsLongClick = false) {
                val m = row.model ?: return@contextMenu null
                val x = down[0]
                if (!x.isNaN() && isOverControl(m.display, x / d, down[1] / d, row::scrollerOffsetDp)) {
                    return@contextMenu null
                }
                state.rowMenuActions(row.key).toMenu(performer::perform)
            }
            // On the same node as the context menu, so both see the same
            // row-local pixels. Observes only, never consumes.
            .trackRowDown(down)
            .drawBehind {
                // No style sync here: 61's apply synced the table and the
                // veil's first pass calls `fonts.useDensity` itself (62's
                // rule). 118: the veil composes the passes (Chrome,
                // settled and fresh Ranges, one bounded layer per live
                // chunk); `veilClock` is read here, in the draw phase,
                // so every veil frame redraws without recomposing.
                val m = model ?: return@drawBehind
                val now = veilClock.longValue
                drawIntoCanvas { c ->
                    veil.draw(c.nativeCanvas, m, d, palette, state.fonts, now)
                }
            },
    ) {
        // 129: the row's full-size text node — the z-order **bottom**, so
        // explore-by-touch reaches the widgets and links first (iOS puts
        // the text element last). Emitted even before the model resolves.
        RowTextNode(p.kind, model, frame, p.index, performer)
        if (model != null) {
            val ctx = RowContext(
                state = state,
                row = row,
                kind = p.kind,
                model = model,
                palette = palette,
                actions = actionsProvider,
                live = model.display.widgets.any { it.kind is WidgetKind.Shimmer },
            )
            // Ticket 116: key(i) keeps scroller i's ScrollState across a
            // change in the scroller count, so a streamed second code block
            // cannot shift the first one's identity (and offset).
            model.display.scrollers.forEachIndexed { i, s -> key(i) { RowScroller(ctx, i, s) } }
            val n = model.display.scrollers.size
            for (w in model.display.widgets) {
                // A bad scroller index falls back to the row (iOS's rule;
                // Rust never emits either case).
                val sc = w.scroller?.toInt()
                if (sc == null || sc >= n) WidgetBox(ctx, w)
            }
            // 129: the link nodes — the z-order **top**, one per painted
            // fragment, positioned (and repositioned) by their scrollers.
            RowLinkNodes(row, model.display, actionsProvider)
        }
    }
}

/**
 * The row identifier of a kind (iOS `RowView.swift:146-153`). Ticket 129
 * moved the tag from the row's root Box to its full-size text node (the
 * same bounds and count, so these tags' tests keep passing).
 */
internal fun rowTag(kind: RowKind): String = when (kind) {
    RowKind.MARKDOWN -> "row-markdown"
    RowKind.USER -> "row-user"
    RowKind.TOOLS -> "row-tools"
    RowKind.CHIP -> "row-chip"
    RowKind.NOTICE -> "row-notice"
    RowKind.IMAGE -> "row-image"
    RowKind.WORKING -> "row-working"
}

/**
 * Test-only semantics: whether the row's layer clips (ticket 132's
 * Tools-row growth clip). Not read by TalkBack — `RowMotionHostTest` is
 * the only consumer, like 120's `ChevronAngleKey`.
 */
internal val RowClipKey = SemanticsPropertyKey<Boolean>("RowClip")

/**
 * A widget's slot (ticket 65): the rect `(w.x, w.y, max(w.w, 0),
 * max(w.h, 0))` dp at `absoluteOffset` (Rust x is left-origin regardless
 * of layout direction), filled by its owner's composable. The host is the
 * scroller's content box when `w.scroller` points at one (its content
 * coordinates), else the row (row coordinates).
 *
 * Ticket 81: the widget rect of a **control** kind carries the
 * [transcriptControl] marker — the rect itself is the port of the iOS
 * `UIControl` (its view's frame is the rect), so a tap whose down lands
 * there claims the gesture and never dismisses the composer. Non-control
 * kinds (image tiles, chevrons, painted glyphs, the rail, the shimmer)
 * stay unmarked: their taps dismiss **and** do their own thing.
 */
@Composable
internal fun WidgetBox(ctx: RowContext, w: Widget) {
    // 129: the debug bounds overlay outlines exposed widget groups in
    // `Palette.danger` — read in composition, drawn only in the a11y
    // gallery (never in production).
    val outline = LocalTranscriptA11yBounds.current && w.kind.isControl()
    Box(
        Modifier
            .absoluteOffset(w.x.dp, w.y.dp)
            .size(max(w.w, 0f).dp, max(w.h, 0f).dp)
            .then(if (w.kind.isTranscriptControl) Modifier.transcriptControl() else Modifier)
            // 129: exposed kinds group at 0f around the widget's own node;
            // the hidden kinds are cleared from the tree entirely.
            .widgetSemantics(w.kind)
            .then(
                if (outline) {
                    val danger = RobocoTheme.palette.danger // #DC2626 light / #F87171 dark
                    Modifier.drawBehind { drawRect(danger, style = Stroke(1f)) }
                } else {
                    Modifier
                },
            ),
    ) {
        RowWidget(ctx, w)
    }
}
