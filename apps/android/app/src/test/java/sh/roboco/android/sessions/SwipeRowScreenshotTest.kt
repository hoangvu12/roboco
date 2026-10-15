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
 * The swipe-row goldens (android-ui-parity #44): the static block —
 * closed, leading open (74 dp), trailing open (148 dp), trailing at
 * 50 % (equal split, unarmed), trailing armed at 70 % (Archive covers
 * the gap) and the archived row (74 dp) — in light and dark, at the
 * ticket's 360 dp width so the 60 % arming rule lands exactly on
 * 180 dp / 252 dp.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp). Roborazzi paths
 * are relative to `roborazzi { outputDir }` (`src/test/screenshots`), so
 * the goldens land at `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.sessions.SwipeRowScreenshotTest`, verified by
 * `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h891dp-xxhdpi")
class SwipeRowScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The golden host: the static block on its palette background (no frame). */
    private fun shot(appearance: Appearance, file: String) {
        val density = Density(3f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                RobocoTheme(appearance) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        Box(Modifier.background(RobocoTheme.palette.background)) {
                            SwipeRowStaticBlock()
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
        shot(Appearance.Light, "sessions/swipe_rows_light.png")
    }

    @Test
    fun darkGolden() {
        shot(Appearance.Dark, "sessions/swipe_rows_dark.png")
    }
}
