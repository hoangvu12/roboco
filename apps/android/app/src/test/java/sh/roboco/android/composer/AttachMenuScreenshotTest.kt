package sh.roboco.android.composer

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
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.flattenLevel

/**
 * The attach menu's goldens (android-ui-parity #87), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`: the
 * ticket's two fixtures, each a static [GlassMenuPanel] over ticket 22's
 * [GalleryBackdrop], in light and dark, on the `w390dp-h260dp-xhdpi`
 * frame — 4 images under `src/test/screenshots/composer/`.
 *
 *  - `full` — `attachMenu(hasCamera = true, clipHasImage = true)`:
 *    4 rows, a 250 × 188 dp panel (10 + 4 × 42 + 10), icons at 36 dp,
 *    titles at 61 dp, the `Palette.text` ink;
 *  - `minimal` — `attachMenu(false, false)`: Photo Library and Choose
 *    File only, 250 × 104 dp.
 *
 * Under Robolectric the panel draws ticket 07's fallback fill
 * (`Glass.forceFallback = true`, the golden convention): light
 * `#FFFFFF` @0.94, dark `#111113` @0.94, radius 22 continuous.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h260dp-xhdpi")
class AttachMenuScreenshotTest {

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

    private fun shot(appearance: Appearance, menu: Menu, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize()) {
                    GalleryBackdrop(Modifier.fillMaxSize())
                    GlassMenuPanel(
                        lines = flattenLevel(
                            title = null,
                            elements = menu.children,
                            resolved = { null },
                            isRoot = true,
                            backTitle = null,
                        ),
                        onRow = {},
                        onSubmenu = {},
                        onBack = {},
                        // Anchored like the composer's `+`: leading, low,
                        // the panel opening upward over it.
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 24.dp, bottom = 60.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/$file")
    }

    @Test
    fun fullLight() {
        shot(Appearance.Light, attachMenu(hasCamera = true, clipHasImage = true) {}, "attach_menu_full_light.png")
    }

    @Test
    fun fullDark() {
        shot(Appearance.Dark, attachMenu(hasCamera = true, clipHasImage = true) {}, "attach_menu_full_dark.png")
    }

    @Test
    fun minimalLight() {
        shot(Appearance.Light, attachMenu(hasCamera = false, clipHasImage = false) {}, "attach_menu_minimal_light.png")
    }

    @Test
    fun minimalDark() {
        shot(Appearance.Dark, attachMenu(hasCamera = false, clipHasImage = false) {}, "attach_menu_minimal_dark.png")
    }
}
