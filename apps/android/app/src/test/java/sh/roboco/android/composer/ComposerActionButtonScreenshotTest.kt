package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * The action-button goldens (android-ui-parity #58), recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.ComposerActionButtonScreenshotTest`
 * and verified by `verifyRoborazziDebug`:
 *
 *  - `action_button_{light,dark}.png` — the ticket's fixture: the
 *    five-state row (34 dp buttons, 16 dp gaps — the shared
 *    [ComposerActionButtonFixtureRow]) on ticket 22's busy
 *    [GlassStage] backdrop, at `w411dp-h891dp-xhdpi`;
 *  - `action_button_menu_light.png` — the same row after a long press on
 *    the queue button (`canSteer = true`): the "While the agent works"
 *    glass menu open over it, settled.
 *
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the GalleryCapture convention): the menu panel draws the fallback
 * fill and nothing animates — the row's first composition never animates,
 * so the goldens are byte-stable.
 *
 * Paths are relative to `roborazzi { outputDir }`
 * (`src/test/screenshots`), landing under `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class ComposerActionButtonScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeMotionAndForceFallbackTier() {
        ReduceMotion.debugOverride = true
        Glass.forceFallback = true
    }

    @After
    fun restore() {
        ReduceMotion.debugOverride = null
        Glass.forceFallback = false
    }

    private fun rowShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        GlassStage(modifier = Modifier.align(Alignment.Center), height = 240.dp) {
                            ComposerActionButtonFixtureRow()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/$file")
    }

    @Test
    fun light() {
        rowShot(Appearance.Light, "action_button_light.png")
    }

    @Test
    fun dark() {
        rowShot(Appearance.Dark, "action_button_dark.png")
    }

    /**
     * The delivery menu open: the same five-state row, long-pressed on the
     * queue button (found by its `Queue message` label — the only one),
     * the menu settled over the backdrop after the open spring.
     */
    @Test
    fun menuLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        GlassStage(modifier = Modifier.align(Alignment.Center), height = 240.dp) {
                            ComposerActionButtonFixtureRow()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Queue message")
            .performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // settle the menu's open spring
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/action_button_menu_light.png")
    }
}
