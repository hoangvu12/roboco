package sh.roboco.android.design

import androidx.compose.runtime.Composable
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
 * The Haptics gallery section (android-ui-parity #05): 11 rows plus the
 * capability line for the fixture SDK 34 / ALL primitives / a view. Row 1
 * reads `Select` / `selection · ComposerBar.swift:371` / `View SEGMENT_TICK`.
 * Wrapped in `RobocoTheme` since ticket 22: the section reads the palette,
 * so the appearance is explicit here. Goldens live under
 * `src/test/screenshots/design/` (the `roborazzi { outputDir }`); the batch
 * run records them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HapticsGalleryScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun content(appearance: Appearance) {
        RobocoTheme(appearance) {
            HapticsGallerySection(HapticHardware(34, ALL_PRIMITIVES.toSet(), hasVibrator = true), hasView = true)
        }
    }

    @Test
    fun hapticsGalleryLight() {
        compose.setContent { content(Appearance.Light) }
        compose.onRoot().captureRoboImage("design/haptics_gallery_light.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun hapticsGalleryDark() {
        compose.setContent { content(Appearance.Dark) }
        compose.onRoot().captureRoboImage("design/haptics_gallery_dark.png")
    }
}
