package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import sh.roboco.android.core.Face

/**
 * The type specimen: one line per [Face] in `fixed(17)`, the fixed /
 * scaled / bodyScaled comparison trio at 16.5, and the factor readout.
 * For previews, screenshots and the design gallery's "Type" section
 * (android-ui-parity #22) — no other component uses it.
 *
 * The sample text only uses characters Geist covers (`ä` is; `⎇` is not
 * and would silently fall back to the system font, making the specimen
 * lie). The Mono line shows the `0`'s inner mark that `O` lacks — every
 * GeistMono glyph advances 600/1000 em.
 */
@Composable
fun TypeSpecimen(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    Box(modifier.fillMaxSize().background(palette.background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Face.entries.forEach { face ->
                BasicText(
                    "${face.name} — Sessions 0O123 ägy",
                    style = uiStyle(face, typeScale.fixed(17f), palette.text),
                )
                Spacer(Modifier.height(16.dp))
            }
            // The trio: same nominal size, three Dynamic-Type behaviours.
            BasicText(
                "fixed 16.5",
                style = uiStyle(Face.SansMedium, typeScale.fixed(16.5f), palette.secondary),
            )
            BasicText(
                "scaled 16.5 → ${roundHalfAway(16.5f * typeScale.factor)}",
                style = uiStyle(Face.SansMedium, typeScale.scaled(16.5f), palette.secondary),
            )
            BasicText(
                "bodyScaled 16.5",
                style = uiStyle(Face.SansMedium, typeScale.bodyScaled(16.5f), palette.secondary),
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                "factor=%.3f body=%.3f".format(Locale.ROOT, typeScale.factor, typeScale.bodyFactor),
                style = uiStyle(Face.Mono, typeScale.fixed(13f), palette.text),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews. The FontsMissingScreen ones intentionally use system fonts.
// ---------------------------------------------------------------------------

@Preview(name = "Type light")
@Composable
private fun TypeSpecimenLightPreview() {
    RobocoTheme(Appearance.Light) { TypeSpecimen() }
}

@Preview(name = "Type dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TypeSpecimenDarkPreview() {
    RobocoTheme(Appearance.Dark) { TypeSpecimen() }
}

@Preview(name = "Type 1.3x", fontScale = 1.3f)
@Composable
private fun TypeSpecimenFontScalePreview() {
    RobocoTheme(Appearance.Light) { TypeSpecimen() }
}

private val missingFontSample = listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf")

@Preview(name = "Fonts missing - light")
@Composable
private fun FontsMissingLightPreview() {
    RobocoTheme(Appearance.Light) { FontsMissingScreen(missingFontSample) }
}

@Preview(name = "Fonts missing - dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FontsMissingDarkPreview() {
    RobocoTheme(Appearance.Dark) { FontsMissingScreen(missingFontSample) }
}
