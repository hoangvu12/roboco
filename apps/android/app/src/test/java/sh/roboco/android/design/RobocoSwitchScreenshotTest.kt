package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * The switch goldens (android-ui-parity #98): the gallery fixture
 * (`RobocoSwitchGallerySection` — the state card with the five
 * `RobocoSwitchVisual` fixtures, the refusing "Notifications" row and the
 * accepting "Group by Project" row) in light and dark, on the ticket's
 * `w402dp-h420dp` phone at xxhdpi, so the 63 × 28 track, the 37 × 24 thumb
 * and the 16 dp card insets are all at their reference size for the
 * side-by-side check against `2-settings.png`.
 *
 * The section is hosted directly (no gallery frame): the cards sit 16 dp
 * from the screen edges, like Settings. At rest nothing animates — the
 * fixtures are static fractions and the interactive rows hold their
 * initial state — so the goldens are byte-stable without freezing the
 * clock.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.design.RobocoSwitchScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative to
 * the `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h420dp-xxhdpi")
class RobocoSwitchScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    RobocoSwitchGallerySection()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun galleryLight() {
        shot(Appearance.Light, "design/robocoswitch_light.png")
    }

    @Test
    fun galleryDark() {
        shot(Appearance.Dark, "design/robocoswitch_dark.png")
    }
}
