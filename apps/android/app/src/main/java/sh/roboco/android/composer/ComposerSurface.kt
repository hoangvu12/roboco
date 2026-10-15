package sh.roboco.android.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import sh.roboco.android.BuildConfig
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.rememberSmoothRoundedShape

/**
 * The alpha at which the chips count as gone (UIKit skips views with model
 * alpha < 0.01 for both VoiceOver and hit-testing).
 */
private const val ChipsAliveAt = 0.01f

/**
 * The composer's one glass surface (android-ui-parity #55): the static,
 * stateless half of the capsule ⇄ card morph — `ios:Composer/ComposerBar.swift`.
 * One [GlassSurface] (ticket 07, regular glass, no tint, `interactive =
 * false`) whose content is a custom [Layout] that measures and places six
 * slots **as a pure function of the morph progress `p`** (0 = capsule,
 * 1 = card) via [ComposerGeometry]:
 *
 * - **capsule** (`p = 0`): a 50 dp pill — `[+] Message… [↑]`;
 * - **card** (`p = 1`): thumbnails on top, full-width text, then a 50 dp
 *   toolbar row inside the glass — `[+] [model] [effort] [branch] … [Send]`.
 *
 * `p` is an **input** — ticket 56 (composer-morph) decides capsule vs card
 * and animates `p` with `Motion.ComposerMorph` (spring 0.42 s / 0.86);
 * this layout just draws whatever it is given, and under Reduce Motion 56
 * snaps `p` so the end state renders. [chipsInteractive] is 56's target
 * (`isCard`): UIKit stops hit-testing the chips the instant the target
 * flips (model alpha 0), so the chips' wrapper is stripped from semantics
 * and consumes touches while `!chipsInteractive || pc < 0.01`.
 *
 * **Slot order is the UIKit subview (Z) order** (`:183-201`): chips (inside
 * the clipped toolbar band, never above `+` or the action), thumbs, attach,
 * text, placeholder (no pointer input, so taps fall through to the text),
 * action. All slots are **always composed** — never `if (card)` /
 * `AnimatedVisibility` — so focus and the IME survive the morph (the pr621
 * lesson: "One field for both states").
 *
 * **Text width (decision COMPOSER-D1):** the text slot is measured at the
 * *target* mode's width (`textWidth(W, aw, if (chipsInteractive) 1f else
 * 0f)`) and placed in the lerped frame, clipped to the frame's width
 * ([TextSlotClip]) — a draft wraps once, at its final width, exactly as
 * UIKit lays text out at the model frame.
 *
 * **Recomposition:** [progress] is read in *measure* (and in the small
 * radius/[faded] derived states), so a running morph re-measures per frame
 * and recomposes only this composable's own scope; the slot lambdas are
 * parameters (stable instances) and are skipped.
 *
 * **Sizing:** the caller must bound the width (tickets 74/93 pass the
 * session width). The surface reports `H = thumbsH + textH + tbH` coerced
 * into the incoming height constraints — the parent must not force a
 * height; if a caller forces a taller box, the controls still sit at the
 * content's bottom (they are placed from the geometry's `H`, not the
 * coerced size). No `imePadding`/`windowInsetsPadding` here: the bottom
 * stack (74) and the canvas (93) place the composer.
 *
 * @param progress 0 = capsule, 1 = card, unclamped (the spring overshoots);
 *   read in measure/placement, plus the radius and the chips' liveness
 * @param modifier applied to the glass surface itself; ticket 56 adds
 *   tap-to-focus here, 74/93 add the width
 * @param thumbnails the photo strip slot (ticket 88); 0 × 0 when it emits
 *   nothing
 * @param text the text slot (ticket 57: `BasicTextField` + its own insets);
 *   measured at the target width, clipped to the lerped frame
 * @param placeholder drawn only while the text is empty (ticket 57)
 * @param attach the `+` capsule (ticket 87 wraps [ComposerAttachButton] as
 *   a menu anchor)
 * @param chips the chip strip slot (ticket 59), clipped to the toolbar band
 *   and faded by `pc`
 * @param chipsInteractive 56's `isCard` — the *target*: while false the
 *   chips are dead (no semantics, touches consumed) even mid-collapse
 * @param action the action button slot (ticket 58)
 */
@Composable
fun ComposerSurface(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    thumbnails: @Composable () -> Unit = {},
    text: @Composable () -> Unit,
    placeholder: @Composable () -> Unit = {},
    attach: @Composable () -> Unit = { ComposerAttachButton(onClick = {}) },
    chips: @Composable () -> Unit = {},
    chipsInteractive: Boolean = true,
    action: @Composable () -> Unit,
) {
    // The corner radius follows p here (25 -> 26 dp): rebuilding the shape
    // recomposes only this scope while p moves — the slot lambdas are
    // parameters and are skipped.
    val radius by remember { derivedStateOf { ComposerGeometry.radiusDp(progress()) } }
    val shape = rememberSmoothRoundedShape(radius.dp)

    // The chips' UIKit model-value liveness: dead the instant the target
    // flips, or once the lerped alpha drops below 0.01. Read inside the
    // wrapper (not in this body) so the flip recomposes only that slot.
    val faded by remember { derivedStateOf { progress().coerceIn(0f, 1f) < ChipsAliveAt } }
    val chipsSlot: @Composable () -> Unit = remember(chips, chipsInteractive) {
        {
            val dead = !chipsInteractive || faded
            Box(
                if (dead) {
                    // UIKit skips alpha-0 views for VoiceOver and touches:
                    // clear the semantics and consume every event at the
                    // Initial pass, so the children's clickable never fires.
                    Modifier
                        .clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                } else {
                    Modifier
                },
            ) { chips() }
        }
    }

    GlassSurface(modifier, shape = shape, interactive = false) {
        Layout(
            contents = listOf(thumbnails, text, placeholder, attach, chipsSlot, action),
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            require(constraints.hasBoundedWidth) {
                "ComposerSurface needs a bounded width: the composer's width comes from its parent (tickets 74/93)"
            }
            val w = constraints.maxWidth
            val d = density
            val m = ComposerMetrics
            val controlPx = m.Control.dp.roundToPx()
            // The TextSlotClip's side (the placeable's own coordinates:
            // placeRelative mirrors by width, keeping the leading edge).
            val leading = layoutDirection == LayoutDirection.Ltr

            /** The slot's single root, or null when it emits nothing. */
            fun slot(name: String, index: Int): Measurable? {
                val roots = measurables[index]
                if (BuildConfig.DEBUG && roots.size > 1) {
                    error(
                        "ComposerSurface slot '$name' emitted ${roots.size} root nodes; " +
                            "each slot must emit exactly one root (or nothing)",
                    )
                }
                return roots.firstOrNull()
            }

            // Reading p here makes every p change re-measure (no recomposition).
            val p = progress()

            // 1. attach: exactly 34 x 34.
            val attachPlaceable = slot("attach", 3)?.measure(Constraints.fixed(controlPx, controlPx))

            // 2. action: min 34 wide, capped at W - 84 (2*8 + 34 + 10 + 24:
            // keeps at least 24 dp of text in the capsule; never binds in
            // practice — iOS has image-only action configs).
            val actionMaxPx = max(controlPx, w - 84.dp.roundToPx())
            val actionPlaceable = slot("action", 5)?.measure(
                Constraints(
                    minWidth = controlPx,
                    maxWidth = actionMaxPx,
                    minHeight = controlPx,
                    maxHeight = controlPx,
                ),
            )
            val aw = (actionPlaceable?.width ?: 0).toFloat()

            // 3. the text slot: measured at the TARGET mode's width
            // (COMPOSER-D1), height free; the lerped frame clips it.
            val targetP = if (chipsInteractive) 1f else 0f
            val textTargetPx = ComposerGeometry.textWidth(w.toFloat(), aw, targetP, d).roundToInt()
            val textPlaceable = slot("text", 1)
                ?.measure(Constraints(minWidth = textTargetPx, maxWidth = textTargetPx))
            val textH = (textPlaceable?.height ?: 0).toFloat()

            // 4. the lerped frame's width: the placeholder's max width and
            //    the text's clip.
            val textWpx = ComposerGeometry.textWidth(w.toFloat(), aw, p, d)
            val placeholderPlaceable = slot("placeholder", 2)
                ?.measure(Constraints(maxWidth = textWpx.roundToInt()))

            // 5. thumbs: exact width W - 24, height free (0 when absent).
            val thumbsWidthPx = max(0, w - (2f * m.ThumbsInset * d).roundToInt())
            val thumbsPlaceable = slot("thumbnails", 0)
                ?.measure(Constraints(minWidth = thumbsWidthPx, maxWidth = thumbsWidthPx))
            val thumbsH = (thumbsPlaceable?.height ?: 0).toFloat()

            // 6. the frame of this p, then the chips inside it.
            val f = ComposerGeometry.frames(w.toFloat(), thumbsH, textH, aw, p, d)
            val chipsPlaceable = slot("chips", 4)
                ?.measure(Constraints.fixed(f.chips.width.roundToInt(), controlPx))

            // 7. the surface's height: the vertical chain, coerced into the
            //    incoming constraints (a forced-taller parent keeps its
            //    height; the controls stay at the content's bottom).
            val surfaceH = f.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(w, surfaceH) {
                // Z-order (bottom -> top), the iOS subview order: chips,
                // thumbs, attach, text, placeholder, action. placeRelative
                // mirrors every x in RTL.
                chipsPlaceable?.placeRelativeWithLayer(
                    f.chips.left.roundToInt(),
                    f.chips.top.roundToInt(),
                ) {
                    alpha = f.chipAlpha
                    clip = true
                    // The toolbar band, in the chips' local coordinates:
                    // clips the strip (and its hit area) to the opening band.
                    this.shape = BandClip(top = f.toolbar.top - f.chips.top, bottom = f.toolbar.bottom - f.chips.top)
                }
                thumbsPlaceable?.placeRelative(f.thumbs.left.roundToInt(), f.thumbs.top.roundToInt())
                attachPlaceable?.placeRelative(f.attach.left.roundToInt(), f.attach.top.roundToInt())
                textPlaceable?.placeRelativeWithLayer(f.text.left.roundToInt(), f.text.top.roundToInt()) {
                    clip = true
                    // The lerped frame's width, on the leading side: a
                    // card-width text growing out of a capsule frame is cut
                    // at the frame's trailing edge.
                    this.shape = TextSlotClip(width = textWpx.roundToInt(), leading = leading)
                }
                placeholderPlaceable?.placeRelative(
                    f.placeholderOrigin.x.roundToInt(),
                    f.placeholderOrigin.y.roundToInt(),
                )
                actionPlaceable?.placeRelative(f.action.left.roundToInt(), f.action.top.roundToInt())
            }
        }
    }
}

/**
 * The chips' clip: the toolbar band rect in the chips placeable's **local**
 * coordinates (`toolbar.clipsToBounds`, `ComposerBar.swift:183-187`). The
 * band's height is the lerped `tb` — at `p = 0` it is a zero-height rect,
 * so nothing draws and nothing is hit. A data class so equal values don't
 * invalidate the layer.
 */
@Immutable
private data class BandClip(val top: Float, val bottom: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect(0f, top, size.width, bottom))
}

/**
 * The text slot's clip (decision COMPOSER-D1): the leading [width] px of
 * the placeable — `Rect(0, 0, width, h)` leading, mirrored for RTL — so the
 * target-width text is cut at the lerped frame's trailing edge while the
 * morph runs. Collapsing, the text is narrower than the frame and nothing
 * is cut.
 */
@Immutable
private data class TextSlotClip(val width: Int, val leading: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = width.toFloat()
        return if (leading) {
            Outline.Rectangle(Rect(0f, 0f, w, size.height))
        } else {
            Outline.Rectangle(Rect(size.width - w, 0f, size.width, size.height))
        }
    }
}
