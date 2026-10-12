package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.FontStatus

/**
 * `FontsMissingScreen` title. Tests read this constant, not the literal.
 * The copy is Roboco-invented: iOS has no equivalent (it silently falls
 * back); keep it minimal — system fonts, palette roles, no actions.
 */
internal const val FONTS_MISSING_TITLE = "Roboco can't start"

/** `FontsMissingScreen` body. Tests read this constant, not the literal. */
internal const val FONTS_MISSING_BODY = "This build is missing bundled fonts. Reinstall Roboco."

/**
 * The release-build gate around the app's content: when the startup
 * `FontGate` check failed, the fatal screen replaces the app. Ticket 35
 * (app root) must keep this wrapper.
 */
@Composable
fun FontGateHost(status: FontStatus, content: @Composable () -> Unit) {
    when (status) {
        FontStatus.Ok -> content()
        is FontStatus.Missing -> FontsMissingScreen(status.paths)
    }
}

/**
 * The fatal screen for a release build whose bundled fonts are missing —
 * a packaging bug, shown instead of the app (`FontGate` stores the paths).
 *
 * This screen must not touch Geist or [LocalFonts]: the fonts may be the
 * very thing that is missing. It uses the system sans and monospace,
 * sizes from [LocalTypeScale] (fixed points — nothing here scales), and
 * ticket 01's palette roles. No interaction: the only remedy is a
 * reinstall.
 */
@Composable
fun FontsMissingScreen(paths: List<String>, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    val title = RobocoTextDefaults.Base.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = typeScale.fixed(20f),
        color = palette.text,
        textAlign = TextAlign.Center,
    )
    val body = RobocoTextDefaults.Base.copy(
        fontFamily = FontFamily.Default,
        fontSize = typeScale.fixed(15f),
        color = palette.secondary,
        textAlign = TextAlign.Center,
    )
    val pathStyle = RobocoTextDefaults.Base.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = typeScale.fixed(13f),
        color = palette.tertiary,
        textAlign = TextAlign.Center,
    )

    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                FONTS_MISSING_TITLE,
                style = title,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            BasicText(FONTS_MISSING_BODY, style = body)
            Spacer(Modifier.height(16.dp))
            paths.forEachIndexed { index, missing ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                BasicText(missing, style = pathStyle)
            }
        }
    }
}
