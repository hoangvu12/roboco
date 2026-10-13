package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The disclosure goldens (ticket 120; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the deterministic [DisclosureFixture] (`static = true`) in light and
 * dark at `w393dp-h852dp-xxhdpi` (d = 3) on the `background` role box.
 *
 * What the goldens must show, side by side with the iOS reference on a
 * finished tool group: an expanded and a collapsed tool header at x = 18 —
 * the `zi_alt_arrow_down` chevron at (24.19, 6.75) 15.75² tinted
 * `#62626A` light / `#A9A9AE` dark, pointing down then right, stroke ≈
 * 0.98 dp — with the "Ran 3 commands" / "Read 2 files" stand-ins at
 * x = 49.5; then an idle tool row (nothing drawn) and a pressed one whose
 * whole 357 × 36 dp rect is the square `controlFill` `0x1327272C` light /
 * `0x13E8E8EA` dark, over its "Ran ls -la" / "Ran git status" stand-ins.
 *
 * The chevron memory is cleared first: a first sighting never animates, so
 * the goldens stay deterministic whatever ran before in this JVM.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DisclosureScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        ToolChevronMemory.clearForTest()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun disclosureTogglesLight() {
        shot(Appearance.Light, "transcript/disclosure_toggles_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun disclosureTogglesDark() {
        shot(Appearance.Dark, "transcript/disclosure_toggles_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    DisclosureFixture(static = true)
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
