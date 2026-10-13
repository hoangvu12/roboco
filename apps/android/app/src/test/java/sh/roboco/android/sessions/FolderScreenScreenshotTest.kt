package sh.roboco.android.sessions

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
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The folder screens' goldens (android-ui-parity #45): the ticket's
 * fixture in light and dark — the "P0" section with the ⋯ circle and three
 * rows, Pinned (2 rows), Archived (2 rows) and the empty archive's "No
 * archived sessions" — as pushed screens inside a [NavStackHost] (the back
 * circle shows, 25) under the [GlassMenuHost] the ⋯ menu needs.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp) on ticket 25's
 * 412 × 700 dp phone with a **fixed 24 dp status bar** (Robolectric
 * reports no insets); the glyph clock is frozen at 0 ns so nothing
 * animates while capturing. Roborazzi paths are relative to
 * `roborazzi { outputDir }` (`src/test/screenshots`), so the goldens land
 * at `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.FolderScreenScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`, then reviewed side by
 * side with `pr-assets/group-by-project/1-off.png` (the large title at 16,
 * the 44 × 44 ⋯ circle 16 dp from the end edge, the 62 dp rows).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class FolderScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Hosts [route] as a pushed folder over the placeholder root and
     * captures the whole window. The stack is pushed hostless before the
     * content is set, so the transition is already committed and no frame
     * of the root alone is ever captured.
     */
    private fun shot(appearance: Appearance, file: String, route: FolderRoute, workspace: WorkspaceSnapshot?) {
        val stack = NavStack(TabRootRoute(TabId.Sessions)).apply { push(route, animated = false) }
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    GlassMenuHost {
                        NavStackHost(stack = stack, active = true) { entry ->
                            val pushed = entry.route
                            if (pushed is FolderRoute) {
                                FolderScreen(
                                    route = pushed,
                                    workspace = workspace,
                                    loading = false,
                                    topInset = 24.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/$file")
    }

    // ------------------------------------------------------------ section

    @Test
    fun sectionLight() {
        shot(Appearance.Light, "FolderScreen_section_light.png", FolderFixtures.sectionRoute, FolderFixtures.sectionWorkspace())
    }

    @Test
    fun sectionDark() {
        shot(Appearance.Dark, "FolderScreen_section_dark.png", FolderFixtures.sectionRoute, FolderFixtures.sectionWorkspace())
    }

    // ------------------------------------------------------------ pinned

    @Test
    fun pinnedLight() {
        shot(Appearance.Light, "FolderScreen_pinned_light.png", FolderFixtures.pinnedRoute, FolderFixtures.pinnedWorkspace())
    }

    @Test
    fun pinnedDark() {
        shot(Appearance.Dark, "FolderScreen_pinned_dark.png", FolderFixtures.pinnedRoute, FolderFixtures.pinnedWorkspace())
    }

    // ----------------------------------------------------------- archived

    @Test
    fun archivedLight() {
        shot(Appearance.Light, "FolderScreen_archived_light.png", FolderFixtures.archivedRoute, FolderFixtures.archivedWorkspace())
    }

    @Test
    fun archivedDark() {
        shot(Appearance.Dark, "FolderScreen_archived_dark.png", FolderFixtures.archivedRoute, FolderFixtures.archivedWorkspace())
    }

    @Test
    fun archivedEmptyLight() {
        shot(
            Appearance.Light,
            "FolderScreen_archived_empty_light.png",
            FolderFixtures.archivedRoute,
            FolderFixtures.archivedWorkspace(emptyList()),
        )
    }
}
