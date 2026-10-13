package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * Ticket 35's goldens: the app root with the fake roots on the ticket's
 * device (`w393dp-h852dp-xxhdpi`), one shot per state:
 *
 * - `app_root_pairing_{light,dark}` — no engines, the pairing root's fill;
 * - `app_root_phone_light` — the tab shell's `background` fill;
 * - `app_root_split_light` — the 900 dp frame (the split's `codeBackground`);
 * - `app_root_crossfade_mid_light` — Pairing → Phone, the clock frozen,
 *   16 ms (the swap frame) + 175 ms: the 50 % blend. The pixel at
 *   16 dp, 16 dp must equal the exact 50/50 mix of the two fills —
 *   `#A79BEE` ± 2 per channel: a both-layers fade would give ~`#CD9DF1`
 *   and fail. Replaces android-app#13's placeholder goldens.
 *
 * Recorded by `:app:recordRoborazziDebug`, verified by
 * `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/app_root/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AppRootScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val input = mutableStateOf<RootInput>(RootInput.Ready(hasEngines = false))

    private fun shot(file: String, appearance: Appearance) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Root(appearance) }
        compose.waitForIdle()
        // One frame, then capture: 0 ms of animation time.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("app_root/$file")
    }

    @Composable
    private fun Root(appearance: Appearance) {
        RobocoTheme(appearance) {
            val palette = RobocoTheme.palette
            AppRoot(
                input = input.value,
                state = remember { AppRootState() },
                presentations = remember { RootPresentations() },
                pairing = { FakeRoot("Pairing", palette.accent, palette.text) },
                phone = { FakeRoot("Phone", palette.background, palette.text) },
                split = { FakeRoot("Split", palette.codeBackground, palette.text) },
            )
        }
    }

    // ------------------------------------------------------------- pairing

    @Test
    fun pairingLight() {
        shot("app_root_pairing_light.png", Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun pairingDark() {
        shot("app_root_pairing_dark.png", Appearance.Dark)
    }

    // --------------------------------------------------------------- phone

    @Test
    fun phoneLight() {
        input.value = RootInput.Ready(hasEngines = true)
        shot("app_root_phone_light.png", Appearance.Light)
    }

    // --------------------------------------------------------------- split

    @Test
    @Config(sdk = [36], qualifiers = "w900dp-h852dp-xxhdpi")
    fun splitLight() {
        input.value = RootInput.Ready(hasEngines = true)
        shot("app_root_split_light.png", Appearance.Light)
    }

    // ------------------------------------------------------------ the fade

    @Test
    fun crossfadeMidLight() {
        input.value = RootInput.Ready(hasEngines = false)
        compose.mainClock.autoAdvance = false
        compose.setContent { Root(Appearance.Light) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // Pairing → Phone: the frame the swap starts, then 175 ms — the
        // 50 % blend point of the 350 ms easeInOut.
        // The frozen clock: the test-side write needs explicit apply
        // notifications, then one frame for the composition that sees it
        // (the swap effect starts the fade there); the fade's first frame
        // is the next one.
        compose.runOnUiThread {
            input.value = RootInput.Ready(hasEngines = true)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(175)
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("app_root/app_root_crossfade_mid_light.png")

        // 16 dp, 16 dp (48 px at xxhdpi), away from the labels: the exact
        // mix of #5B43E8 (Pairing) over #F3F3F5 (Phone) — no background
        // shows through, and both layers never fade together.
        val px = compose.onRoot().captureToImage().toPixelMap()[48, 48].toArgb()
        for (shift in intArrayOf(16, 8, 0)) {
            val expected = (0xFFA79BEE.toInt() shr shift) and 0xFF
            val actual = (px shr shift) and 0xFF
            assertTrue(
                "channel $shift: ${actual.toString(16)} vs ${expected.toString(16)}",
                abs(actual - expected) <= 2,
            )
        }
    }

    // -------------------------------------------------------- core failure

    @Test
    fun coreFailedLight() {
        input.value = RootInput.CoreFailed("libroboco_core.so not found")
        shot("app_root_core_failed_light.png", Appearance.Light)
    }
}
