package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState

/**
 * Ticket 28's goldens: the chrome at p = 0, 0.5 and 1 over 22's striped
 * [GlassStage] backdrop (the chrome the backdrop's sibling, never inside
 * its `hazeSource`), light and dark, at 402 × 874 dp and density 3 with
 * the clock frozen: Sessions selected, the accessory's live counts
 * working 2 / awaiting 1.
 *
 *  - `p0` — the pill 290 × 62 at x 21, the accessory 360 × 48 at x 21,
 *    the "New session" label visible;
 *  - `p50` — the mid-morph row;
 *  - `p100` — only the chat icon in the left 48 dp circle, and the
 *    accessory shows the plate "+", the frozen 12 dp spinner glyph
 *    (working > 0 → `.spinner`, `MainTabController.swift:328`) and
 *    "2 working · 1 needs you", with no "New session" text.
 *
 * Recorded by `:app:recordRoborazziDebug` and verified by
 * `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class TabBarMinimizeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The chrome at a fixed morph position [p] — no animation to settle,
     * only the platters, which start at their Sessions rest values.
     */
    private fun shot(file: String, p: Float) {
        val minimized = p >= 0.999f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize()) {
                        val haze = rememberHazeState()
                        CompositionLocalProvider(LocalHazeState provides haze) {
                            GlassStage(
                                height = 874.dp,
                                backdropModifier = Modifier.hazeSource(haze),
                            ) {
                                TabChromeLayout(
                                    progress = { p },
                                    bottomInset = 21.dp,
                                    hasAccessory = true,
                                    modifier = Modifier.matchParentSize(),
                                    pill = {
                                        GlassTabPill(
                                            selected = TabId.Sessions,
                                            onTabTap = {},
                                            minimizeProgress = { p },
                                            minimized = minimized,
                                            onRestore = {},
                                            expandedWidth = tabPillExpandedWidth(402f).dp,
                                        )
                                    },
                                    search = { GlassSearchButton(TabId.Sessions, onTabTap = {}) },
                                    accessory = {
                                        NewSessionAccessory(
                                            counts = LiveCounts(working = 2, awaiting = 1),
                                            onTap = {},
                                            inline = minimized,
                                            labelAlpha = { 1f - p.coerceIn(0f, 1f) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$file")
    }

    // ------------------------------------------------------------------ p0

    @Test
    fun p0Light() {
        shot("tab_bar_minimize_p0_light.png", 0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun p0Dark() {
        shot("tab_bar_minimize_p0_dark.png", 0f)
    }

    // ---------------------------------------------------------------- p50

    @Test
    fun p50Light() {
        shot("tab_bar_minimize_p50_light.png", 0.5f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun p50Dark() {
        shot("tab_bar_minimize_p50_dark.png", 0.5f)
    }

    // --------------------------------------------------------------- p100

    @Test
    fun p100Light() {
        shot("tab_bar_minimize_p100_light.png", 1f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun p100Dark() {
        shot("tab_bar_minimize_p100_dark.png", 1f)
    }
}
