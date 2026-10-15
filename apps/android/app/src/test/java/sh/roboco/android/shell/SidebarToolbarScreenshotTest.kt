package sh.roboco.android.shell

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.sessions.SessionsScreenActions
import sh.roboco.android.sessions.sessionsOptionsMenu

/**
 * The sidebar toolbar's goldens (android-ui-parity #107) — the previews'
 * 360 × 200 dp layout on ticket 22's [GlassStage] backdrop (the toolbar is
 * the backdrop's sibling, never inside the hazeSource it blurs), 105's
 * slot geometry mimicked (full width × 44 dp, bottom 24 dp above the
 * stage), light and dark:
 *
 * - `rest` — the `[gear ⋯]` capsule and the accent `+` at rest;
 * - `half` — `visibility = 0.5`: the folder-push fade (the row's alpha
 *   over the striped backdrop);
 * - `pressed_options` — a `PressInteraction.Press` held on the ellipsis
 *   slot's source, the clock advanced 400 ms (07/08's swell on the slot,
 *   the capsule still);
 * - `menu_open` — 46's options menu opened from the ellipsis inside a
 *   [GlassMenuHost], the clock advanced past the 0.35 s open spring (the
 *   panel grows up from the slot, covering it);
 * - `rtl` — the mirrored row.
 *
 * `Glass.forceFallback = true` for the whole class (the repo's golden
 * convention): the capsule and the panel draw the fallback fill, rim and
 * shadow; nothing depends on how Robolectric reports hardware
 * acceleration. The clock is frozen for every shot (the goldens stay
 * byte-stable).
 *
 * Recorded by `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.shell.SidebarToolbarScreenshotTest`, verified by
 * `:app:verifyRoborazziDebug`, then reviewed side by side with an iPad
 * (iOS 26) capture of the sidebar — the ticket's ★ values (44/88/16, the
 * press look) get one retune there. Paths are relative to
 * `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h200dp-xxhdpi")
class SidebarToolbarScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The goldens' no-op actions: 46's rows, nothing behind them. */
    private object NoopActions : SessionsScreenActions {
        override val groupByProject: Boolean = false
        override fun setGroupByProject(on: Boolean) = Unit
        override fun newSection() = Unit
        override fun collapseAll() = Unit
        override fun toggleSection(id: String) = Unit
        override fun openPinned() = Unit
        override fun openArchived() = Unit
        override fun openSection(id: String, name: String) = Unit
        override fun renameSection(id: String, currentName: String) = Unit
        override fun deleteSection(id: String) = Unit
        override fun userSectionName(id: String): String? = null
    }

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    /**
     * Composes the previews' stage ([content] in 105's slot geometry) and
     * captures the whole window; [before] runs after the first settle
     * (the pressed shot's clock advance).
     */
    private fun shot(
        appearance: Appearance,
        file: String,
        direction: LayoutDirection = LayoutDirection.Ltr,
        before: () -> Unit = {},
        content: @Composable BoxScope.() -> Unit,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                val haze = rememberHazeState()
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalHazeState provides haze,
                ) {
                    GlassStage(
                        modifier = Modifier.fillMaxSize(),
                        height = 200.dp,
                        backdropModifier = Modifier.hazeSource(haze),
                        content = content,
                    )
                }
            }
        }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    /** The toolbar at rest, nothing wired (the `rest` / `rtl` content). */
    @Composable
    private fun BoxScope.restToolbar() {
        ToolbarSlot {
            SidebarToolbarContent(
                visibility = { 1f },
                onSettings = {},
                onOptions = {},
                onNewSession = {},
            )
        }
    }

    /** 105's slot geometry inside a [GlassStage]: bottom 24, height 44. */
    @Composable
    private fun BoxScope.ToolbarSlot(content: @Composable () -> Unit) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .height(SidebarToolbarDefaults.ItemSize),
        ) {
            content()
        }
    }

    /** Delivers pending writes and runs the frame that recomposes them. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** ------------------------------------------------------------- rest */

    @Test
    fun restLight() {
        shot(Appearance.Light, "shell/sidebar_toolbar_rest_light.png") { restToolbar() }
    }

    @Test
    fun restDark() {
        shot(Appearance.Dark, "shell/sidebar_toolbar_rest_dark.png") { restToolbar() }
    }

    /** ------------------------------------------------------------- half */

    @Test
    fun halfLight() {
        shot(Appearance.Light, "shell/sidebar_toolbar_half_light.png") {
            ToolbarSlot {
                SidebarToolbarContent(
                    visibility = { 0.5f },
                    onSettings = {},
                    onOptions = {},
                    onNewSession = {},
                )
            }
        }
    }

    @Test
    fun halfDark() {
        shot(Appearance.Dark, "shell/sidebar_toolbar_half_dark.png") {
            ToolbarSlot {
                SidebarToolbarContent(
                    visibility = { 0.5f },
                    onSettings = {},
                    onOptions = {},
                    onNewSession = {},
                )
            }
        }
    }

    /** ---------------------------------------------------- pressed ⋯ */

    private fun pressedOptionsShot(appearance: Appearance, file: String) {
        val source = MutableInteractionSource()
        shot(
            appearance,
            file,
            before = {
                source.tryEmit(PressInteraction.Press(Offset.Zero))
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(400) // the press-down spring settles
            },
        ) {
            ToolbarSlot {
                SidebarToolbarContent(
                    visibility = { 1f },
                    onSettings = {},
                    onOptions = {},
                    onNewSession = {},
                    optionsSource = source,
                )
            }
        }
    }

    @Test
    fun pressedOptionsLight() {
        pressedOptionsShot(Appearance.Light, "shell/sidebar_toolbar_pressed_options_light.png")
    }

    @Test
    fun pressedOptionsDark() {
        pressedOptionsShot(Appearance.Dark, "shell/sidebar_toolbar_pressed_options_dark.png")
    }

    /** ------------------------------------------------------ menu open */

    /**
     * The ⋯ menu opened from the ellipsis: the real flow (the slot's window
     * bounds anchor, 46's rows built at tap time) inside a [GlassMenuHost],
     * the frozen clock advanced past the open spring.
     */
    private fun menuOpenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    val menu = rememberGlassMenuState()
                    val haze = rememberHazeState()
                    CompositionLocalProvider(LocalHazeState provides haze) {
                        GlassStage(
                            modifier = Modifier.fillMaxSize(),
                            height = 200.dp,
                            backdropModifier = Modifier.hazeSource(haze),
                        ) {
                            ToolbarSlot {
                                SidebarToolbarContent(
                                    visibility = { 1f },
                                    onSettings = {},
                                    onOptions = { anchor ->
                                        menu.open(anchor, sessionsOptionsMenu(NoopActions))
                                    },
                                    onNewSession = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sidebar-options").performClick()
        frame() // the click's write is recomposed by the next frame
        compose.mainClock.advanceTimeBy(600) // past the 0.35 s open spring
        frame()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun menuOpenLight() {
        menuOpenShot(Appearance.Light, "shell/sidebar_toolbar_menu_open_light.png")
    }

    @Test
    fun menuOpenDark() {
        menuOpenShot(Appearance.Dark, "shell/sidebar_toolbar_menu_open_dark.png")
    }

    /** -------------------------------------------------------------- RTL */

    @Test
    fun rtlLight() {
        shot(Appearance.Light, "shell/sidebar_toolbar_rtl_light.png", LayoutDirection.Rtl) {
            restToolbar()
        }
    }

    @Test
    fun rtlDark() {
        shot(Appearance.Dark, "shell/sidebar_toolbar_rtl_dark.png", LayoutDirection.Rtl) {
            restToolbar()
        }
    }
}
