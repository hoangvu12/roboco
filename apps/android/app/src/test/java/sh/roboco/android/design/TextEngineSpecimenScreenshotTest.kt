package sh.roboco.android.design

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The text-engine goldens (recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`): the specimen in light and dark, with **fixed**
 * `cjkSource`/`measurerCalls` — the live values depend on the host fonts
 * and on test order, which would make the golden flaky.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner
 * (android-app#30). The golden does not prove CJK/emoji coverage — the
 * Robolectric host may lack those system fonts (tofu is fine); the glyph
 * forms are checked on an emulator in the ticket's manual steps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TextEngineSpecimenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun specimenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                TextEngineSpecimen(cjkSource = "fixture", measurerCalls = 0)
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun textEngineSpecimenLight() {
        specimenShot(Appearance.Light, "design/text_engine_specimen_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun textEngineSpecimenDark() {
        specimenShot(Appearance.Dark, "design/text_engine_specimen_dark.png")
    }
}
