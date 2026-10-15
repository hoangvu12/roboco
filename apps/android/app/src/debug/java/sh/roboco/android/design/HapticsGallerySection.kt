package sh.roboco.android.design

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "Haptics" entry of the design gallery (android-ui-parity #05): one
 * capability line, then one row per moment with its iOS source and the plan
 * this device would run. Tapping a row fires it through `LocalFeedback`, so
 * the real gate applies — a fast double tap shows the rate limit in logcat.
 * Ticket 22 registers it through `HapticsGalleryHost` in `design/Gallery.kt`
 * and provides the theme; colours are palette roles, so the gallery's
 * appearance control reaches this section.
 */
@Composable
fun HapticsGallerySection(hardware: HapticHardware, hasView: Boolean) {
    val palette = RobocoTheme.palette
    val feedback = LocalFeedback.current
    val caps = hardware.caps(hasView)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background),
    ) {
        BasicText(
            text = capabilityLine(hardware, hasView),
            style = TextStyle(fontSize = 13.sp, color = palette.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Haptic.entries.forEachIndexed { index, haptic ->
            if (index > 0) Hairline(palette.hairline)
            HapticRow(
                haptic = haptic,
                plan = HapticPlanner.describe(HapticPlanner.plan(haptic, caps)),
                text = palette.text,
                secondary = palette.secondary,
                onClick = { feedback.haptic(haptic) },
            )
        }
    }
}

private fun capabilityLine(hardware: HapticHardware, hasView: Boolean): String {
    val primitives = ALL_PRIMITIVES
        .filter { it in hardware.primitives }
        .map(HapticPlanner::primitiveName)
        .ifEmpty { listOf("none") }
    return "SDK ${hardware.sdk} · view ${if (hasView) "yes" else "no"} · primitives ${primitives.joinToString(" ")}"
}

@Composable
private fun HapticRow(
    haptic: Haptic,
    plan: String,
    text: Color,
    secondary: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BasicText(haptic.name, style = TextStyle(fontSize = 15.sp, color = text))
        BasicText(HapticTable.iosSource(haptic), style = TextStyle(fontSize = 13.sp, color = secondary))
        BasicText(plan, style = TextStyle(fontSize = 13.sp, color = secondary))
    }
}

/** A 1 physical px separator (1 / density dp). */
@Composable
private fun Hairline(color: Color) {
    val density = LocalDensity.current
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { 1.toDp() })
            .background(color),
    )
}

@Preview(name = "light")
@Composable
private fun HapticsGallerySectionLight() {
    ProvideFeedback {
        RobocoTheme(Appearance.System) {
            HapticsGallerySection(HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true), hasView = true)
        }
    }
}

@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HapticsGallerySectionDark() {
    ProvideFeedback {
        RobocoTheme(Appearance.System) {
            HapticsGallerySection(HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true), hasView = true)
        }
    }
}
