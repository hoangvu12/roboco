package sh.roboco.android.design.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalReduceMotion

/**
 * Test-only: the graphics-layer scale applied by [Modifier.glassPress]
 * (1 = rest, [Glass.PressScale] = fully pressed). Production code never
 * reads it; UI tests read it through the semantics tree, including
 * mid-press. Ticket 07's [GlassPressScaleKey] is the same value for an
 * interactive [GlassSurface]; a node must publish exactly one of the two —
 * stacking both modifiers means a compounded ([Glass.PressScale]²) scale,
 * which the tests detect as two key-holding nodes.
 */
val GlassPressModifierScaleKey = SemanticsPropertyKey<Float>("GlassPressModifierScale")

/**
 * The interactive press for the **opaque** buttons (ticket 08): the exact
 * swell and highlight an interactive [GlassSurface] draws — the scale
 * `1 + (Glass.PressScale − 1) · s` about the centre plus the [highlight]
 * colour × the highlight factor over the content, both driven by
 * [interactionSource] on [Glass.PressDownSpring] when pressed and
 * [Glass.PressReleaseSpring] on release/cancel — but drawn here, because an
 * opaque fill has no glass material to swell.
 *
 * For the **glass** buttons (unfilled [GlassCircleButton],
 * [GlassTextCapsule]) the press comes from
 * `GlassSurface(interactive = true, interactionSource = …)` sharing the
 * button's `clickable` source instead. **Never combine the two** on one
 * button: the scales would compound to [Glass.PressScale]²
 * (ticket 08's "double press" pitfall).
 *
 * Everything 07 owns is referenced, never restated: a retune of
 * [Glass.PressScale] or the springs in [Glass] changes this modifier and
 * every glass button together.
 *
 * - The scale is a `graphicsLayer` **draw-phase** read: the measured size
 *   and layout are unaffected, so surrounding bars and stacks do not
 *   jiggle while pressed.
 * - The animation is interruptible: `animateTo` always continues from the
 *   current value.
 * - Under Reduce Motion ([LocalReduceMotion]) the scale stays 1 and the
 *   highlight switches with `snapTo`, exactly like the interactive
 *   [GlassSurface] — glass and opaque buttons behave alike.
 * - The highlight overlay is drawn over the content, clipped to [shape].
 *   With [enabled] `false` the target is 0, so nothing is drawn and the
 *   button does not respond.
 *
 * Public because ticket 107's toolbar slots apply it directly to their own
 * opaque shapes with their own highlight colour.
 */
fun Modifier.glassPress(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    highlight: Color,
    enabled: Boolean = true,
): Modifier = composed(
    inspectorInfo = {
        name = "glassPress"
        properties["interactionSource"] = interactionSource
        properties["shape"] = shape
        properties["highlight"] = highlight
        properties["enabled"] = enabled
    },
) {
    // The press state, exactly ticket 07's interactive press: two Animatables
    // (scale progress s, highlight progress h) driven by the same springs.
    // `remember`ed in the composed modifier's own scope, so the values and
    // the running animations survive the caller's recompositions.
    val s = remember { Animatable(0f) }
    val h = remember { Animatable(0f) }
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    LaunchedEffect(pressed && enabled, reduce) {
        val target = if (pressed && enabled) 1f else 0f
        val spec = if (pressed && enabled) Glass.PressDownSpring else Glass.PressReleaseSpring
        // Reversals cancel the running animateTo and continue from the
        // current value; reduced motion snaps (no scale, instant highlight).
        launch { if (reduce) s.snapTo(0f) else s.animateTo(target, spec) }
        launch { if (reduce) h.snapTo(target) else h.animateTo(target, spec) }
    }
    Modifier
        .graphicsLayer {
            val k = 1f + (Glass.PressScale - 1f) * s.value
            scaleX = k
            scaleY = k
        }
        .drawWithContent {
            drawContent()
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = highlight.copy(alpha = highlight.alpha * h.value),
            )
        }
        // Test-only scale readout. The semantics block's snapshot reads are
        // observed by the semantics tree (LayoutNode observes reads around
        // calculateSemanticsConfiguration), so the key follows the animation
        // without recomposing anything.
        .semantics { this[GlassPressModifierScaleKey] = 1f + (Glass.PressScale - 1f) * s.value }
}
