package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.glass.GlassMenuHost

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
                    // Ticket 46: the Sessions root is the real screen now —
                    // its ⋯ needs a GlassMenuHost (15 throws without one)
                    // and its loading state's spinner a frozen glyph clock.
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        GlassMenuHost { MainTabShell(state = state) }
                    }
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

    /** The large-title item is composed (the list is at — or near — its top). */
    private fun largeTitleOnScreen(): Boolean =
        compose.onAllNodesWithTag("nav-large-title").fetchSemanticsNodes().isNotEmpty()

    /**
     * A slow, zero-velocity swipe up over the real Settings list (ticket
     * 103 replaced the placeholder rows): the finger starts down the
     * middle of the screen — clear of the trailing switches — so the list
     * scrolls without toggling anything. The scrollable consumes the
     * touch slop before it scrolls, so the finger travels 500 dp + slop
     * for a 500 dp scroll.
     */
    private fun scrollSettingsDownList() {
        compose.onRoot().performTouchInput {
            val distance = 500.dp.toPx()
            swipeWithVelocity(
                start = Offset(width / 2f, height * 0.6f),
                end = Offset(width / 2f, height * 0.6f - distance - viewConfiguration.touchSlop),
                endVelocity = 0f,
            )
        }
    }

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
        // Ticket 103: the Settings root is the real screen (no placeholder
        // rows) — the pushed page goes on programmatically (the row tap
        // the placeholder used to stand in for), like 46 does for the
        // real Sessions root.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            state.stack(TabId.Settings).push(PlaceholderDetailRoute(TabId.Settings, 5), animated = false)
        }
        compose.waitUntil(5_000) { textExists("Settings row 5") }
        assertEquals(2, state.stack(TabId.Settings).entries.size)

        // Re-select with a pushed page: pop to root, no haptic.
        val before = feedback.calls.size
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitUntil(5_000) { state.stack(TabId.Settings).entries.size == 1 }
        assertEquals(before, feedback.calls.size)
        compose.onNodeWithText("Settings row 5").assertDoesNotExist()
        // The real root: the large title (the inline title is hidden at
        // rest — 25's one-visible-title rule).
        compose.onNodeWithTag("nav-large-title").assertExists()

        // Re-select at the root: the list scrolls back to the top (the
        // large-title item is composed away while scrolled down; the
        // inline title stays, so the tag is the top probe).
        scrollSettingsDownList()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-large-title").assertDoesNotExist()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitUntil(5_000) { largeTitleOnScreen() }

        // A second re-select at the top: nothing happens (no haptic, no
        // pop, no scroll).
        val after = feedback.calls.size
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        assertEquals(after, feedback.calls.size)
        assertEquals(1, state.stack(TabId.Settings).entries.size)
        compose.onNodeWithTag("nav-large-title").assertExists()
    }

    // ---------------------------------------------------------- retention

    @Test
    fun scrollAndStackAreKeptAcrossTabSwitches() {
        setContent()
        // Ticket 103: the scrollable root is the real Settings screen —
        // its bottom rows stand in for the placeholder's row 25, and the
        // pushed page goes on programmatically.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        scrollSettingsDownList()
        compose.waitForIdle()
        compose.runOnUiThread {
            state.stack(TabId.Settings).push(PlaceholderDetailRoute(TabId.Settings, 25), animated = false)
        }
        compose.waitUntil(5_000) { textExists("Settings row 25") }

        // Away and back: the pushed page is still the top of the stack.
        compose.onNodeWithTag("tab-sessions").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-sessions").assertExists()
        compose.onNodeWithText("Settings row 25").assertDoesNotExist()
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Settings row 25").assertExists()

        // System back pops the pushed page; the root list kept its scroll
        // position across the round trip (26's saveable state).
        back()
        compose.waitUntil(5_000) { state.stack(TabId.Settings).entries.size == 1 }
        compose.onNodeWithText("Settings row 25").assertDoesNotExist()
        compose.onNodeWithTag("tab-root-more").assertExists()
        // The list is still near its end: the last section's rows are on
        // screen, the large-title item is not (the inline title shows).
        compose.onNodeWithTag("nav-large-title").assertDoesNotExist()
        compose.onNodeWithTag("settings-archived").assertExists()
    }

    @Test
    fun eachTabsBackStackIsIndependent() {
        setContent()
        // Ticket 46: the Sessions root has no placeholder rows to tap —
        // push its detail page programmatically (the row tap it used to
        // stand in for). Ticket 103: the same for the real Settings root.
        compose.runOnUiThread {
            state.stack(TabId.Sessions).push(PlaceholderDetailRoute(TabId.Sessions, 3), animated = false)
        }
        compose.waitUntil(5_000) { textExists("Sessions row 3") }
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            state.stack(TabId.Settings).push(PlaceholderDetailRoute(TabId.Settings, 7), animated = false)
        }
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
        // The one Select is the user's tap on Settings (ticket 26: a user
        // tab change plays Haptic.Select); system back adds none.
        assertEquals(listOf(Haptic.Select), feedback.calls)
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
        // Ticket 46: the Sessions root is the real screen, with no
        // placeholder rows to tap (CI r33: "Placeholder row 4" not found) —
        // push the detail page programmatically, as
        // eachTabsBackStackIsIndependent does.
        compose.runOnUiThread {
            state.stack(TabId.Sessions).push(PlaceholderDetailRoute(TabId.Sessions, 4), animated = false)
        }
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
