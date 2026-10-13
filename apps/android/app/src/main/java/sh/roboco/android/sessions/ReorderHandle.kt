package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.hairlineDp
import sh.roboco.android.design.rememberSmoothRoundedShape

// ---------------------------------------------------------------------------
// The drag handle slot (47)
// ---------------------------------------------------------------------------

/**
 * The Edit-mode drag handle slot (47) — the port of UIKit's reorder
 * accessory (`UICellAccessory.reorder(displayed: .whenEditing)`,
 * `SessionListController.swift:61-66`): a trailing [EditMetrics.SlotWidth]
 * slot whose end is [EditMetrics.SlotEndPadding] from the row's end, the
 * `line.3.horizontal` glyph centred in `Palette.tertiary`, and (unless
 * disabled in [EditMetrics]) a hairline separator at the slot's leading
 * edge.
 *
 * [visible] drives the animated width (`0 ↔ 52` dp, the row's content
 * re-flows with it) and the glyph + separator alpha, both
 * [EditMetrics.EditToggleMs] with [Motion.EaseInOut], snapped under
 * reduce motion; [enabled] gates the drag itself (off while committing).
 * The box is clipped to its (animating) bounds. The slot carries no
 * pointer input of its own besides [ReorderableCollectionItemScope.draggableHandle]
 * on the handle box, which starts the drag on **press** — no long-press
 * delay, like the UIKit reorder control.
 *
 * @param itemScope the enclosing [ReorderableCollectionItemScope] —
 *   `draggableHandle` is a member of it, so it is passed down from the
 *   `ReorderableItem` content (the library's own documented pattern)
 * @param id the row's chat id — the handle's `testTag` is `reorder-<id>`
 * @param onDragStarted `state.start(id)`; [onDragStopped] ends the drag
 *   and dispatches the move (the caller owns both)
 */
@Composable
internal fun ReorderHandleSlot(
    itemScope: ReorderableCollectionItemScope,
    id: String,
    visible: Boolean,
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val reduce = LocalReduceMotion.current
    val widthSpec: AnimationSpec<Dp> =
        if (reduce) snap() else tween(EditMetrics.EditToggleMs, easing = Motion.EaseInOut)
    val alphaSpec: AnimationSpec<Float> =
        if (reduce) snap() else tween(EditMetrics.EditToggleMs, easing = Motion.EaseInOut)
    val width by animateDpAsState(
        targetValue = if (visible) EditMetrics.AccessoryInset else 0.dp,
        animationSpec = widthSpec,
        label = "pinned-edit-slot-width",
    )
    // Read through the delegate in the draw / layer phases below, so the
    // 300 ms toggle never recomposes the row.
    val slotAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = alphaSpec,
        label = "pinned-edit-slot-alpha",
    )
    val hairline = palette.hairline
    val separator = if (EditMetrics.ShowVerticalSeparator) {
        Modifier.drawBehind {
            val a = slotAlpha
            if (a <= 0f) return@drawBehind
            val top = EditMetrics.SeparatorInsetV.toPx()
            val stroke = hairlineDp().toPx()
            drawLine(
                color = hairline,
                start = Offset(stroke / 2f, top),
                end = Offset(stroke / 2f, size.height - top),
                strokeWidth = stroke,
                alpha = a,
            )
        }
    } else {
        Modifier
    }
    Row(
        modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .then(separator),
    ) {
        // The handle box: the glyph centred, the drag on press. The
        // library gates the modifier itself (a second pointer only ever
        // continues the running drag).
        val handle = with(itemScope) {
            Modifier.draggableHandle(
                enabled = enabled,
                onDragStarted = { onDragStarted() },
                onDragStopped = { onDragStopped() },
            )
        }
        Box(
            handle
                .width(EditMetrics.SlotWidth)
                .fillMaxHeight()
                .graphicsLayer { this.alpha = slotAlpha }
                .then(
                    if (visible) {
                        Modifier
                            .semantics { contentDescription = "Reorder" }
                            .testTag("reorder-$id")
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RobocoIcons.Line3Horizontal, palette.tertiary, EditMetrics.GlyphPt, SymbolWeight.Regular)
        }
        Spacer(Modifier.width(EditMetrics.SlotEndPadding))
    }
}

// ---------------------------------------------------------------------------
// The lifted look (47)
// ---------------------------------------------------------------------------

/**
 * The lifted dragged row's look (47): behind the content an opaque
 * `Palette.elevated` fill in the `SessionCell` background geometry —
 * [SmoothRoundedShape] of [EditMetrics.LiftFillRadius], inset
 * [EditMetrics.LiftInsetHorizontal] / [EditMetrics.LiftInsetVertical]
 * (`ios:Threads/SessionCell.swift:82-83`) — plus a soft shadow, both
 * scaled by [progress] (0 = the resting row: no fill, no shadow). Without
 * the opaque fill the rows below would show through the dragged text.
 *
 * `progress` is read in the draw and layer phases only; the lift-in /
 * lift-out tweens and the library's settle spring run concurrently.
 */
@Composable
internal fun Modifier.liftedRow(progress: () -> Float): Modifier {
    val palette = RobocoTheme.palette
    val fillShape = rememberSmoothRoundedShape(EditMetrics.LiftFillRadius)
    val layerShape = remember {
        LiftedInsetShape(EditMetrics.LiftFillRadius, EditMetrics.LiftInsetHorizontal, EditMetrics.LiftInsetVertical)
    }
    return this
        .graphicsLayer {
            shadowElevation = EditMetrics.LiftElevation.toPx() * progress()
            shape = layerShape
            clip = false
        }
        .drawWithCache {
            val l = EditMetrics.LiftInsetHorizontal.toPx()
            val t = EditMetrics.LiftInsetVertical.toPx()
            val outline = fillShape.createOutline(
                Size(size.width - 2f * l, size.height - 2f * t),
                layoutDirection,
                this,
            )
            onDrawBehind {
                val p = progress().coerceIn(0f, 1f)
                if (p > 0f) translate(l, t) { drawOutline(outline, palette.elevated, alpha = p) }
            }
        }
}

/**
 * [SmoothRoundedShape] of [radius], for the rect inset [horizontal] /
 * [vertical] — the lift's fill and shadow geometry. The outline is the
 * base shape's for the inset size, drawn translated by the insets.
 */
private class LiftedInsetShape(
    private val radius: Dp,
    private val horizontal: Dp,
    private val vertical: Dp,
) : Shape {
    private val base = SmoothRoundedShape(radius)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = with(density) { horizontal.toPx() }
        val t = with(density) { vertical.toPx() }
        val inner = base.createOutline(
            Size(size.width - 2f * l, size.height - 2f * t),
            layoutDirection,
            density,
        )
        // The base outline sits at the origin; move it by the insets so the
        // shadow lines up with the fill (which `liftedRow` translates too).
        return Outline.Generic(Path().apply { addOutline(inner); translate(Offset(l, t)) })
    }
}

// ---------------------------------------------------------------------------
// The lifted-row sample: previews and the Roborazzi golden
// ---------------------------------------------------------------------------

/**
 * A session row shown fully lifted (progress = 1) — the preview (b) of
 * ticket 47 and the `pinned_row_lifted_light.png` golden's body: the row
 * at its fixed height (`R(62k)`, `SessionCell.swift:49`), the content
 * narrowed by the accessory, the opaque fill and the shadow.
 *
 * The [ReorderableCollectionItemScope] stand-in disables the drag (the
 * sample is static); the real scope comes from the `ReorderableItem`
 * content in `sessionListItems`.
 */
@Composable
internal fun LiftedRowSample(vm: SessionRowVM, modifier: Modifier = Modifier) {
    val ts = LocalTypeScale.current
    val itemScope = remember { NoDragItemScope }
    Row(modifier.fillMaxWidth().height(ts.scaledDp(62f)).liftedRow(progress = { 1f })) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            SessionRow(vm, modifier = Modifier.fillMaxSize())
        }
        ReorderHandleSlot(
            itemScope = itemScope,
            id = vm.id,
            visible = true,
            enabled = false,
            onDragStarted = {},
            onDragStopped = {},
        )
    }
}

/** The sample's scope: `draggableHandle` without the drag. */
private object NoDragItemScope : ReorderableCollectionItemScope {
    override fun Modifier.draggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (Offset) -> Unit,
        onDragStopped: () -> Unit,
        dragGestureDetector: DragGestureDetector,
    ): Modifier = this

    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (Offset) -> Unit,
        onDragStopped: () -> Unit,
    ): Modifier = this
}

@Composable
private fun LiftedRowPreviewBody(appearance: Appearance, vm: SessionRowVM) {
    RobocoTheme(appearance) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            Column(Modifier.background(RobocoTheme.palette.background)) {
                LiftedRowSample(vm)
            }
        }
    }
}

/** Preview (b): a row in the lifted state (progress = 1). */
@Preview(name = "Pinned lifted row – light", widthDp = 393, heightDp = 200)
@Composable
private fun LiftedRowLightPreview() {
    LiftedRowPreviewBody(Appearance.Light, SessionRowFixtures.sample("done-unseen"))
}

@Preview(name = "Pinned lifted row – dark", widthDp = 393, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LiftedRowDarkPreview() {
    LiftedRowPreviewBody(Appearance.Dark, SessionRowFixtures.sample("done-unseen"))
}
