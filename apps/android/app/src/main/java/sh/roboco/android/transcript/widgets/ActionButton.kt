package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * `WidgetKind.Action`'s view (wave-9 #14's error card; upstream #967's
 * `RowView` `.action` case): a filled capsule over the Rust rect, [fill]
 * (`DANGER_SOFT`) behind a Sans Semibold 14 label in the screen background
 * colour, 12 dp side insets. A tap plays the `Light` haptic (iOS
 * `UIImpactFeedbackGenerator(style: .light)`) then [onPerform]. Tagged
 * `transcript-action-<action>` like iOS's accessibility identifier.
 */
@Composable
fun ActionButton(action: String, label: String, fill: Color, onPerform: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    val typeScale = LocalTypeScale.current
    Box(
        modifier
            .fillMaxSize()
            .testTag("transcript-action-$action")
            .clip(CapsuleShape)
            .background(fill)
            .clickable(role = Role.Button) {
                feedback.haptic(Haptic.Light)
                onPerform()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = uiStyle(Face.SansSemibold, typeScale.fixed(14f), RobocoTheme.palette.background),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
