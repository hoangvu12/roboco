package sh.roboco.android.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.rememberGlassMenuState

/**
 * The ⋯ menu's goldens (android-ui-parity #80), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`: the real
 * [SessionHeader] (52) — "Fix the deploy script" / "roboco @ studio",
 * a fixed 24 dp status bar — over ticket 22's striped [GalleryBackdrop],
 * inside a real [GlassMenuHost] (15) and [ToastHost] (14), on the
 * `w412dp-h900dp-xxhdpi` device with no system insets. Tapping
 * `session-more` opens the real placement (cover: the panel's trailing
 * edge = the ⋯ circle's, opening below the circle's top), then the clock
 * advances 1000 ms so the open spring settles before the capture.
 *
 * In Robolectric the glass draws ticket 07's fallback fill
 * (`Glass.forceFallback = true`, the golden convention).
 *
 * The three fixtures:
 * - `session_menu_light_unpinned` — "Pin", all rows enabled (the fifth row
 *   set is android-next #05's "Account & Usage" and agent-previews' 05's
 *   "Previews", never disabled);
 * - `session_menu_dark_pinned` — "Unpin" with the slash icon, enabled;
 * - `session_menu_light_readonly` — Pin and Archive disabled (grey
 *   titles, full-ink icons; Copy Transcript enabled).
 *
 * The panel's bounds are pinned here too, on the worked example: a 412 dp
 * window with the ⋯ at (352, 24)–(396, 68) puts the 250 × 230 dp panel at
 * x 146 → 396 and y 24 → 254 (±1 px) — five rows (Pin, Copy Transcript,
 * Previews, Account & Usage, Archive) at 42 dp + the 10 dp panel paddings
 * (agent-previews ticket 05 added the Previews row; the goldens need a
 * record_goldens dispatch to match).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var shotDensity: Density = Density(1f)

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    /** Delivers pending writes and runs the frame that recomposes them. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun dpPx(dp: Int): Float = with(shotDensity) { dp.dp.toPx() }

    /**
     * [headed]: upstream #967's menu — the title over "project @ device"
     * heads it and Delete trails Archive. The panel grows by those two
     * lines, so the worked-example geometry below is checked only for the
     * plain menus.
     */
    private fun shot(appearance: Appearance, pinned: Boolean, canWrite: Boolean, file: String, headed: Boolean = false) {
        compose.setContent {
            RobocoTheme(appearance) {
                ToastHost {
                    GlassMenuHost {
                        shotDensity = LocalDensity.current
                        Box(Modifier.fillMaxSize()) {
                            GalleryBackdrop(Modifier.matchParentSize())
                            val menuState = rememberGlassMenuState()
                            SessionHeader(
                                title = "Fix the deploy script",
                                subtitle = "roboco @ studio",
                                edgeEngaged = true,
                                onMore = { anchor ->
                                    menuState.open(
                                        anchor,
                                        sessionMenu(
                                            pinned = pinned,
                                            canWrite = canWrite,
                                            onSetPinned = {},
                                            onCopyTranscript = {},
                                            onShowPreviews = {},
                                            onShowAccounts = {},
                                            onArchive = {},
                                            onDelete = if (headed) ({}) else null,
                                            title = if (headed) "Fix the deploy script" else "",
                                            subtitle = if (headed) "roboco @ studio" else "",
                                        ),
                                    )
                                },
                                showBack = true,
                                onBack = {},
                                statusBarTop = 24.dp,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-more").performClick()
        frame()
        compose.mainClock.advanceTimeBy(1000)
        frame()

        // The worked example: the panel sits at x 146 → 396, y 24 → 254,
        // its trailing edge shared with the ⋯ circle's (396).
        val panel = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        if (headed) {
            compose.onNodeWithTag("session-menu-delete").assertExists()
            val more = compose.onNodeWithTag("session-more").fetchSemanticsNode()
            assertEquals(
                "the headed panel still shares the ⋯ circle's trailing edge",
                more.positionInRoot.x + more.size.width,
                panel.positionInRoot.x + panel.size.width,
                1f,
            )
            compose.onRoot().captureRoboImage("session/$file")
            return
        }
        assertEquals(dpPx(146), panel.positionInRoot.x, 1f)
        assertEquals(dpPx(396), panel.positionInRoot.x + panel.size.width, 1f)
        assertEquals(dpPx(24), panel.positionInRoot.y, 1f)
        assertEquals(dpPx(254), panel.positionInRoot.y + panel.size.height, 1f)
        val more = compose.onNodeWithTag("session-more").fetchSemanticsNode()
        assertEquals(
            "the panel's trailing edge matches the ⋯ circle's",
            more.positionInRoot.x + more.size.width,
            panel.positionInRoot.x + panel.size.width,
            1f,
        )

        compose.onRoot().captureRoboImage("session/$file")
    }

    @Test
    fun lightUnpinned() {
        shot(Appearance.Light, pinned = false, canWrite = true, "session_menu_light_unpinned.png")
    }

    @Test
    fun darkPinned() {
        shot(Appearance.Dark, pinned = true, canWrite = true, "session_menu_dark_pinned.png")
    }

    @Test
    fun lightReadOnly() {
        shot(Appearance.Light, pinned = false, canWrite = false, "session_menu_light_readonly.png")
    }

    @Test
    fun lightHeadedWithDelete() {
        shot(Appearance.Light, pinned = false, canWrite = true, "session_menu_light_headed_delete.png", headed = true)
    }

    @Test
    fun darkHeadedWithDelete() {
        shot(Appearance.Dark, pinned = true, canWrite = true, "session_menu_dark_headed_delete.png", headed = true)
    }
}
