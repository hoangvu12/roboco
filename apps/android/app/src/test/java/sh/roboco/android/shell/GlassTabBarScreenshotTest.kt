package sh.roboco.android.shell

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.shell.tablet.settledEmptySessionsFixtures

/**
 * Ticket 26's goldens: the bar on 22's striped [GlassStage] backdrop (the
 * bar the backdrop's sibling, never inside its `hazeSource`), light and
 * dark, at density 3 with the clock frozen:
 *
 *  - `tabbar_sessions` / `tabbar_settings` / `tabbar_search` (platters
 *    settled, clock advanced 400 ms) at 402 × 140 dp;
 *  - `tabbar_pressed` — a `PressInteraction.Press` emitted into the pill
 *    source, clock +400 ms: the whole pill swelled;
 *  - `tabbar_360` / `tabbar_800` — the row-width cap;
 *  - `shell_placeholder_402x874` — a full [MainTabShell] over the
 *    placeholder roots, Sessions selected.
 *
 * Recorded by `:app:recordRoborazziDebug` and verified by
 * `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/shell/`. The 402 × 874 shell golden's bottom
 * 110 dp is the side-by-side crop against the `1-off.png` reference
 * (acceptance criterion).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h140dp-xxhdpi")
class GlassTabBarScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    /**
     * The bar over the backdrop; a `Press` is emitted into the pill source
     * after composition when [press]. The clock is frozen; the first frame
     * is run, then [settleMs] of animation time so the platters settle.
     */
    private fun shot(
        file: String,
        selected: TabId = TabId.Sessions,
        settleMs: Long = 400,
        press: Boolean = false,
    ) {
        val source = MutableInteractionSource()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                Box(Modifier.fillMaxSize()) {
                    val haze = rememberHazeState()
                    CompositionLocalProvider(LocalHazeState provides haze) {
                        GlassStage(
                            modifier = Modifier.fillMaxSize(),
                            height = 140.dp,
                            backdropModifier = Modifier.hazeSource(haze),
                        ) {
                            GlassTabBar(
                                selected = selected,
                                onTabTap = {},
                                modifier = Modifier.align(Alignment.BottomCenter),
                                pillInteractionSource = source,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (press) {
            compose.runOnUiThread { source.tryEmit(PressInteraction.Press(Offset.Zero)) }
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(settleMs)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$file")
    }

    // ------------------------------------------------------------- sessions

    @Test
    fun tabbarSessionsLight() {
        shot("tabbar_sessions_light.png", TabId.Sessions)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h140dp-night-xxhdpi")
    fun tabbarSessionsDark() {
        shot("tabbar_sessions_dark.png", TabId.Sessions)
    }

    // ------------------------------------------------------------- settings

    @Test
    fun tabbarSettingsLight() {
        shot("tabbar_settings_light.png", TabId.Settings)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h140dp-night-xxhdpi")
    fun tabbarSettingsDark() {
        shot("tabbar_settings_dark.png", TabId.Settings)
    }

    // --------------------------------------------------------------- search

    @Test
    fun tabbarSearchLight() {
        // Search selected in the wrapper: the pill keeps its last pill
        // selection (26's look — the morph is the shell's, not the
        // wrapper's); ticket 29 deleted the interim inner circle, so this
        // golden is re-recorded on the batch run.
        shot("tabbar_search_light.png", TabId.Search)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h140dp-night-xxhdpi")
    fun tabbarSearchDark() {
        shot("tabbar_search_dark.png", TabId.Search)
    }

    // --------------------------------------------------------------- press

    @Test
    fun tabbarPressedLight() {
        shot("tabbar_pressed_light.png", TabId.Sessions, press = true)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h140dp-night-xxhdpi")
    fun tabbarPressedDark() {
        shot("tabbar_pressed_dark.png", TabId.Sessions, press = true)
    }

    // ---------------------------------------------------------- width caps

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h140dp-xxhdpi")
    fun tabbar360Light() {
        shot("tabbar_360_light.png", TabId.Sessions)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h140dp-night-xxhdpi")
    fun tabbar360Dark() {
        shot("tabbar_360_dark.png", TabId.Sessions)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h140dp-xxhdpi")
    fun tabbar800Light() {
        shot("tabbar_800_light.png", TabId.Sessions)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h140dp-night-xxhdpi")
    fun tabbar800Dark() {
        shot("tabbar_800_dark.png", TabId.Sessions)
    }

    // ----------------------------------------------------------- full shell

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shellPlaceholderLight() {
        shellShot("shell_placeholder_402x874_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun shellPlaceholderDark() {
        shellShot("shell_placeholder_402x874_dark.png")
    }

    private fun shellShot(file: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                // Ticket 46: the Sessions root is the real screen now — its
                // ⋯ needs a GlassMenuHost (the golden's background rows
                // change accordingly; re-record with recordRoborazziDebug).
                GlassMenuHost {
                    // The Sessions root at its settled empty state: the live
                    // FrontPageModel's first load races the capture on wall
                    // time (spinner vs "No sessions yet"; record run
                    // 38607283018 tripped the gallery's copy of this shell).
                    CompositionLocalProvider(LocalSidebarFixtures provides settledEmptySessionsFixtures()) {
                        MainTabShell(state = MainTabState(TabId.Sessions))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$file")
    }
}
