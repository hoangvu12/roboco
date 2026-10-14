package sh.roboco.android.newsession

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
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
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * The Project / Run on menu goldens (android-ui-parity #113) — the five
 * static panel fixtures of the "New session project menus" gallery page
 * (P1, P2, R1, R2, R3, over [sh.roboco.android.design.GalleryBackdrop])
 * in light and dark, at the mdpi phone width where the ticket's dp
 * geometry reads 1:1.
 *
 * Recorded by `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.newsession.ProjectMenusScreenshotTest`, verified by
 * `:app:verifyRoborazziDebug --tests …`, then reviewed side by side with
 * the iOS Simulator's `-demo -route new` menus: P1's checked `roboco`
 * row shows the checkmark in the leading slot while the other project
 * rows show their 18 dp tiles centred 36 dp from the panel edge with
 * titles at 61 dp, the separator sits between `Roboco Mobile` and
 * `No Project…`, and R2's two MacBook Pro rows show their paths.
 *
 * `Glass.forceFallback` and the frozen main clock follow the gallery
 * golden convention (tickets 07/22): the fallback fill keeps the bytes
 * stable, nothing here animates. The row counts double as a self-check
 * on the flattened content (4 group rows and 2 fixed rows per Project
 * panel; 2 + 3 + 3 Run on rows).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w420dp-h2100dp-mdpi")
class ProjectMenusScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun stableGoldens() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTiers() {
        Glass.forceFallback = false
    }

    // ── goldens ─────────────────────────────────────────────────────────

    @Test
    fun goldensLight() {
        goldenShot(Appearance.Light, "newsession/project_menus_light.png")
    }

    @Test
    fun goldensDark() {
        goldenShot(Appearance.Dark, "newsession/project_menus_dark.png")
    }

    /**
     * The font-scale AC: at 1.3 the titles and subtitles grow (ticket 15's
     * `scaled` text) while the row geometry — the 36 dp slot centre, the
     * 61 dp text start — stays put (15's own test pins the mechanics; this
     * pins it over this ticket's real menus).
     */
    @Test
    fun goldenAtFontScale13() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.3f)) {
                RobocoTheme(Appearance.Light) {
                    GlassMenuHost(hazeState = rememberHazeState()) {
                        ProjectMenusStaticPanels()
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("newsession/project_menus_fontscale13.png")
    }

    private fun goldenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    ProjectMenusStaticPanels()
                }
            }
        }
        compose.waitForIdle()
        // One settle frame before the capture (the frozen-clock recipe).
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // The flattened content, as a self-check on what the golden holds:
        // both Project panels carry the four groups and the two fixed rows;
        // the Run on panels carry 2 + 3 + 3 rows.
        assertEquals(8, count("project-menu-group"))
        assertEquals(2, count("project-menu-none"))
        assertEquals(2, count("project-menu-new"))
        assertEquals(8, count("run-on-row"))

        compose.onRoot().captureRoboImage(file)
    }

    private fun count(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
