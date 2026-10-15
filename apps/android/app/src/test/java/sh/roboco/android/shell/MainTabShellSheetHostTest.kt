package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * /code-review F5 (ticket 35 AC: "a presented sheet stays presented across
 * the switch"; forgetting the last engine removes sheets instantly): the
 * phone shell presents on the app root's sheet host (RootHosts', above the
 * Phone ↔ Split switch and registered with RootPresentations) instead of a
 * host of its own. Standalone (galleries, previews, tests) it still mounts
 * its own.
 *
 * The tab content (`routeContent`) composes inside the shell's providers,
 * so the [LocalSheetHost] it reads is the host the shell's router presents
 * on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class MainTabShellSheetHostTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun underTheRootHostTheShellPresentsOnTheRootHost() {
        val root = SheetHostState()
        var seen: SheetHostState? = null
        compose.setContent {
            RobocoTheme {
                SheetHost(root) {
                    GlassMenuHost {
                        MainTabShell(
                            state = MainTabState(TabId.Sessions),
                            routeContent = { _ ->
                                seen = LocalSheetHost.current
                                Box {}
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertSame("the shell must use the root's host, not mount its own", root, seen)
    }

    @Test
    fun aStandaloneShellStillHostsItsOwnSheets() {
        val unrelated = SheetHostState()
        var seen: SheetHostState? = null
        compose.setContent {
            RobocoTheme {
                GlassMenuHost {
                    MainTabShell(
                        state = MainTabState(TabId.Sessions),
                        routeContent = { _ ->
                            seen = LocalSheetHost.current
                            Box {}
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertNotNull("a shell without a root host mounts its own", seen)
        assertNotSame(unrelated, seen)
    }
}
