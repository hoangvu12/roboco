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
 * The tool-rail goldens (ticket 121; recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`): the deterministic
 * [ToolRailFixture] — the static [RailFixture3] and a brand-new live 2-row
 * draw-in — in light and dark at `w393dp-h852dp-xxhdpi` (d = 3), on the
 * `background` role box, with stand-in row content under the rail.
 *
 * What the goldens must show, side by side with the iOS reference on an
 * expanded tool group at the default text size: the 1 dp trunk at
 * x = 18 + 14.0625 dp under the header chevron's centre, the quadratic
 * elbows (bend 6.75 dp) at each row's mid, the branches ending at 31.5 dp
 * before the 18 dp icon squares, the trunk running through the middle
 * row's 60 dp open detail into row 2, the stroke `0x29000000` light /
 * `0x1FFFFFFF` dark — and, in the draw-in shot, row 0's branch about half
 * drawn with its cover about half faded over the icon and label stand-ins
 * while row 1's trunk just starts, its cover still fully opaque.
 *
 * `ToolRailMemory` is reset first and a fake clock sits past the quiet
 * window: the plan is deterministic whatever ran before in this JVM. The
 * draw-in shot freezes the main clock and advances 13 frames of 16 ms past
 * the first animation frame (elapsed = 208 ms: inc0 1, br0 0.4226,
 * cover0 α 0.5774, cont0 0.9165, inc1 0.0765).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ToolRailScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        ToolRailMemory.resetForTest()
        ToolRailMemory.clock = { 20_000L }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun toolRailSettledLight() {
        shot(Appearance.Light, "transcript/tool_rail_settled_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun toolRailSettledDark() {
        shot(Appearance.Dark, "transcript/tool_rail_settled_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ToolRailFixture(RailFixture3, live = false, key = 1uL, token = null)
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun toolRailDrawin208msLight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ToolRailFixture(railDemoSpec(2), live = true, key = 2uL, token = Any())
                }
            }
        }
        // The first animation frame: the composition applied, the effect's
        // t0 landed on it. elapsed = 0 — every stroke hidden, both covers
        // opaque, so no content flashes.
        compose.mainClock.advanceTimeByFrame()
        // 13 frames × 16 ms later: elapsed = 208 ms.
        repeat(13) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("transcript/tool_rail_drawin_208ms_light.png")
    }
}
