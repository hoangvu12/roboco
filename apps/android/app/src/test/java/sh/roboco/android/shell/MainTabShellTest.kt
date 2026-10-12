package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ReduceMotion

/**
 * Ticket 26's shell behaviour (Robolectric, 402 × 874 dp at xxhdpi): the
 * haptic count on a user change vs re-select vs programmatic select, the
 * re-select pop-then-scroll chain, scroll + stack retention across tab
 * switches, lazy composition of never-selected tabs, system-back routing
 * through [androidx.activity.OnBackPressedDispatcher], and `previousTab`
 * after Search. A fake [Feedback] records every call; Reduce Motion is
 * forced for determinism (NavStack's reduced cross-fade, the platter snap).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class MainTabShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val feedback = RecordingFeedback()
    private lateinit var state: MainTabState

    @Before
    fun setUp() {
        ReduceMotion.resetForTest()
    }

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    private fun setContent() {
        state = MainTabState(TabId.Sessions)
        compose.setContent {
            RobocoTheme {
                CompositionLocalProvider(
                    LocalFeedback provides feedback,
                    LocalReduceMotion provides true,
                ) {
                    MainTabShell(state = state)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // ------------------------------------------------------------- haptics

    @Test
    fun tappingAnUnselectedTabPlaysExactlyOneSelectHapticAndSwapsContent() {
        setContent()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Haptic.Select), feedback.calls)
        assertEquals(TabId.Settings, state.selected)
        // The content swapped: the Settings root is composed, the Sessions
        // root is not.
        compose.onNodeWithTag("tab-root-more").assertExists()
        compose.onNodeWithTag("tab-root-sessions").assertDoesNotExist()
    }

    @Test
    fun programmaticSelectAndReselectPlayNoHaptic() {
        setContent()
        state.select(TabId.Settings)
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").assertExists()
        // A re-select at the root: the scroll-to-top handler runs (a no-op
        // at the top) — still no haptic.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun reselectPopsToRootThenScrollsToTopAndASecondReselectDoesNothing() {
        setContent()
        compose.onNodeWithText("Placeholder row 5").performClick()
        compose.waitUntil(5_000) { textExists("Sessions row 5") }
        assertEquals(2, state.stack(TabId.Sessions).entries.size)

        // Re-select with a pushed page: pop to root, no haptic.
        val before = feedback.calls.size
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitUntil(5_000) { state.stack(TabId.Sessions).entries.size == 1 }
        assertEquals(before, feedback.calls.size)
        compose.onNodeWithText("Sessions row 5").assertDoesNotExist()
        compose.onNodeWithText("Placeholder row 1").assertExists()

        // Re-select at the root: the list scrolls back to the top.
        compose.onNodeWithTag("tab-root-sessions").performScrollToIndex(20)
        compose.waitForIdle()
        compose.onNodeWithText("Placeholder row 1").assertDoesNotExist()
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitUntil(5_000) { textExists("Placeholder row 1") }

        // A second re-select at the top: nothing happens (no haptic, no
        // pop, no scroll).
        val after = feedback.calls.size
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitForIdle()
        assertEquals(after, feedback.calls.size)
        assertEquals(1, state.stack(TabId.Sessions).entries.size)
        compose.onNodeWithText("Placeholder row 1").assertExists()
    }

    // ---------------------------------------------------------- retention

    @Test
    fun scrollAndStackAreKeptAcrossTabSwitches() {
        setContent()
        compose.onNodeWithTag("tab-root-sessions").performScrollToIndex(20)
        compose.waitForIdle()
        compose.onNodeWithText("Placeholder row 25").performClick()
        compose.waitUntil(5_000) { textExists("Sessions row 25") }

        // Away and back: the pushed page is still the top of the stack.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").assertExists()
        compose.onNodeWithText("Sessions row 25").assertDoesNotExist()
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sessions row 25").assertExists()

        // Pop to root (re-select): the list kept its scroll position.
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitUntil(5_000) { state.stack(TabId.Sessions).entries.size == 1 }
        compose.onNodeWithText("Placeholder row 21").assertExists()
    }

    @Test
    fun eachTabsBackStackIsIndependent() {
        setContent()
        compose.onNodeWithText("Placeholder row 3").performClick()
        compose.waitUntil(5_000) { textExists("Sessions row 3") }
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Placeholder row 7").performClick()
        compose.waitUntil(5_000) { textExists("Settings row 7") }
        assertEquals(2, state.stack(TabId.Settings).entries.size)
        assertEquals(2, state.stack(TabId.Sessions).entries.size)

        // Popping Settings to root does not touch Sessions.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitUntil(5_000) { state.stack(TabId.Settings).entries.size == 1 }
        assertEquals(2, state.stack(TabId.Sessions).entries.size)
    }

    // ---------------------------------------------------- lazy composition

    @Test
    fun aTabNeverSelectedIsNeverComposed() {
        setContent()
        compose.onNodeWithTag("tab-root-sessions").assertExists()
        compose.onNodeWithTag("tab-root-more").assertDoesNotExist()
        compose.onNodeWithTag("tab-root-search").assertDoesNotExist()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").assertExists()
    }

    // ------------------------------------------------------ system back

    @Test
    fun backAtSettingsRootReturnsToSessions() {
        setContent()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        back()
        assertEquals(TabId.Sessions, state.selected)
        compose.onNodeWithTag("tab-root-sessions").assertExists()
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun backAtSearchRootReturnsToThePreviousTab() {
        setContent()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-search").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Search, state.selected)
        assertEquals(TabId.Settings, state.previousTab)
        compose.onNodeWithTag("tab-root-search").assertExists()
        back()
        assertEquals(TabId.Settings, state.selected)
        compose.onNodeWithTag("tab-root-more").assertExists()
        compose.onNodeWithTag("tab-root-sessions").assertDoesNotExist()
    }

    @Test
    fun backAtSessionsRootIsNotConsumed() {
        setContent()
        // Neither the shell (Sessions) nor the host (root) registers an
        // enabled callback: the activity's default back runs instead.
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        back()
        assertEquals(TabId.Sessions, state.selected)
    }

    @Test
    fun backWithAPushedPagePopsItAndDoesNotSwitchTabs() {
        setContent()
        compose.onNodeWithText("Placeholder row 4").performClick()
        compose.waitUntil(5_000) { textExists("Sessions row 4") }
        back()
        compose.waitUntil(5_000) { state.stack(TabId.Sessions).entries.size == 1 }
        assertEquals(TabId.Sessions, state.selected)
        compose.onNodeWithText("Sessions row 4").assertDoesNotExist()
    }

    @Test
    fun previousTabIsTheTabYouCameFromWhenEnteringSearch() {
        setContent()
        compose.onNodeWithTag("tab-search").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Search, state.selected)
        assertEquals(TabId.Sessions, state.previousTab)
    }

    // ------------------------------------------------------------- the fake

    /** Records every [Haptic] call; `cue` is a no-op. */
    private class RecordingFeedback : Feedback {
        val calls = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            calls += haptic
        }

        override fun cue(cue: Cue) {}
    }
}
