package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * Round 44 (SessionFlowTest risk reduction): the live counts reach the
 * accessory through `MainTabShell(liveCounts = provider)`, read inside the
 * accessory slot only. A counts change updates the summary, and the caller
 * holding the provider is not recomposed by it: the state is never read
 * outside the accessory, so the shell's measure-time `BoxWithConstraints`
 * subcomposition is not re-run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class MainTabShellLiveCountsTest {

    @get:Rule
    val compose = createComposeRule()

    /** The accessory's merged label (`clearAndSetSemantics`: the summary text is not its own node). */
    private fun hasLabel(label: String): Boolean =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theProviderDrivesTheAccessoryWithoutRecomposingTheCaller() {
        val live = mutableStateOf(LiveCounts())
        val provider: () -> LiveCounts = { live.value }
        var callerRuns = 0
        compose.setContent {
            SideEffect { callerRuns++ }
            RobocoTheme {
                GlassMenuHost {
                    MainTabShell(
                        state = MainTabState(TabId.Sessions),
                        liveCounts = provider,
                        routeContent = { _ -> Box {} },
                    )
                }
            }
        }
        compose.waitForIdle()
        val runsAtRest = callerRuns

        live.value = LiveCounts(working = 2, awaiting = 1)
        compose.waitUntil(5_000) { hasLabel("New session, 2 working, 1 needs you") }

        live.value = LiveCounts()
        compose.waitUntil(5_000) { hasLabel("New session") }
        assertEquals("a counts change never recomposes the provider's holder", runsAtRest, callerRuns)
    }
}
