package sh.roboco.android.pairing

import androidx.compose.foundation.layout.WindowInsets
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
 * Ticket 34's goldens: the pairing root on the ticket's device
 * (`w393dp-h852dp-xxhdpi`, safe insets 59/34 injected — Robolectric reports
 * zero system-bar insets), light and dark, one shot per state:
 *
 * - `pairing_root_none` — no status node, tappable capsule;
 * - `pairing_root_redeeming` — the redeeming line in `secondary`, ticket
 *   08's busy spinner leading the still-visible "Pair an engine";
 * - `pairing_root_error` — the ticket's example error copy in `danger`.
 *
 * The clock is frozen and exactly one frame is run before each capture, so
 * the spinner sits at step 0 and the goldens stay byte-stable. Recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`;
 * the files land under `src/test/screenshots/pairing/`. Colour checks (page
 * `#F3F3F5`/`#060606`, inks) are done by pixel-sampling these goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PairingRootScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(file: String, status: PairingRootStatus, appearance: Appearance) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(appearance) {
                PairingRoot(
                    status = status,
                    onPair = {},
                    safeInsets = WindowInsets(top = 59.dp, bottom = 34.dp),
                )
            }
        }
        compose.waitForIdle()
        // One frame, then capture: 0 ms of animation time (the ticket's
        // deterministic-golden rule) — the spinner draws its step-0 spokes.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("pairing/$file")
    }

    // -------------------------------------------------------------- none

    @Test
    fun noneLight() {
        shot("pairing_root_none_light.png", PairingRootStatus.None, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun noneDark() {
        shot("pairing_root_none_dark.png", PairingRootStatus.None, Appearance.Dark)
    }

    // -------------------------------------------------------- redeeming

    @Test
    fun redeemingLight() {
        shot("pairing_root_redeeming_light.png", PairingRootStatus.Redeeming, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun redeemingDark() {
        shot("pairing_root_redeeming_dark.png", PairingRootStatus.Redeeming, Appearance.Dark)
    }

    // ------------------------------------------------------------- error

    @Test
    fun errorLight() {
        shot(
            "pairing_root_error_light.png",
            PairingRootStatus.Error(PairingRootExampleError),
            Appearance.Light,
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun errorDark() {
        shot(
            "pairing_root_error_dark.png",
            PairingRootStatus.Error(PairingRootExampleError),
            Appearance.Dark,
        )
    }
}
