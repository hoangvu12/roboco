package sh.roboco.android.settings

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
 * The Wallpaper rows goldens (android-ui-parity #97): the gallery fixtures
 * (a)–(c) of `WallpaperRowsGallerySection` in light and dark, on the
 * ticket's `w402dp-h1100dp` phone at xxhdpi — the width ticket 70's
 * geometry table is stated against, so the 16 dp card insets, the 52/68/
 * 84.6 dp rows and the 26 dp continuous corners sit at their reference
 * size. The live block is dropped (`showLive = false`): the goldens pin
 * the three fixtures exactly as the acceptance list states, byte-stable
 * (the section is animation-free — no pressed row, no sheet, no launcher).
 *
 * Fixture (a) is the side-by-side partner of `2-settings.png`'s Wallpaper
 * card: 68 dp tall, `photo` icon, "Choose Wallpaper…", the one-line
 * "Shown behind new chats and the sessions list" subtitle, chevron.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.WallpaperRowsScreenshotTest`,
 * verify with `./gradlew :app:verifyRoborazziDebug --tests …`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h1100dp-xxhdpi")
class WallpaperRowsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                WallpaperRowsGallerySection(showLive = false)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun galleryLight() {
        shot(Appearance.Light, "settings/wallpaper_rows_light.png")
    }

    @Test
    fun galleryDark() {
        shot(Appearance.Dark, "settings/wallpaper_rows_dark.png")
    }
}
