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
 * The image-tile goldens (ticket 66; recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`): the deterministic
 * [ImageTileFixture] in light and dark at `w393dp-h852dp-xxhdpi` (d = 3)
 * on the `background` role box, loaded (the sync-hit path, picture at
 * alpha 1 in the first frame) and placeholder (nothing loaded, the
 * fixture's own `ChipBackground` boxes showing through), plus the overlay
 * clip — a full-size red box in the 260 dp tile's `overlay` slot must come
 * out with the tile's continuous rounded corners.
 *
 * What the goldens must show, side by side with an iOS 393 pt simulator:
 * the three 76 dp thumbnails at x = 135/217/299 (6 dp gaps, right-aligned
 * to the 337 pt column end) with 12 pt corners; the 260 dp image row at
 * x = 18, y = 100 with 14 pt corners; the 1200 × 800 fixture picture
 * aspect-**fit** (137: centred, letterboxed top and bottom) on the
 * `Palette.elevated` tile background; and the #E7E7EB / #1C1C20
 * placeholders when nothing is loaded.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageTileScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTileLoadedLight() {
        shot(Appearance.Light, loaded = true, "transcript/image_tile_loaded_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTileLoadedDark() {
        shot(Appearance.Dark, loaded = true, "transcript/image_tile_loaded_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTilePlaceholderLight() {
        shot(Appearance.Light, loaded = false, "transcript/image_tile_placeholder_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTilePlaceholderDark() {
        shot(Appearance.Dark, loaded = false, "transcript/image_tile_placeholder_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun imageTileOverlayClipLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ImageTileOverlayFixture()
                }
            }
        }
        compose.onRoot().captureRoboImage("transcript/image_tile_overlay_clip_light.png")
    }

    private fun shot(appearance: Appearance, loaded: Boolean, file: String) {
        // The placeholder never loads, so its Loading spinner loops on
        // withFrameMillis forever: an auto-advancing clock never idles.
        // Frozen, the spinner sits at step 0 (as ImageTileStatesScreenshotTest).
        if (!loaded) compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ImageTileFixture(loaded = loaded)
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
