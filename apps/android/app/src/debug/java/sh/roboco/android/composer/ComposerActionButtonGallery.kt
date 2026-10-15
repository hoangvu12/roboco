package sh.roboco.android.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle

/**
 * The "Composer action button" page of the debug design gallery
 * (android-ui-parity #58), registered in ticket 22's registry as
 * `GallerySection("Composer action button") { ComposerActionButtonGallerySection() }`.
 *
 * Two halves over the busy backdrop:
 *  - the **static** five-state row — send (empty), send, queue, stop,
 *    offline — each labelled underneath, from the main source set's
 *    [ActionButtonFixtureStates] (the same states the Roborazzi goldens
 *    shoot);
 *  - a **live** [ComposerActionButton] over the controller overload,
 *    driven by four chips: `running`, `has content`, `canSteer` and
 *    `canWrite`. Toggling `running` with content shows the queue bounce
 *    with no colour change; toggling `has content` while idle shows the
 *    grey → accent snap; a long press mid-turn opens the "While the agent
 *    works" menu (its Steer row disappears with `canSteer` off); the mono
 *    readout under the stage logs `onSend(mode)` / `onStop()`.
 *
 * The section wraps itself in its own [GlassMenuHost] (the registry's
 * [sh.roboco.android.design.GalleryProviders] installs no host): the
 * window's content is the haze source the menu panels blur.
 *
 * Try it live (`adb shell am start -n sh.roboco.android/.MainActivity
 * --es route gallery --es section composer_action_button`): tap the stop
 * square (one sharp click — the controller's [sh.roboco.android.design.Haptic.Stop],
 * never a double buzz) and watch the log show `onStop()`.
 */
@Composable
fun ComposerActionButtonGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GlassMenuHost(hazeState = rememberHazeState()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption(
                "Composer action button",
                uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            Caption(
                "The trailing send / queue / stop control: the accent arrow, the grey " +
                    "disabled arrow, the inverted stop square and the offline grey. A visual " +
                    "action change springs the fill and pops the glyph; typing snaps.",
                uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
            StaticStatesRow()
            LiveDemo()
        }
    }
}

/** The static five states, each labelled underneath, over the backdrop. */
@Composable
private fun StaticStatesRow() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GlassStage(height = 120.dp) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((label, state) in ActionButtonFixtureStates) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ComposerActionButton(state, onPrimary = {}, onSend = {})
                    BasicText(
                        label,
                        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
                    )
                }
            }
        }
    }
}

/**
 * The live demo: one real controller (ticket 54's
 * [rememberComposerController]) behind the button's controller overload,
 * driven by the four chips. `onSend` answers [SendResult.Kept] so the text
 * survives and the demo stays in the state you set up.
 */
@Composable
private fun LiveDemo() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val controller = rememberComposerController()
    var log by remember { mutableStateOf("(nothing yet)") }
    controller.onSend = { _, _, mode ->
        log = "onSend($mode)"
        SendResult.Kept
    }
    controller.onStop = { log = "onStop()" }

    var running by remember { mutableStateOf(false) }
    var hasContent by remember { mutableStateOf(false) }
    var canSteer by remember { mutableStateOf(false) }
    var canWrite by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassStage(height = 120.dp) {
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                ComposerActionButton(controller)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DemoChip("running", running) {
                running = !running
                controller.running = running
            }
            DemoChip("has content", hasContent) {
                hasContent = !hasContent
                controller.text = if (hasContent) "Hello" else ""
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DemoChip("canSteer", canSteer) {
                canSteer = !canSteer
                controller.canSteer = canSteer
            }
            DemoChip("canWrite", canWrite) {
                canWrite = !canWrite
                controller.canWrite = canWrite
            }
        }
        BasicText(log, style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary))
    }
}

/** A plain clickable text chip, like the morph section's (no Material). */
@Composable
private fun DemoChip(text: String, active: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(
            Face.SansMedium,
            typeScale.fixed(14f),
            if (active) palette.accent else palette.secondary,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Caption(text: String, style: TextStyle) {
    BasicText(text, style = style)
}
