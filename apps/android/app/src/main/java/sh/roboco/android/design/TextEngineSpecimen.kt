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
import sh.roboco.android.core.AndroidMeasurer
import sh.roboco.android.core.Face
import sh.roboco.android.core.FontChain

/**
 * The text-engine specimen (debug gallery, ticket 60): the shared
 * `TextSystem`'s output as the user sees it — Latin in Geist, CJK/emoji
 * through the chain's Noto Sans CJK fallback (SemiBold visibly heavier
 * than Regular), mono with the table row the transcript painter keeps
 * column-aligned. Compose draws the CJK in the mono line at its natural
 * width; mono double-width is a transcript-painter rule (ticket 63).
 *
 * The readout line names the CJK source and the measurer's call count so
 * the lab (tickets 135/136) has a cheap visual counterpart. Previews and
 * the screenshot test pass fixed values: the live ones depend on the host
 * fonts and on test order, which would make the golden flaky.
 */
@Composable
fun TextEngineSpecimen(
    cjkSource: String? = FontChain.cjkSource,
    measurerCalls: Long = AndroidMeasurer.Stats.calls.get(),
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            BasicText(
                "Sans  直 名称 한국어 かな 👨‍👩‍👧 🇯🇵",
                style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Sans  直 名称 한국어 かな 👨‍👩‍👧 🇯🇵",
                style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Mono  | 名称 | 状态 |",
                style = uiStyle(Face.Mono, typeScale.fixed(13.5f), palette.text),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "cjk=$cjkSource measurer calls=$measurerCalls",
                style = uiStyle(Face.Mono, typeScale.fixed(13f), palette.secondary),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews. Fixed cjkSource/measurerCalls: the live values would differ on
// every host and make the golden meaningless.
// ---------------------------------------------------------------------------

@Preview(name = "Text engine light")
@Composable
private fun TextEngineSpecimenLightPreview() {
    RobocoTheme(Appearance.Light) { TextEngineSpecimen(cjkSource = "fixture", measurerCalls = 0) }
}

@Preview(name = "Text engine dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TextEngineSpecimenDarkPreview() {
    RobocoTheme(Appearance.Dark) { TextEngineSpecimen(cjkSource = "fixture", measurerCalls = 0) }
}
