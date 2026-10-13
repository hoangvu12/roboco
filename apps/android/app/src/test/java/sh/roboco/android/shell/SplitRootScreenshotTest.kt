package sh.roboco.android.shell

import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SplitRootFixture

/**
 * The split shell's goldens (android-ui-parity #33): the gallery's
 * fixture ([SplitRootFixture] — fixed text, the empty field, no
 * time-dependent content) in the ticket's four shots:
 *
 * - `SplitRoot_shown_light` / `SplitRoot_shown_dark` — 1280 × 800 dp,
 *   the sidebar shown: 360 dp rows beside the detail column, the 1-px
 *   hairline on the sidebar's trailing edge;
 * - `SplitRoot_hidden_light` — the sidebar hidden: the detail fills the
 *   window;
 * - `SplitRoot_narrow840_light` — 840 × 1200 dp: the detail is 480 dp
 *   (exactly at the breakpoint, no hysteresis anywhere in this ticket).
 *
 * Recorded by `recordRoborazziDebug`, verified by `verifyRoborazziDebug`;
 * paths are relative to `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class SplitRootScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String, sidebarVisible: Boolean) {
        // The fixture's shell is constructed directly (the gallery uses
        // rememberTabletShellState): a hidden sidebar must be settled
        // before the capture, with no animation running.
        val shell = TabletShellState(
            initialChatId = null,
            initialSidebarVisible = sidebarVisible,
            initialDetailRoot = DetailRoot.Draft(prompt = null, focusOnAppear = false),
        )
        compose.setContent {
            RobocoTheme(appearance) { SplitRootFixture(shell = shell) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun sidebarShownLight() {
        shot(Appearance.Light, "shell/SplitRoot_shown_light.png", sidebarVisible = true)
    }

    @Test
    fun sidebarShownDark() {
        shot(Appearance.Dark, "shell/SplitRoot_shown_dark.png", sidebarVisible = true)
    }

    @Test
    fun sidebarHiddenLight() {
        shot(Appearance.Light, "shell/SplitRoot_hidden_light.png", sidebarVisible = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h1200dp-xhdpi")
    fun narrow840dpLight() {
        shot(Appearance.Light, "shell/SplitRoot_narrow840_light.png", sidebarVisible = true)
    }
}
