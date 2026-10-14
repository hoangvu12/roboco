package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
 * The image-tile-states goldens (ticket 137; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the deterministic [ImageTileStatesFixture] (gallery rows 1–2) in light
 * and dark at `w393dp-h852dp-xxhdpi` (d = 3) on the `background` role
 * box, with the test clock frozen so the Loading spinner sits at step 0
 * (ticket 08's rule: no frame arrives, the golden stays deterministic).
 *
 * What the goldens must show, side by side with pr817 (no screenshots
 * published — compare against the Spec numbers): three 260 dp tiles
 * stacked at x = 18 with 14 dp corners — the spinner 8 dp above
 * "Loading image" (Geist Regular 13, `secondary`, 12 dp insets,
 * vertically centred), the 960 × 540 art aspect-**fit** (letterboxed top
 * and bottom) on `Palette.elevated`, and the three-line "Image
 * unavailable … Tap to retry." text; then three 76 dp thumbnails
 * right-aligned at x = 135/217/299 with 12 dp corners — the 20 dp
 * spinner, the 540 × 960 portrait pillarboxed, and the 20 dp warning
 * glyph (compact mode: no label).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageTileStatesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTileStatesLight() {
        shot(Appearance.Light, "transcript/image_tile_states_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTileStatesDark() {
        shot(Appearance.Dark, "transcript/image_tile_states_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.mainClock.autoAdvance = false // the spinner sits at step 0
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ImageTileStatesFixture()
                }
            }
        }
        compose.waitForIdle()
        // Let the Failed tile's effect land (its null load) without moving
        // the spinner off step 0: 80 ms of frames is < the 125 ms step.
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }
}
