package sh.roboco.android.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassCircleVariants
import sh.roboco.android.design.glass.GlassProminentVariants
import sh.roboco.android.design.glass.GlassTextCapsuleVariants
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The "Glass buttons" page of the debug design gallery (ticket 08): the
 * three button primitives in every variant — the circles, the text
 * capsules and the prominent capsules — each group on its own
 * [GlassStage] backdrop so the glass has something to blur, per the
 * per-screen Haze convention (`GlassHaze.kt`): the backdrop is the only
 * `hazeSource`, the buttons are siblings drawn over it.
 *
 * Each group has a "hold to press" toggle that emits a fake
 * `PressInteraction` into the group's shared [MutableInteractionSource], so
 * the pressed swell/highlight can be inspected hands-free (light and dark,
 * and with the frame's "Force reduce motion" toggle on: no scale, the
 * highlight snapping in — the busy spinner keeps rotating).
 *
 * Registered in `Gallery.sections` as "Glass buttons" (slug
 * `glass_buttons`), captured automatically by ticket 22's parameterized
 * gallery test. Under the Roborazzi gallery capture the clock never
 * advances, so the busy spinner sits at step 0 and the goldens stay
 * byte-stable.
 */
@Composable
fun GlassButtonsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val labelStyle = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            "Circles — glass 44/17 · jump 40/15 · share 44/15 · filled + and ✓ · disabled",
            style = labelStyle,
        )
        PressStage(height = 148.dp) { source -> GlassCircleVariants(source) }
        BasicText(
            "Text capsules — 44 dp tall · 14 dp sides · Done emphasized · tail clip",
            style = labelStyle,
        )
        PressStage(height = 148.dp) { source -> GlassTextCapsuleVariants(source) }
        BasicText(
            "Prominent — Next compact · Submit disabled · Use full width · Use busy · pairing",
            style = labelStyle,
        )
        PressStage(height = 372.dp) { source -> GlassProminentVariants(source) }
    }
}

/**
 * One group's stage: the deterministic backdrop with the `hazeSource`, the
 * group's buttons over it, and the "hold to press" toggle emitting a fake
 * [PressInteraction] into the shared [MutableInteractionSource] every
 * button of the group receives.
 */
@Composable
private fun PressStage(height: Dp, content: @Composable (MutableInteractionSource) -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toggleStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent)
    val source = remember { MutableInteractionSource() }
    val toggleSource = remember { MutableInteractionSource() }
    var holding by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    ProvideHazeState {
        GlassStage(
            height = height,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    text = if (holding) "Release" else "Hold to press",
                    style = toggleStyle,
                    modifier = Modifier.clickable(
                        interactionSource = toggleSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            val p = press
                            if (p == null) {
                                PressInteraction.Press(Offset.Zero).also {
                                    press = it
                                    source.tryEmit(it)
                                }
                            } else {
                                source.tryEmit(PressInteraction.Release(p))
                                press = null
                            }
                            holding = !holding
                        },
                    ),
                )
                content(source)
            }
        }
    }
}
