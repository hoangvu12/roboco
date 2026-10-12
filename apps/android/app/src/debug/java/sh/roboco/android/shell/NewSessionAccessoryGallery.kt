package sh.roboco.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle

/**
 * The "New session accessory" page of the debug design gallery
 * (android-ui-parity #27), registered in ticket 22's `Gallery.sections`
 * as `GallerySection("New session accessory") { NewSessionAccessoryGallerySection() }`.
 *
 * The capsules sit on ticket 22's `GlassStage`: the backdrop is the only
 * `hazeSource` and every capsule is a sibling of it (ticket 07's
 * per-screen Haze convention), so the glass reads over the stripes and the
 * photo. Under `GalleryCapture` the tier is the flat fallback fill; on a
 * device the capsules blur the backdrop.
 *
 * Rows, in the ticket's step 7 order: the five count states, the inline
 * (2,1) at 180 dp (the label is laid out at alpha 0 — plate, glyph and
 * summary stay), and the long-summary case (12,10) in a 300 dp capsule,
 * which shows the label truncating while the summary stays whole. The
 * tap counter under the stage proves the whole capsule is one button.
 * The spinner runs on the theme's shared clock, so every spinner on the
 * page pulses in phase.
 *
 * Labels are foundation `BasicText` in the Mono 11 gallery label style
 * (no Material `Text`).
 */
@Composable
fun NewSessionAccessoryGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var taps by remember { mutableIntStateOf(0) }
    val state = rememberHazeState()

    // One HazeState for the whole section, provided around the column; the
    // stage's backdrop modifier carries the source.
    ProvideHazeState(state) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassStage(
                height = 520.dp,
                backdropModifier = Modifier.hazeSource(state),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewSessionAccessory(counts = LiveCounts(), onTap = { taps++ })
                    NewSessionAccessory(counts = LiveCounts(working = 2), onTap = { taps++ })
                    NewSessionAccessory(counts = LiveCounts(awaiting = 1), onTap = { taps++ })
                    NewSessionAccessory(counts = LiveCounts(awaiting = 3), onTap = { taps++ })
                    NewSessionAccessory(counts = LiveCounts(working = 2, awaiting = 1), onTap = { taps++ })
                    NewSessionAccessory(
                        counts = LiveCounts(working = 2, awaiting = 1),
                        onTap = { taps++ },
                        modifier = Modifier.width(180.dp),
                        inline = true,
                    )
                    NewSessionAccessory(
                        counts = LiveCounts(working = 12, awaiting = 10),
                        onTap = { taps++ },
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
            BasicText(
                text = "taps: $taps",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            )
        }
    }
}
