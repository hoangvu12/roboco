package sh.roboco.android.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import sh.roboco.android.design.chipMenuELines
import sh.roboco.android.design.chipMenuM1Lines
import sh.roboco.android.design.chipMenuM2Lines
import sh.roboco.android.design.chipMenuPLines
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuPanel

/**
 * The chip-menu goldens (android-ui-parity #89), recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.ChipMenuScreenshotTest`
 * and verified by `verifyRoborazziDebug`: the ticket's four panels —
 * M1 (model, resolved: "Opus 5" checked), M2 (model, loading), E
 * (reasoning effort: "High" checked) and P (pull request, the long
 * title) — side by side over ticket 22's [GalleryBackdrop], in light
 * and dark. The wide `w1300dp` device fits the four 250–300 dp panels
 * in one row; mdpi keeps the golden byte-stable (1 dp = 1 px).
 *
 * In Robolectric the panels draw ticket 07's fallback fill
 * (`Glass.forceFallback = true`, the golden convention). Paths are
 * relative to `src/test/screenshots`, landing under `composer/`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1300dp-h860dp-mdpi")
class ChipMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    @Test
    fun chipMenusLight() {
        shot(Appearance.Light, "chip_menus_light.png")
    }

    @Test
    fun chipMenusDark() {
        shot(Appearance.Dark, "chip_menus_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize()) {
                    GalleryBackdrop(Modifier.fillMaxSize())
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlassMenuPanel(
                            lines = chipMenuM1Lines(),
                            onRow = {},
                            onSubmenu = {},
                            onBack = {},
                        )
                        GlassMenuPanel(
                            lines = chipMenuM2Lines(),
                            onRow = {},
                            onSubmenu = {},
                            onBack = {},
                        )
                        GlassMenuPanel(
                            lines = chipMenuELines(),
                            onRow = {},
                            onSubmenu = {},
                            onBack = {},
                        )
                        GlassMenuPanel(
                            lines = chipMenuPLines(),
                            onRow = {},
                            onSubmenu = {},
                            onBack = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/$file")
    }
}
