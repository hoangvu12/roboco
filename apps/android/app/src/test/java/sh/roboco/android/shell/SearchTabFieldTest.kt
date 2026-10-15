package sh.roboco.android.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Ticket 29's field and return circle (Robolectric, 402 × 220 dp at xxhdpi
 * with the zero insets Robolectric reports): the search chrome fixture at
 * fixed morph positions over the striped backdrop, plus the real
 * [MainTabShell] for the round trip.
 *
 *  - the p = 1 geometry: the return circle 48 × 48 at window x 21, the text
 *    box 40 dp inside the field (250 dp wide, both tops at y 151 = the
 *    bottoms 21 dp above the window bottom), and at p = 0 exactly ticket
 *    26's circle (62 × 62 at x 319, `tab-search`, "Search", no text field);
 *  - `performTextInput` updates `query` after each keystroke (raw);
 *  - the clear button's visibility, its click emptying the query and its
 *    focus-when-unfocused;
 *  - `performImeAction()` clears the focus and keeps the text;
 *  - `search-close` ("Close") returns to the tab you came from through the
 *    real shell, and the `new-session` accessory exists and is clickable in
 *    every state (never removed from composition, upstream #577);
 *  - the field node carries the "Search" content description;
 *  - the morph follows [SearchMorph.Spring] (0.663 at 100 ms, settled by
 *    900 ms) and snaps under Reduce Motion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h220dp-xxhdpi")
class SearchTabFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val searchState = SearchTabState()
    private var returns = 0

    @Before
    fun setUp() {
        ReduceMotion.resetForTest()
        returns = 0
    }

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The search chrome fixture at a fixed morph position [p] over the
     * striped backdrop: 402 dp wide (R = 360), 220 dp tall, B = 21
     * (gesture nav with Robolectric's zero insets), Reduce Motion forced
     * so nothing animates while the assertions run.
     */
    private fun place(p: Float = 1f, active: Boolean = true) {
        compose.setContent {
            RobocoTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    SearchMorphFixture(
                        state = searchState,
                        progress = { p },
                        active = active,
                        minimizeProgress = { 0f },
                        height = 220.dp,
                        onReturn = { returns++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The real shell, for the round trip. */
    private fun placeShell(shell: MainTabState) {
        compose.setContent {
            RobocoTheme {
                // Ticket 46: the real Sessions root needs the menu host and
                // the frozen glyph clock (the loading spinner).
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    GlassMenuHost { MainTabShell(state = shell, searchState = searchState) }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun theSearchRowSettlesAtTheTicketGeometry() {
        place(p = 1f, active = true)
        // The return circle: 48 × 48 at window x 21, top y 151, bottom
        // 199 = 220 − 21 (21 dp above the window bottom).
        compose.onNodeWithTag("search-close")
            .assertPositionInRootIsEqualTo(21.dp, 151.dp)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
        // The text box: 40 dp inside the field capsule's leading edge
        // (window x 77 + 40 = 117), 250 wide (304 − 40 − 14, no clear
        // button with empty text), the field's own top and height.
        compose.onNodeWithTag("search-field")
            .assertPositionInRootIsEqualTo(117.dp, 151.dp)
            .assertWidthIsEqualTo(250.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun atRestTheSlotIsExactlyTicket26sSearchCircle() {
        place(p = 0f, active = false)
        compose.onNodeWithTag("tab-search")
            .assertPositionInRootIsEqualTo(319.dp, 137.dp)
            .assertWidthIsEqualTo(62.dp)
            .assertHeightIsEqualTo(62.dp)
        // The text field subtree is not composed once p has settled.
        compose.onNodeWithTag("search-field").assertDoesNotExist()
        // 26's semantics: the label, not a field.
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithTag("tab-search").assertHasClickAction()
    }

    // ---------------------------------------------------------------- typing

    @Test
    fun typingUpdatesTheQueryAfterEachKeystroke() {
        place(p = 1f, active = true)
        compose.onNodeWithTag("search-field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-field").assertIsFocused()
        compose.onNodeWithTag("search-field").performTextInput("z")
        compose.waitForIdle()
        assertEquals("z", searchState.query)
        compose.onNodeWithTag("search-field").performTextInput("e")
        compose.waitForIdle()
        assertEquals("ze", searchState.query)
        // Raw, untrimmed — ticket 49 trims.
        compose.onNodeWithTag("search-field").performTextInput(" ")
        compose.waitForIdle()
        assertEquals("ze ", searchState.query)
    }

    @Test
    fun theFieldNodeCarriesTheSearchDescription() {
        place(p = 1f, active = true)
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithTag("search-field").assertExists()
    }

    // ------------------------------------------------------------ the clear

    @Test
    fun theClearButtonTracksEmptinessAndEmptiesTheQuery() {
        place(p = 1f, active = true)
        compose.onNodeWithTag("search-field").performClick()
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
        compose.onNodeWithTag("search-field").performTextInput("zeron")
        compose.waitForIdle()
        compose.onNodeWithTag("search-clear").assertExists()
        compose.onNodeWithTag("search-clear").performClick()
        compose.waitForIdle()
        assertEquals("", searchState.query)
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
    }

    @Test
    fun theClearButtonFocusesTheFieldWhenItWasNotFocused() {
        place(p = 1f, active = true)
        compose.onNodeWithTag("search-field").performClick()
        compose.onNodeWithTag("search-field").assertIsFocused()
        compose.onNodeWithTag("search-field").performTextInput("ze")
        // The IME "Search" key: hide + clear the focus, keep the text.
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("search-field").assertIsNotFocused()
        assertEquals("ze", searchState.query)
        // UIKit's UITextField begins editing when its clear button is
        // tapped while not editing.
        compose.onNodeWithTag("search-clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-field").assertIsFocused()
        assertEquals("", searchState.query)
    }

    // ------------------------------------------------------ the return path

    @Test
    fun theCloseButtonCallsOnReturn() {
        place(p = 1f, active = true)
        compose.onNodeWithTag("search-close").assertExists()
        compose.onNodeWithTag("search-close").performClick()
        compose.waitForIdle()
        assertEquals(1, returns)
    }

    @Test
    fun closeReturnsToTheTabYouCameFromInTheRealShell() {
        val shell = MainTabState(TabId.Sessions)
        // Snaps the morph in one frame per selection, for determinism.
        ReduceMotion.debugOverride = true
        placeShell(shell)
        compose.onNodeWithTag("tab-search").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Search, shell.selected)
        compose.onNodeWithTag("search-close").assertExists()
        // Selecting the tab activates the search: the field composes and
        // focuses (the caret at the end; the IME shows on a device).
        compose.onNodeWithTag("search-field").assertIsFocused()
        // The accessory is never removed from composition (upstream #577).
        compose.onNodeWithTag("new-session").assertExists()
        compose.onNodeWithTag("new-session").assertHasClickAction()

        compose.onNodeWithTag("search-close").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Sessions, shell.selected)
        compose.onNodeWithTag("new-session").assertExists().assertHasClickAction()

        // Settings → Search → Close lands on Settings, not Sessions.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Settings, shell.selected)
        compose.onNodeWithTag("tab-search").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Search, shell.selected)
        assertEquals(TabId.Settings, shell.previousTab)
        compose.onNodeWithTag("search-close").performClick()
        compose.waitForIdle()
        assertEquals(TabId.Settings, shell.selected)
        // After it, the accessory is displayed and usable (#577's test).
        compose.onNodeWithTag("new-session").assertExists().assertHasClickAction()
    }

    // ------------------------------------------------ the live morph spring

    @Test
    fun theMorphFollowsTheSearchMorphSpring() {
        var selected by mutableStateOf(false)
        var morphState: State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                val morph = rememberSearchMorphProgress(selected)
                morphState = morph
                SearchMorphFixture(
                    state = searchState,
                    progress = { morph.value },
                    active = selected,
                    minimizeProgress = { 0f },
                    height = 220.dp,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { selected = true }
        // 100 ms in: the spring sits at 0.663 exactly (the ticket's
        // checkpoint); one frame of launch alignment brings it to ~0.53,
        // so the window is [0.50, 0.80] — mid-flight, not settled.
        compose.mainClock.advanceTimeBy(100)
        val p = morphState!!.value
        assertTrue("progress at 100 ms: $p", p in 0.50f..0.80f)
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(1f, morphState!!.value, 1e-4f)
    }

    @Test
    fun reduceMotionSnapsTheMorph() {
        var selected by mutableStateOf(false)
        var morphState: State<Float>? = null
        ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                val morph = rememberSearchMorphProgress(selected)
                morphState = morph
                SearchMorphFixture(
                    state = searchState,
                    progress = { morph.value },
                    active = selected,
                    minimizeProgress = { 0f },
                    height = 220.dp,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { selected = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, morphState!!.value, 1e-4f)
    }
}
