package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * The copy-code goldens (ticket 119; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the deterministic [CopyCodeFixture] (`copied = false` / `true`) in
 * light and dark at `w393dp-h852dp-xxhdpi` (d = 3) on the `background`
 * role box, the card at the row origin x = 18.
 *
 * What the goldens must show, side by side with an iPhone showing a
 * fenced code block: a 357 × 104 dp mock code card with 12 dp circular
 * corners, `codeBackground` fill (`#FAFAFB` / `#0B0B0D`) and the
 * `codeBorder` hairline, and in its top-right cell — (313, 0, 44, 32) —
 * the 13 pt glyph: `square.on.square` at Medium in `tertiary`
 * (`#97979F` / `#6B6B72`) for the idle golden, `checkmark` at Semibold
 * in `accent` (`#5B43E8` / `#8B7CF6`) for the copied one, centred in the
 * cell with no background, border or pill of its own.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CopyCodeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun copyCodeIdleLight() {
        shot(Appearance.Light, copied = false, "transcript/copy_code_idle_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun copyCodeIdleDark() {
        shot(Appearance.Dark, copied = false, "transcript/copy_code_idle_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun copyCodeCopiedLight() {
        shot(Appearance.Light, copied = true, "transcript/copy_code_copied_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun copyCodeCopiedDark() {
        shot(Appearance.Dark, copied = true, "transcript/copy_code_copied_dark.png")
    }

    private fun shot(appearance: Appearance, copied: Boolean, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    // The row origin: MARGIN_X 18 (rows.rs:124).
                    Column(Modifier.padding(start = 18.dp)) {
                        CopyCodeFixture(copied = copied)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
