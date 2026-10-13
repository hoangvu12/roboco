package sh.roboco.android.settings

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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The Devices rows goldens (android-ui-parity #102): the gallery fixtures
 * of [DevicesRowsGallerySection] in light and dark, on the ticket's
 * `w402dp-h900dp` phone at xxhdpi — the width ticket 70's geometry table
 * is stated against, so the 16 dp card insets, the 67.7/52 dp rows and
 * the 26 dp continuous corners sit at their reference size. The section
 * is fixed `DevicesRowSpec`s — no live flow, no core — and nothing
 * animates, so the goldens are byte-stable without freezing the clock.
 *
 * Fixture row 1 ("MacBook Pro", "Connected", `success` dot) is the
 * side-by-side partner of `2-settings.png`'s "Choose Wallpaper…" row
 * card; row 4 pins the wrapped incompatible sentence (a taller row, as
 * iOS `numberOfLines = 0`).
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.DevicesRowsScreenshotTest`,
 * verify with `./gradlew :app:verifyRoborazziDebug --tests …`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class DevicesRowsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    DevicesRowsGallerySection()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun galleryLight() {
        shot(Appearance.Light, "settings/devices_rows_light.png")
    }

    @Test
    fun galleryDark() {
        shot(Appearance.Dark, "settings/devices_rows_dark.png")
    }
}
