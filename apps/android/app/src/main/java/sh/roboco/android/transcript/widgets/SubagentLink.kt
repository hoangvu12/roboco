package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape

/**
 * `WidgetKind.SubagentLink`'s view (android-next #06; upstream #899's
 * `RowView` case — a `ToolToggleControl` with a 10 pt continuous corner):
 * an invisible tap target over a whole subagent spawn card. The Rust rect
 * is the card itself, so the card's icon, label, description and running
 * dots stay Rust paint underneath and the dots keep following the chip's
 * `subagent_status`.
 *
 * While the finger is down the card fills with `controlFill`, rounded 10 dp
 * and instant both ways, like [ToolToggleTarget]. A tap plays the `Select`
 * haptic (iOS `UISelectionFeedbackGenerator`) then [onOpen]. The label is
 * iOS's "Open subagent: <title>".
 */
@Composable
fun SubagentLinkTarget(title: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val pressed = interactions.collectIsPressedAsState()
    val fill = RobocoTheme.palette.controlFill
    val shape = rememberSmoothRoundedShape(10.dp)
    Box(
        modifier
            .fillMaxSize()
            .testTag("subagent-link")
            // The State is read inside the draw block: a press redraws,
            // never recomposes.
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                onDrawBehind { if (pressed.value) drawOutline(outline, fill) }
            }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                feedback.haptic(Haptic.Select)
                onOpen()
            }
            .semantics { contentDescription = "Open subagent: $title" },
    )
}
