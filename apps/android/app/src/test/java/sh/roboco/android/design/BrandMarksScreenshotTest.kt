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
 * The brand-mark goldens (android-ui-parity #13): the 9 harnesses at sides
 * 20 / 34 / 64 dp, tinted `Palette.text` on `Palette.background`, 720×240 dp
 * at mdpi (1 dp = 1 px). Claude keeps its orange in both themes; pi, opencode
 * and hermes holes stay open at 64 dp; opencode is narrower than its cell and
 * centred. Goldens live under `src/test/screenshots/design/` (the `roborazzi
 * { outputDir }`); the batch run records them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BrandMarksScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w720dp-h240dp-mdpi")
    fun brandMarksLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                BrandMarksSample()
            }
        }
        compose.onRoot().captureRoboImage("design/brand_marks_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w720dp-h240dp-mdpi")
    fun brandMarksDark() {
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                BrandMarksSample()
            }
        }
        compose.onRoot().captureRoboImage("design/brand_marks_dark.png")
    }
}
