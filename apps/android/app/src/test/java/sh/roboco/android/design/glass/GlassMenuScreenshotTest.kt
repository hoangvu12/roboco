package sh.roboco.android.design.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.fixtureALines
import sh.roboco.android.design.fixtureBLines
import sh.roboco.android.design.fixtureCLines
import sh.roboco.android.design.fixtureDLines

/**
 * The menu goldens (android-ui-parity #15), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`: the
 * ticket's fixtures A–D, each a static [GlassMenuPanel] over ticket 22's
 * [GalleryBackdrop], in light and dark, on the `w393dp-h852dp-xxhdpi`
 * device — 8 images under `src/test/screenshots/design/glass/`.
 *
 * In Robolectric the panel draws ticket 07's fallback fill
 * (`Glass.forceFallback = true`, the golden convention): blur does not run
 * there. The panel is anchored bottom-right, like the iOS 26 capture's
 * queue-actions menu (which grew up out of a low anchor).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class GlassMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun forceFallbackTier() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    private fun shot(appearance: Appearance, lines: List<MenuLine>, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize()) {
                    GalleryBackdrop(Modifier.fillMaxSize())
                    GlassMenuPanel(
                        lines = lines,
                        onRow = {},
                        onSubmenu = {},
                        onBack = {},
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 24.dp, bottom = 140.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun fixtureALight() {
        shot(Appearance.Light, fixtureALines(), "design/glass/glass_menu_a_light.png")
    }

    @Test
    fun fixtureADark() {
        shot(Appearance.Dark, fixtureALines(), "design/glass/glass_menu_a_dark.png")
    }

    @Test
    fun fixtureBLight() {
        shot(Appearance.Light, fixtureBLines(), "design/glass/glass_menu_b_light.png")
    }

    @Test
    fun fixtureBDark() {
        shot(Appearance.Dark, fixtureBLines(), "design/glass/glass_menu_b_dark.png")
    }

    @Test
    fun fixtureCLight() {
        shot(Appearance.Light, fixtureCLines(), "design/glass/glass_menu_c_light.png")
    }

    @Test
    fun fixtureCDark() {
        shot(Appearance.Dark, fixtureCLines(), "design/glass/glass_menu_c_dark.png")
    }

    @Test
    fun fixtureDLight() {
        shot(Appearance.Light, fixtureDLines(), "design/glass/glass_menu_d_light.png")
    }

    @Test
    fun fixtureDDark() {
        shot(Appearance.Dark, fixtureDLines(), "design/glass/glass_menu_d_dark.png")
    }
}
