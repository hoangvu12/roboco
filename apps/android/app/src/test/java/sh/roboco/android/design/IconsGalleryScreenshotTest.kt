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
 * The Icons gallery goldens (android-ui-parity #06): the whole section —
 * registry grid, weight and size ladders, `NumberCircle`, the "Measured vs
 * iOS" outlines and the swipe-action plates — in light and dark, 520×2000
 * dp at mdpi (1 dp = 1 px). Glyphs are `Palette.text` on `Palette.elevated`
 * cards; the fill glyphs' knock-outs (the check of `check-circle-fill`)
 * must let the card show through. The batch run records
 * `src/test/screenshots/design/icons_gallery_{light,dark}.png`; review the
 * "Measured vs iOS" outlines side by side with the `1-off.png` /
 * `2-settings.png` crops (the scale is 1.15 px per pt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IconsGalleryScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w520dp-h2000dp-mdpi")
    fun iconsGalleryLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                IconsGallerySection()
            }
        }
        compose.onRoot().captureRoboImage("design/icons_gallery_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w520dp-h2000dp-mdpi")
    fun iconsGalleryDark() {
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                IconsGallerySection()
            }
        }
        compose.onRoot().captureRoboImage("design/icons_gallery_dark.png")
    }
}
