package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The jump button's goldens (android-ui-parity #131): the ticket's stage
 * fixture — [JumpToLatestStage], the striped haze-source backdrop, 74's
 * stack stand-in and the button at 74's jump anchor — in light and dark
 * at `w412dp-h300dp-xxhdpi` (the window is the stage itself, so the
 * golden is exactly the 412 × 300 dp frame):
 *
 * - `jump_shown_{light,dark}.png` — settled shown (the first composition
 *   starts at `p = 1`, the retained scrolled-up session rule);
 * - `jump_mid_show_{light,dark}.png` — the clock frozen 96 ms after the
 *   show: the button is smaller (≈0.87–0.91 scale) and translucent;
 * - `jump_hidden_{light,dark}.png` — nothing drawn above the capsule
 *   (the glass is skipped entirely while idle-hidden).
 *
 * Static positions and the glass fallback tier so nothing animates while
 * capturing. Recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`; paths are relative to `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h300dp-xxhdpi")
class JumpToLatestScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().captureRoboImage("session/$file")
    }

    // -------------------------------------------------------------- goldens

    @Test
    fun jumpShownLight() {
        shot(Appearance.Light, "jump_shown_light.png") { JumpToLatestStage(visible = true) }
    }

    @Test
    fun jumpShownDark() {
        shot(Appearance.Dark, "jump_shown_dark.png") { JumpToLatestStage(visible = true) }
    }

    /**
     * 96 ms into the show: smaller and translucent. The spring's launch
     * costs a frame or two, so the frozen value is the table's p at
     * 64–96 ms (scale ≈0.87–0.91, alpha ≈0.53–0.78) — the golden records
     * exactly this frame, deterministically.
     */
    @Test
    fun jumpMidShowLight() {
        midShow(Appearance.Light, "jump_mid_show_light.png")
    }

    @Test
    fun jumpMidShowDark() {
        midShow(Appearance.Dark, "jump_mid_show_dark.png")
    }

    @Test
    fun jumpHiddenLight() {
        shot(Appearance.Light, "jump_hidden_light.png") { JumpToLatestStage(visible = false) }
    }

    @Test
    fun jumpHiddenDark() {
        shot(Appearance.Dark, "jump_hidden_dark.png") { JumpToLatestStage(visible = false) }
    }

    // ------------------------------------------------------------- helpers

    /** Starts hidden, flips the rule on, freezes the clock 96 ms later. */
    private fun midShow(appearance: Appearance, file: String) {
        var visible by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(appearance) { JumpToLatestStage(visible = visible) }
        }
        compose.waitForIdle()
        visible = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        compose.onRoot().captureRoboImage("session/$file")
    }
}
