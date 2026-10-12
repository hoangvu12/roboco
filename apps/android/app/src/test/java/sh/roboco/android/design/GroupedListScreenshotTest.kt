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
 * The grouped-list goldens (android-ui-parity #70): the gallery fixture
 * (`GroupedListGallerySection`, the `2-settings.png` sections plus the
 * pressed demo row) in light and dark, on the ticket's `w402dp-h1300dp`
 * phone at xxhdpi — the width the geometry table is stated against, so the
 * 16 dp card insets, the 52/68/84 dp rows and the 26 dp continuous corners
 * are all at their reference size.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.design.GroupedListScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative to
 * the `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `design/`. The section's only animation-free state is the pressed row,
 * which holds its highlight via `previewPressed`, so the goldens are
 * byte-stable without freezing the clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h1300dp-xxhdpi")
class GroupedListScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                GroupedListGallerySection()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun galleryLight() {
        shot(Appearance.Light, "design/grouped_list_light.png")
    }

    @Test
    fun galleryDark() {
        shot(Appearance.Dark, "design/grouped_list_dark.png")
    }
}
