package sh.roboco.android.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/** The gallery's Nav alpha steps: 1 / 0.5 / 0 (ticket 85's multiplier). */
private val JumpNavAlphaChoices = floatArrayOf(1f, 0.5f, 0f)

/**
 * The "Jump to latest" page of the debug design gallery
 * (android-ui-parity #131): the ticket's stage fixture — the striped haze
 * source, 74's stack stand-in and the button at 74's jump anchor — with
 * live toggles:
 *
 * - **Visible** drives the stateless core, so the show/hide spring (and a
 *   quick reversal mid-flight, `.beginFromCurrentState`) can be watched;
 * - **Nav alpha** cycles 1 / 0.5 / 0 (85's pop-fade multiplier: the layer
 *   fades, the spring is untouched);
 * - a live readout of `target · p · alpha · scale` in the layer's own
 *   formulas.
 *
 * It follows the gallery's Light/Dark and Force-reduce-motion toggles
 * through [RobocoTheme]; the spring is deliberately **not** gated by
 * Reduce Motion (iOS keeps it, ticket 04), so it keeps springing under
 * the forced setting.
 *
 * Registered in `design/Gallery.kt` as `GallerySection("Jump to latest")`.
 */
@Composable
fun JumpToLatestGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var visible by remember { mutableStateOf(true) }
    var navStep by remember { mutableIntStateOf(0) }
    val motion = remember { JumpToLatestMotion(true) }

    Column {
        JumpToLatestStage(
            visible = visible,
            navAlpha = { JumpNavAlphaChoices[navStep] },
            motion = motion,
        )
        Column(Modifier.fillMaxWidth().height(190.dp)) {
            JumpToggleRow("Visible", if (visible) "on" else "off") { visible = !visible }
            JumpToggleRow("Nav alpha", JumpNavAlphaChoices[navStep].toString()) {
                navStep = (navStep + 1) % JumpNavAlphaChoices.size
            }
            // The live readout — the same formulas the layer uses. It
            // recomposes every animation frame by design (a gallery-only
            // exception to the draw-phase-read rule).
            BasicText(
                jumpReadout(motion, JumpNavAlphaChoices[navStep]),
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
        }
    }
}

/** `target · p (3) · alpha (2) · scale (3)` — the layer values, live. */
private fun jumpReadout(motion: JumpToLatestMotion, nav: Float): String = String.format(
    Locale.ROOT,
    "target %b · p %.3f · alpha %.2f · scale %.3f",
    motion.target,
    motion.progress.value,
    JumpToLatest.alpha(motion.progress.value, nav),
    JumpToLatest.scale(motion.progress.value),
)

/** One gallery toggle row: label left, current value in accent right. */
@Composable
private fun JumpToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}
