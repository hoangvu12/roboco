package sh.roboco.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.ui.PlaceholderScreen

/**
 * The seed screenshot test (android-app#13): one golden of the placeholder
 * in light and dark, so ticket 14 can add screenshot tests without touching
 * the build. Goldens are committed under `src/test/screenshots/` (the
 * `roborazzi { outputDir }`); `recordRoborazziDebug` re-records them,
 * `verifyRoborazziDebug` verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PlaceholderScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun placeholderLight() {
        compose.setContent { PlaceholderScreen(CoreStatus.Loaded("0.0.0")) }
        compose.onRoot().captureRoboImage("placeholder_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "night")
    fun placeholderDark() {
        compose.setContent { PlaceholderScreen(CoreStatus.Loaded("0.0.0")) }
        compose.onRoot().captureRoboImage("placeholder_dark.png")
    }

    @Test
    fun placeholderCoreFailure() {
        compose.setContent { PlaceholderScreen(CoreStatus.Failed("stale jniLibs")) }
        compose.onRoot().captureRoboImage("placeholder_failure.png")
    }
}
