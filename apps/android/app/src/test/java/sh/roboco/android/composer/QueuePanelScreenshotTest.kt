package sh.roboco.android.composer

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.hazeSource
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
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.session.SessionChrome

/**
 * The queue panel goldens (android-ui-parity #82), recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`:
 * the ticket's four fixtures — F1, F3, F5 and FG — in light and dark,
 * each in its own 378 dp [GlassStage] over the striped backdrop, plus
 * F5 with row 0's ⋯ menu open (dark). The stage is the backdrop's
 * sibling, never inside its `hazeSource`, and the [GlassMenuHost] gives
 * the ⋯ rows their host.
 *
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the GalleryCapture convention; Robolectric blur is not byte-stable),
 * reset in `@After`. Nothing animates — every fixture is a fixed value.
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`: `queue_panel_f1_light.png` …
 * `queue_panel_f5_menu_dark.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h460dp-xxhdpi")
class QueuePanelScreenshotTest {

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

    private fun fixtureItems(key: String): List<SessionChrome.QueuedItem> = when (key) {
        "f1" -> QueuePanelFixtures.F1
        "f3" -> QueuePanelFixtures.F3
        "f5" -> QueuePanelFixtures.F5
        "fg" -> QueuePanelFixtures.FG
        else -> error("unknown queue fixture $key")
    }

    /** The panel in a 378 dp slot over the striped backdrop, in the host. */
    private fun mount(appearance: Appearance, key: String) {
        val items = fixtureItems(key)
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassMenuHost(hazeState = LocalHazeState.current!!) {
                        GlassStage(
                            modifier = Modifier.width(378.dp),
                            height = 200.dp,
                            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                        ) {
                            QueuePanel(
                                items = items,
                                glassVisible = true,
                                animateGlass = false,
                                onAction = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(appearance: Appearance, key: String) {
        mount(appearance, key)
        val mode = if (appearance == Appearance.Dark) "dark" else "light"
        compose.onRoot().captureRoboImage("composer/queue_panel_${key}_$mode.png")
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun f1Light() = shot(Appearance.Light, "f1")

    @Test
    fun f1Dark() = shot(Appearance.Dark, "f1")

    @Test
    fun f3Light() = shot(Appearance.Light, "f3")

    @Test
    fun f3Dark() = shot(Appearance.Dark, "f3")

    @Test
    fun f5Light() = shot(Appearance.Light, "f5")

    @Test
    fun f5Dark() = shot(Appearance.Dark, "f5")

    @Test
    fun fgLight() = shot(Appearance.Light, "fg")

    @Test
    fun fgDark() = shot(Appearance.Dark, "fg")

    /** F5 with row 0's ⋯ menu open (Move Up greyed, Remove destructive). */
    @Test
    fun f5MenuDark() {
        mount(Appearance.Dark, "f5")
        compose.onNodeWithTag("queue-more-0").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/queue_panel_f5_menu_dark.png")
    }
}
