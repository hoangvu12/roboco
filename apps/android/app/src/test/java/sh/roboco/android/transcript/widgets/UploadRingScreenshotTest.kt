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
 * The upload-ring goldens (ticket 67; recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`): the deterministic
 * [UploadRingFixtureRow] in light and dark at `w393dp-h852dp-xxhdpi`
 * (d = 3) on the `background` role box, alpha frozen at 1 (the fixture
 * passes `startVisible = true`).
 *
 * What the goldens must show, side by side with
 * `testImageUploadShowsRingOnThumbnail`'s `upload-ring` XCTAttachment
 * (which needs macOS + Xcode to produce — until then, check against the
 * numeric spec): 76 dp tiles at 0.42 % and 0 % and a 40 dp tile at 100 %,
 * 8 dp apart — on the 76 dp tiles the black @ 0.38 scrim over the teal,
 * a 34 dp ring (3 dp stroke, white @ 0.3 track, white arc from 12
 * o'clock clockwise — 151.2° at 0.42, the 7.2° stub at 0), and the white
 * Geist SemiBold 10.5 dp "42%" / "0%"; on the 40 dp tile a 22 dp ring
 * with the 9 dp "100%". The fixture colours (teal/pink) stand in for
 * photo pixels and are the same in dark.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class UploadRingScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun uploadRingLight() {
        shot(Appearance.Light, "transcript/upload-ring-light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun uploadRingDark() {
        shot(Appearance.Dark, "transcript/upload-ring-dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    UploadRingFixtureRow()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
