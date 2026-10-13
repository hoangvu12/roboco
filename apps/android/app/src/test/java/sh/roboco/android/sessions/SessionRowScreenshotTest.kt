package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The session-row goldens (android-ui-parity #37): the gallery fixture in
 * light and dark and the font-scale-1.3 variant, each with the glyph clock
 * frozen at t = 0 so the spinner row is byte-stable.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp), `w411dp` as the
 * ticket pins (the width the geometry table is stated against). Roborazzi
 * paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * so the goldens land at `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.SessionRowScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SessionRowScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The golden host: the section on its palette background (no frame). */
    private fun shot(appearance: Appearance, file: String, fontScale: Float = 1f) {
        val density = Density(3f, fontScale)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                RobocoTheme(appearance) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        Box(Modifier.background(RobocoTheme.palette.background)) {
                            SessionRowGallerySection()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun lightGolden() {
        shot(Appearance.Light, "sessions/session_row_light.png")
    }

    @Test
    fun darkGolden() {
        shot(Appearance.Dark, "sessions/session_row_dark.png")
    }

    @Test
    fun lightFontScale130Golden() {
        shot(Appearance.Light, "sessions/session_row_light_fontscale130.png", fontScale = 1.3f)
    }
}
