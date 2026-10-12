package sh.roboco.android.design

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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

// TODO(01): Palette — these hexes are Palette.text / secondary / background / hairline.
private val TextLight = Color(0xFF27272C)
private val TextDark = Color(0xFFE8E8EA)
private val SecondaryLight = Color(0xFF62626A)
private val SecondaryDark = Color(0xFFA9A9AE)
private val BackgroundLight = Color(0xFFF3F3F5)
private val BackgroundDark = Color(0xFF060606)
private val HairlineLight = Color(0xFFE2E2E6)
private val HairlineDark = Color(0xFF1E1E22)

/**
 * The "Haptics" entry of the design gallery (android-ui-parity #05): one
 * capability line, then one row per moment with its iOS source and the plan
 * this device would run. Tapping a row fires it through `LocalFeedback`, so
 * the real gate applies — a fast double tap shows the rate limit in logcat.
 * Ticket 22 owns the registration (`HapticsGalleryHost` in `design/Gallery.kt`).
 */
@Composable
fun HapticsGallerySection(hardware: HapticHardware, hasView: Boolean) {
    val dark = isSystemInDarkTheme()
    val text = if (dark) TextDark else TextLight
    val secondary = if (dark) SecondaryDark else SecondaryLight
    val hairline = if (dark) HairlineDark else HairlineLight
    val feedback = LocalFeedback.current
    val caps = hardware.caps(hasView)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (dark) BackgroundDark else BackgroundLight),
    ) {
        BasicText(
            text = capabilityLine(hardware, hasView),
            style = TextStyle(fontSize = 13.sp, color = secondary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Haptic.entries.forEachIndexed { index, haptic ->
            if (index > 0) Hairline(hairline)
            HapticRow(
                haptic = haptic,
                plan = HapticPlanner.describe(HapticPlanner.plan(haptic, caps)),
                text = text,
                secondary = secondary,
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
        HapticsGallerySection(HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true), hasView = true)
    }
}

@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HapticsGallerySectionDark() {
    ProvideFeedback {
        HapticsGallerySection(HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true), hasView = true)
    }
}
