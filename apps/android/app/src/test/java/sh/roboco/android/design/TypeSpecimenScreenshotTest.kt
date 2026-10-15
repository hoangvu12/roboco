package sh.roboco.android.design

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The type goldens (recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`): the specimen in light, dark and at font scale 2
 * (the factor readout shows 1.600 and the `scaled` line is visibly larger
 * than `fixed`), and the fatal missing-fonts screen in light and dark.
 *
 * Paths are relative to the `roborazzi { outputDir }`
 * (`src/test/screenshots`), landing under `design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TypeSpecimenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun specimenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) { TypeSpecimen() }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
    fun typeSpecimenLight() {
        specimenShot(Appearance.Light, "design/type_specimen_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
    fun typeSpecimenDark() {
        specimenShot(Appearance.Dark, "design/type_specimen_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi", fontScale = 2f)
    fun typeSpecimenFontScale2Light() {
        // @Config(fontScale) applies before the activity launches; the
        // ticket's RuntimeEnvironment.setFontScale before setContent is
        // the same setting applied one step later — both, belt and braces.
        RuntimeEnvironment.setFontScale(2f)
        specimenShot(Appearance.Light, "design/type_specimen_fontscale2_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun fontsMissingLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                FontsMissingScreen(listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf"))
            }
        }
        compose.onRoot().captureRoboImage("design/fonts_missing_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun fontsMissingDark() {
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                FontsMissingScreen(listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf"))
            }
        }
        compose.onRoot().captureRoboImage("design/fonts_missing_dark.png")
    }
}
