package sh.roboco.android.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The lab meters fixture: the illustrative result JSON from the ticket's
 * Spec, with every `…` filled with the idle values. Nothing here is a
 * reference number — it only needs to be a realistic, long, wrapped and
 * ellipsized string for the label's golden.
 */
internal val FIXTURE_JSON =
    """{"idle":{"frames":671,"hitches":3,"hitchRatioMsPerS":1.24,"worstFrameMs":41.7,"seconds":11.18},""" +
        """"streaming":{"frames":671,"hitches":3,"hitchRatioMsPerS":1.24,"worstFrameMs":41.7,"seconds":11.18},""" +
        """"idlePerf":{"maxLayoutMs":2.31,"maxApplyMs":0.84,"maxSyncBuildMs":1.92,"syncBuilds":4,"asyncBuilds":0,"viewsCreated":38},""" +
        """"streamingPerf":{"maxLayoutMs":2.31,"maxApplyMs":0.84,"maxSyncBuildMs":1.92,"syncBuilds":4,"asyncBuilds":0,"viewsCreated":38},""" +
        """"jankstats":{"idleFrames":668,"idleJanky":2,"idleJankyPct":0.30,"idleWorstMs":33.1,""" +
        """"streamingFrames":535,"streamingJanky":4,"streamingJankyPct":0.75,"streamingWorstMs":40.2,"refreshHz":120.0}}"""

/**
 * The "Lab meters" section of the debug design gallery
 * (android-ui-parity #136): the two debug instruments of the Transcript
 * Lab, static — the hitch meter label with a representative reading, and
 * the bench result label with the fixture JSON (wrapped and ellipsized
 * exactly like the live one). No live measurement runs here; the real
 * instruments are the lab activity's `--ez meter`/`--ez bench` extras.
 */
@Composable
fun LabMetersGallerySection() {
    val palette = RobocoTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(200.dp, 40.dp).background(palette.background),
            contentAlignment = Alignment.Center,
        ) {
            HitchMeterLabel(" 3 hitches · layout 1.4ms ")
        }
        Box(Modifier.size(393.dp, 220.dp)) {
            BenchResultLabel(FIXTURE_JSON)
        }
    }
}

// ------------------------------------------------------------ previews

@Preview
@Composable
private fun LabMetersLightPreview() {
    LabMetersPreviewBody(Appearance.Light)
}

@Preview
@Composable
private fun LabMetersDarkPreview() {
    LabMetersPreviewBody(Appearance.Dark)
}

@Composable
private fun LabMetersPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            LabMetersGallerySection()
        }
    }
}
