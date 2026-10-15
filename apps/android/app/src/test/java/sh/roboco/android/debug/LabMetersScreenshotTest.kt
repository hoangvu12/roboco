package sh.roboco.android.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The lab meter goldens (android-ui-parity #136; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the hitch meter label and the bench result label in light and dark, on
 * the ticket's `w411dp-h891dp` phone at xxhdpi — side by side with an iOS
 * `-lab -meter` capture the label look must match: text `#62626A` /
 * `#A9A9AE` on `0xD9FFFFFF` / `0xD9111113`, 6 dp circular radius, height
 * one 11 dp line for the meter; wrapped, ellipsized mono 10 dp text in
 * the 80 dp bench box. The whole "Lab meters" gallery section (both
 * labels over the palette) is captured separately by ticket 22's
 * GalleryScreenshotTest, which picks the registered section up
 * automatically.
 *
 * Rasterisation is host-dependent: record on the Ubuntu runner, like
 * every golden here. At rest nothing animates — both labels are static
 * text — so the goldens are byte-stable without freezing the clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class LabMetersScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(
                    Modifier.fillMaxSize().background(RobocoTheme.palette.background),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun hitchMeterLight() {
        shot(Appearance.Light, "debug/hitch_meter_light.png") {
            HitchMeterLabel(" 3 hitches · layout 1.4ms ")
        }
    }

    @Test
    fun hitchMeterDark() {
        shot(Appearance.Dark, "debug/hitch_meter_dark.png") {
            HitchMeterLabel(" 3 hitches · layout 1.4ms ")
        }
    }

    @Test
    fun benchLabelLight() {
        shot(Appearance.Light, "debug/bench_label_light.png") {
            BenchResultLabel(FIXTURE_JSON)
        }
    }

    @Test
    fun benchLabelDark() {
        shot(Appearance.Dark, "debug/bench_label_dark.png") {
            BenchResultLabel(FIXTURE_JSON)
        }
    }
}
