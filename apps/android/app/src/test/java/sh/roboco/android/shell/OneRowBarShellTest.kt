package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * Wave-10 37 (upstream #967 + #971): the phone shell's one-row bottom bar.
 * The bar is one row — Settings, the search field, New session — with no
 * tab cells at all (#971: no tab bar can come back); Settings pushes over
 * the list and the bar steps aside, back brings both back; the Sessions
 * stack shows bottom chrome on its root only. Robolectric, 402 × 874 dp,
 * Reduce Motion forced (the reduced cross-fade) as in [MainTabShellTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class OneRowBarShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        GlassMenuHost { MainTabShell(state = state, oneRowBar = true) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * The bar is never removed from composition (#30): hidden, it stays
     * composed but unplaced, and an unplaced node can keep its semantics —
     * so "shown" is a node that is displayed.
     */
    private fun shown(tag: String): Boolean {
        val nodes = compose.onAllNodesWithTag(tag)
        return (0 until nodes.fetchSemanticsNodes().size).any { nodes.get(it).isDisplayed() }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun theBarIsOneRowWithNoTabCells() {
        setContent()
        assertTrue(exists("bottom-settings"))
        assertTrue(exists("new-session"))
        assertTrue(compose.onAllNodesWithTag("search-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        for (tab in listOf("tab-sessions", "tab-more", "tab-search")) {
            assertFalse("no tab cell $tab", exists(tab))
        }
        assertTrue(state.stack(TabId.Sessions).chromeOnRootOnly)
    }

    @Test
    fun settingsPushesOverTheListAndTheBarStepsAside() {
        setContent()
        compose.onNodeWithTag("bottom-settings").performClick()
        compose.waitForIdle()
        assertEquals("still the one stack", TabId.Sessions, state.selected)
        assertEquals(SettingsRoute, state.stack(TabId.Sessions).top.route)
        assertTrue(exists("tab-root-more"))
        assertFalse("the bar belongs to the front page", shown("new-session"))
        assertFalse(shown("bottom-settings"))

        back()
        assertFalse(exists("tab-root-more"))
        assertTrue("back on the list, the bar is back", shown("new-session"))
        assertTrue(shown("bottom-settings"))
        assertFalse("and still no tab cells", exists("tab-more"))
    }

    @Test
    fun aPushLetsGoOfTheSearchFieldsFocus() {
        setContent()
        compose.onNodeWithTag("search-field", useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertTrue("the field took the focus", fieldFocused())
        compose.onNodeWithTag("bottom-settings").performClick()
        compose.waitForIdle()
        assertFalse("the push let go of it (and the IME)", fieldFocused())
    }

    private fun fieldFocused(): Boolean =
        compose.onAllNodesWithTag("search-field", useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    @Test
    fun theSessionsStackShowsChromeOnItsRootOnly() {
        val root = object : NavRoute {}
        val page = object : NavRoute {}
        val stack = NavStack(root)
        val first = stack.top
        val pushed = stack.push(page, animated = false)
        assertFalse("off: a plain page keeps the chrome", stack.hidesChrome(pushed))
        stack.chromeOnRootOnly = true
        assertTrue("on: everything above the root hides it", stack.hidesChrome(pushed))
        assertFalse("the root always shows it", stack.hidesChrome(first))
        val session = object : NavRoute {
            override val hidesBottomChrome: Boolean get() = true
        }
        stack.chromeOnRootOnly = false
        assertTrue("a route that hides keeps hiding", stack.hidesChrome(stack.push(session, animated = false)))
    }
}
