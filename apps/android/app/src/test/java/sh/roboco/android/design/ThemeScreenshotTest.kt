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
 * The theme-root goldens (recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`): the palette page in light and dark (swatches over
 * the checkerboard, both hexes per role) and the shapes page in both modes.
 *
 * Paths are relative to the `roborazzi { outputDir }` (see
 * `apps/android/gradle.properties`:
 * `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory`),
 * so these land under `src/test/screenshots/design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ThemeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paletteShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    PaletteGallerySection()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    private fun shapesShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ShapesGallerySection()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
    fun paletteLight() {
        paletteShot(Appearance.Light, "design/theme_palette_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
    fun paletteDark() {
        paletteShot(Appearance.Dark, "design/theme_palette_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun shapesLight() {
        shapesShot(Appearance.Light, "design/theme_shapes_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
    fun shapesDark() {
        shapesShot(Appearance.Dark, "design/theme_shapes_dark.png")
    }
}
