package sh.roboco.android.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The Settings screen goldens (android-ui-parity #103), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`:
 *
 * - `settings_screen_top_{light,dark}.png` — the screen at rest at the
 *   reference phone's 402 × 874 dp (1 pt = 1.1443 px), `topInset` fixed
 *   at 24: the large "Settings" title, the Devices, Notifications (99's
 *   off fixture), Appearance (System checked), Wallpaper (nothing set),
 *   Sessions (Group by Project on) and About sections — the page fill
 *   `#F3F3F5` / `#060606`, the cards `#FFFFFF` / `#111113` (the
 *   [SettingsScreenGallerySection] fixture, one source with the gallery);
 * - `settings_screen_bottom_light.png` — scrolled to the end under the
 *   phone chrome ([BottomChromeInsets] 24 / 150, the gesture-navigation
 *   values): the last About card ends 166 dp above the window bottom (30's
 *   padding + 16), so the rows clear the "New session" accessory and the
 *   tab pill.
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22): the scaffold's soft scroll edge draws its
 * non-blur fallback, so the goldens stay byte-stable. Nothing animates at
 * rest, so the clock stays frozen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SettingsScreenScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) { content() }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    // ------------------------------------------------------ at rest (top)

    @Test
    fun settingsScreenTopLight() {
        shot(Appearance.Light, "settings/settings_screen_top_light.png") {
            ActionSheetHost { SettingsScreenGallerySection() }
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun settingsScreenTopDark() {
        shot(Appearance.Dark, "settings/settings_screen_top_dark.png") {
            ActionSheetHost { SettingsScreenGallerySection() }
        }
    }

    // ------------------------------------------------- scrolled to the end

    @Test
    fun settingsScreenBottomLight() {
        val listState = LazyListState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp),
                ) {
                    ActionSheetHost { SettingsScreenGallerySection(listState = listState) }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        // To the end (scrollBy clamps at the last item): the last card
        // stops 166 dp above the bottom — 150 (the chrome group) + 16.
        val px = with(compose.density) { 20_000.dp.toPx() }
        compose.runOnUiThread { runBlocking { listState.scrollBy(px) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("settings/settings_screen_bottom_light.png")
    }
}
