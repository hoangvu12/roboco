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
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The status-widget goldens (android-ui-parity #123; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the deterministic [StatusWidgetsFixture] in light and dark at
 * `w390dp-h250dp-xhdpi` (d = 2) on the `background` role — the glyph clock
 * frozen at 0 and every Working row's clock at [FIXTURE_NOW], so the
 * seconds never move between runs.
 *
 * What the goldens must show, side by side with an iPhone showing a live
 * turn: a row of three 15.75-dp ToolStatus boxes — the animated 2×3 violet
 * spinner (frozen at cycle 0), a grey `#97979F` checkmark, a red
 * `#DC2626` xmark (dark: `#6B6B72` / `#F87171`); the subagent-card mock
 * (354 × 33.75, radius 10.125, `#000000` @0.03 fill, `#000000` @0.0945
 * hairline, the `AGENT_TILE` at x = 9) with its trailing spinner 9 dp from
 * the right edge; and three 354 × 36 trailer rows: `Working…`,
 * `Working…  1m 15s` and `Writing…  1h 1m` — the pastel 3×3 trailer
 * glyph 14 × 14 at x = 0, the word at x = 22 in `#62626A` (dark
 * `#A9A9AE`) and the suffix in `#97979F` (dark `#6B6B72`), the suffix
 * fainter than the word, nothing clipped.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StatusWidgetsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h250dp-xhdpi")
    fun statusWidgetsLight() {
        shot(Appearance.Light, "transcript/status-widgets-light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h250dp-xhdpi")
    fun statusWidgetsDark() {
        shot(Appearance.Dark, "transcript/status-widgets-dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        StatusWidgetsFixture()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
