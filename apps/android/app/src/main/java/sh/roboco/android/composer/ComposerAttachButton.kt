package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight

/**
 * The composer's attach `+` capsule (android-ui-parity #55): the 34 × 34
 * filled circle of `ios:Composer/ComposerBar.swift:141-153` —
 * `UIButton.Configuration.filled()`, `plus` at 15 pt semibold in
 * `Palette.text` over `Palette.controlFill`, capsule corners, zero insets,
 * `accessibilityLabel = "Attach"`, `accessibilityIdentifier =
 * "composer-attach"`. No haptic (iOS fires none on `+`; the menu, ticket
 * 87, has its own).
 *
 * The pressed look is the overlay colour rule of ticket 08's opaque filled
 * buttons — ink @0.12, here ink = `Palette.text` — **without** 07's
 * `Glass.PressScale` swell (no source evidence that `.filled()` scales).
 * It is a draw-phase overlay, so a press never recomposes the button.
 *
 * [enabled] is a Roboco addition (ticket 79, offline read-only): the glyph
 * drops to `Palette.tertiary` and taps are ignored; iOS never disables `+`.
 *
 * @param modifier must stay **first** in the chain: ticket 78 passes 56's
 *   `Modifier.composerControl(morph)`, which must be the outermost modifier
 *   so a tap on `+` never triggers tap-to-focus (`ComposerBar.swift:276-285`)
 * @param interactionSource shared with the menu anchor (ticket 87) so the
 *   press look tracks its own touch handling
 */
@Composable
fun ComposerAttachButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val palette = RobocoTheme.palette
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Draw-time overlay, not a palette role (ticket 01's no-copy rule is
    // about defining roles; the pressed ink alpha is a transient wash).
    val pressedOverlay = remember(palette) { palette.text.copy(alpha = 0.12f) }

    Box(
        modifier
            .size(ComposerMetrics.Control.dp)
            .clip(CapsuleShape)
            .background(palette.controlFill)
            .drawWithContent {
                drawContent()
                if (pressed && enabled) drawRect(pressedOverlay)
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Attach" }
            .testTag("composer-attach"),
        contentAlignment = Alignment.Center,
    ) {
        // The button carries the label; the glyph stays decorative.
        Icon(
            RobocoIcons.Plus,
            tint = if (enabled) palette.text else palette.tertiary,
            sizePt = 15f,
            weight = SymbolWeight.Semibold,
        )
    }
}
