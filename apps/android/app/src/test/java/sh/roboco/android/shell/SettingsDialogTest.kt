package sh.roboco.android.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.math.exp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * The tablet Settings form-sheet dialog's behaviour (android-ui-parity
 * #104) — Robolectric + compose-ui-test at the ticket's tablet window
 * (1280 × 800 dp, xhdpi, all insets 0, so `avail` = the window). The
 * fixture is the ticket's: `RobocoTheme { SheetHost(state) {
 * FakeSplit() } }` with the gallery's fake root
 * ([SettingsDialogFakeSplit]) and a fake [AppRouter] that records calls,
 * provided **around** the `SheetHost` exactly as 106 will provide the real
 * one at the split root (the presenter captures those locals).
 *
 * Covers the ticket's acceptance list: the settled geometry (540 × 620 at
 * (370, 90); 512 × 552 at (24, 24) in a 560 × 600 window), the title and
 * Done circle's placement inside the card, the present spring tracking
 * the analytic critically damped curve (`1 − (1 + ωt)·e^(−ωt)`, ω =
 * 23.0835) and settling by 450 ms, the scrim's consumed outside taps,
 * Done's animated teardown (root disposed exactly once), pushes inside the
 * card without Done, back popping vs dismissing, predictive back commit /
 * cancel, a stacked page sheet dismissing alone, the "already presenting"
 * no-op, session taps routed to the app router, the same-frame instant
 * dismissal, reduced motion's 150 ms fades, the pane title / Dismiss
 * action / cleared split semantics, and the sidebar keyboard being put
 * away on present.
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class SettingsDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = SheetHostState()
    private val router = RecordingRouter()
    private var sidebarTaps = 0
    private var rootDisposals = 0
    private var presenter: SettingsDialogPresenter? = null

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Provided around the SheetHost: the presenter captures
                // this scope's locals, so the card sees the recording
                // router (106 provides the real one at the split root).
                CompositionLocalProvider(LocalAppRouter provides router) {
                    SheetHost(state) {
                        SettingsDialogFakeSplit(
                            state = state,
                            onSidebarRowTap = { sidebarTaps++ },
                            onPresenterReady = { presenter = it },
                            onRootDispose = { rootDisposals++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ fixtures

    /** The ticket's fake `AppRouter`: records every openSession call. */
    private class RecordingRouter : AppRouter {
        val sessions = mutableListOf<String>()

        override fun openSession(chatId: String) {
            sessions += chatId
        }

        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            openSession(chatId)
        }

        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() {}
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
    }

    /** The card's entry (the only one unless a page sheet is stacked). */
    private fun entry(): SheetEntry = state.entries.first { it.key == SettingsDialogDefaults.Key }

    private fun present() {
        compose.runOnUiThread { assertTrue(presenter!!.present()) }
        compose.waitForIdle()
    }

    /** One frame on the frozen clock (the SheetHostTest convention). */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Runs the clock past every spring (0.5 s) and the reduced fade. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    /** The tick of one `advanceTimeByFrame` — 16 ms. */
    private val frameMs = 16L

    /** The settle-matched ω of the 0.4 s critically damped spring. */
    private val omega = 23.0835

    /** `p(t) = 1 − (1 + ωt)·e^(−ωt)` — the spring's analytic progress. */
    private fun springProgress(tMs: Long): Float {
        val wT = omega * tMs / 1000.0
        return (1.0 - (1.0 + wT) * exp(-wT)).toFloat()
    }

    /**
     * The card's list scrollable (the LazyColumn is 25's, untagged — the
     * only node with a `ScrollBy` action before any push).
     */
    private val scrollableMatcher = SemanticsMatcher("has a scroll action") { node ->
        node.config.contains(SemanticsActions.ScrollBy)
    }

    /**
     * Scrolls the card's list until [text]'s row is in view. The semantics
     * scroll runs coroutines the frozen clock never pumps, so the clock is
     * thawed for the scroll (the card sits at rest — nothing animates)
     * and frozen again after.
     */
    private fun scrollToRow(text: String) {
        compose.mainClock.autoAdvance = true
        compose.onNode(scrollableMatcher).performScrollToNode(hasText(text))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun aSettledCardMeasures540x620Centred() {
        present()
        settle()
        val card = compose.onNodeWithTag("settings-dialog").getBoundsInRoot()
        assertEquals(370f, card.left.value, 1f)
        assertEquals(90f, card.top.value, 1f)
        assertEquals(540f, card.width.value, 1f)
        assertEquals(620f, card.height.value, 1f)
        compose.onNodeWithTag("sheet-scrim").assertExists()
        assertTrue(presenter!!.isPresented)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w560dp-h600dp-xhdpi")
    fun aNarrowWindowClampsTheCard() {
        present()
        settle()
        val card = compose.onNodeWithTag("settings-dialog").getBoundsInRoot()
        assertEquals(24f, card.left.value, 1f)
        assertEquals(24f, card.top.value, 1f)
        assertEquals(512f, card.width.value, 1f)
        assertEquals(552f, card.height.value, 1f)
    }

    @Test
    fun theSplitIsNotScaledOrClippedAndHidesFromTalkBackWhileUp() {
        val sidebarBefore = compose.onNodeWithTag("fake-sidebar").getBoundsInRoot()
        assertEquals(0f, sidebarBefore.left.value, 1f)
        assertEquals(360f, sidebarBefore.width.value, 1f)
        present()
        settle()
        // 31 clears the presenting content's semantics while a visible
        // entry exists: TalkBack cannot reach the split behind the card.
        compose.onNodeWithTag("fake-sidebar").assertDoesNotExist()
        // The card dims but never scales or clips the split (its layer is
        // draw-only): the sidebar reappears at exactly x = 0, 360 dp wide,
        // once the card is gone — the golden pins the during state.
        compose.runOnUiThread { state.dismiss(animated = false) }
        // Idle first so the write reaches the recomposer, then one frame
        // recomposes (instantDismissRemovesTheCardInTheSameFrame's order).
        compose.waitForIdle()
        frame()
        val sidebarAfter = compose.onNodeWithTag("fake-sidebar").getBoundsInRoot()
        assertEquals(sidebarBefore.left, sidebarAfter.left)
        assertEquals(sidebarBefore.width, sidebarAfter.width)
    }

    @Test
    fun theTitleAndDoneCircleSitInTheCardNavRow() {
        present()
        settle()
        val card = compose.onNodeWithTag("settings-dialog").getBoundsInRoot()
        // The large title's text box: 16 from the card's left edge, top at
        // 49 (44 nav row + 5) from the card's top.
        val title = compose.onNodeWithTag("nav-large-title").getBoundsInRoot()
        assertEquals(16f, title.left.value - card.left.value, 1f)
        assertEquals(49f, title.top.value - card.top.value, 1f)
        // Done: a 44 × 44 circle, its end edge 16 from the card's right
        // edge, its top at the card's top (centre 22 below it).
        val done = compose.onNodeWithTag("settings-done").getBoundsInRoot()
        assertEquals(44f, done.width.value, 1f)
        assertEquals(44f, done.height.value, 1f)
        assertEquals(16f, card.right.value - done.right.value, 1f)
        assertEquals(0f, done.top.value - card.top.value, 1f)
        assertEquals(22f, done.top.value - card.top.value + done.height.value / 2f, 1f)
        compose.onNodeWithContentDescription("Done").assertExists()
    }

    // ------------------------------------------------- presentation timing

    @Test
    fun presentTracksTheCriticallyDampedSpring() {
        present()
        val e = entry()
        // Pump frames until the offset first leaves H: that frame already
        // carries 16 ms of animation, and the spring's t = 0 was the frame
        // before it (the measure and the LaunchedEffect cost a frame or
        // two — never time from present()).
        while (e.offset.value.isNaN() || e.offset.value >= e.heightPx) {
            frame()
        }
        val t0 = compose.mainClock.currentTime - frameMs
        // Every frame of the flight matches the analytic curve within
        // ±0.01 (reference points: 0.671 at 100 ms, 0.944 at 200 ms; the
        // card's scale 0.94 + 0.06·p and the scrim's 0.2·p are the same
        // progress read in the draw phase, pinned by the goldens).
        while (compose.mainClock.currentTime - t0 < 450L) {
            frame()
            val t = compose.mainClock.currentTime - t0
            assertEquals(springProgress(t), e.progress, 0.01f)
        }
        // Settled (p ≥ 0.999) by 450 ms.
        assertTrue("settled progress was ${e.progress}", e.progress >= 0.999f)
    }

    @Test
    fun theScrimConsumesOutsideTapsWithoutReachingTheSplit() {
        present()
        settle()
        // (100, 400) px = (50, 200) dp: inside the 360 dp sidebar, well
        // outside the card (x 370…910 dp). The tap does nothing.
        compose.onNodeWithTag("sheet-scrim").performTouchInput { click(Offset(100f, 400f)) }
        compose.waitForIdle()
        assertEquals(1, state.entries.size)
        assertTrue(presenter!!.isPresented)
        assertEquals(0, sidebarTaps)
    }

    // --------------------------------------------------------- dismissal

    @Test
    fun doneDismissesTheCardAnimatedlyAndDisposesTheRootOnce() {
        present()
        settle()
        assertEquals(0, rootDisposals)
        compose.onNodeWithTag("settings-done").performClick()
        compose.waitForIdle()
        // Mid-flight: the entry is dismissing, the card is animating out
        // (progress between 0 and 1 → scale toward 0.94, alpha toward 0)
        // and its input is blocked.
        assertTrue(entry().dismissing)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val mid = entry().progress
        assertTrue("mid-dismiss progress was $mid", mid in 0.001f..0.999f)
        settle()
        assertFalse(presenter!!.isPresented)
        assertTrue(state.entries.isEmpty())
        compose.onNodeWithTag("settings-dialog").assertDoesNotExist()
        assertEquals(1, rootDisposals)
    }

    @Test
    fun instantDismissRemovesTheCardInTheSameFrame() {
        present()
        settle()
        compose.runOnUiThread { state.dismiss(animated = false) }
        compose.waitForIdle()
        frame() // recompose: the card leaves composition, the split's semantics return
        assertTrue(state.entries.isEmpty())
        assertFalse(presenter!!.isPresented)
        compose.onNodeWithTag("settings-dialog").assertDoesNotExist()
        compose.onNodeWithTag("fake-sidebar").assertExists()
        assertEquals(1, rootDisposals)
    }

    // ------------------------------------------------------------- stack

    @Test
    fun pushingARouteSlidesItInsideTheCardWithoutDone() {
        present()
        settle()
        scrollToRow("Push detail")
        compose.onNodeWithText("Push detail").performClick()
        settle()
        val card = compose.onNodeWithTag("settings-dialog").getBoundsInRoot()
        compose.onNodeWithText("Engine").assertExists()
        val pushed = compose.onNodeWithText("Engine").getBoundsInRoot()
        // Clipped to the card: the pushed screen stays inside its bounds.
        assertTrue(pushed.left.value >= card.left.value - 1f)
        assertTrue(pushed.right.value <= card.right.value + 1f)
        // The Done circle is the root's only. (25's back circle belongs to a
        // route screen's own LargeTitleScaffold; this fixture's NavDemoScreen
        // draws none, so it is not asserted here - LargeTitleScaffoldTest
        // covers it.) The covered root stays composed but unplaced, and the
        // test semantics tree keeps unplaced nodes (TalkBack's does not):
        // the root's Done is not on screen rather than absent.
        compose.onNodeWithTag("settings-done").assertIsNotDisplayed()
    }

    @Test
    fun backPopsThePushedScreenAndKeepsTheCard() {
        present()
        settle()
        scrollToRow("Push detail")
        compose.onNodeWithText("Push detail").performClick()
        settle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertTrue(presenter!!.isPresented)
        compose.onNodeWithTag("settings-dialog").assertExists()
        compose.onNodeWithText("Engine").assertDoesNotExist()
        // The root is back: its Done circle too.
        compose.onNodeWithTag("settings-done").assertExists()
    }

    @Test
    fun backAtTheRootDismissesTheCard() {
        present()
        settle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertTrue(state.entries.isEmpty())
        assertFalse(presenter!!.isPresented)
        assertEquals(1, rootDisposals)
    }

    @Test
    fun predictiveBackScrubsCancelsAndCommits() {
        present()
        settle()
        val e = entry()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        // p = 1 − 0.25·0.5 = 0.875 → scale 0.9925, alpha 0.875, scrim 0.175.
        assertEquals(0.875f, e.progress, 0.001f)
        assertEquals(0.9925f, SettingsDialogMath.scale(e.progress, false), 1e-4f)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        settle()
        // Cancelled: the card springs back to fully presented.
        assertEquals(1f, e.progress, 0.001f)
        assertEquals(1, state.entries.size)
        // A repeated gesture, committed this time: dismisses the card.
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.8f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(1, rootDisposals)
    }

    @Test
    fun aStackedPageSheetDismissesAloneOnBack() {
        present()
        settle()
        scrollToRow("Stack page sheet")
        compose.onNodeWithText("Stack page sheet").performClick()
        settle()
        assertEquals(2, state.entries.size)
        assertEquals("demo-pair", state.entries[1].key)
        assertEquals(SheetStyle.Page(grabber = true), state.entries[1].style)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, state.entries.size)
        assertEquals(SettingsDialogDefaults.Key, state.entries.single().key)
        // The card underneath is neither dismissed nor scaled (31 never
        // recedes a Custom's presenter, and iPad sheets do not recede).
        assertEquals(1f, entry().progress, 0.001f)
        assertTrue(presenter!!.isPresented)
    }

    @Test
    fun presentWhileAnythingIsUpIsTheUIKitNoOp() {
        present()
        settle()
        compose.runOnUiThread { assertFalse(presenter!!.present()) }
        compose.waitForIdle()
        assertEquals(1, state.entries.size)
        scrollToRow("Stack page sheet")
        compose.onNodeWithText("Stack page sheet").performClick()
        settle()
        // Also while a sheet is stacked on the card.
        compose.runOnUiThread { assertFalse(presenter!!.present()) }
        compose.waitForIdle()
        assertEquals(2, state.entries.size)
    }

    @Test
    fun openingASessionFromInsideTheCardRoutesToTheAppRouter() {
        present()
        settle()
        scrollToRow("Archived Sessions")
        compose.onNodeWithText("Archived Sessions").performClick()
        compose.waitForIdle()
        settle()
        // Once, through the router — never a push on the card's stack.
        assertEquals(listOf("s1"), router.sessions)
        assertTrue(presenter!!.isPresented)
        compose.onNodeWithTag("settings-done").assertExists()
        compose.onNodeWithText("Engine").assertDoesNotExist()
    }

    // ------------------------------------------------------ motion modes

    @Test
    fun reducedMotionPresentsAndDismissesAs150MsFades() {
        ReduceMotion.debugOverride = true
        present()
        // Compose + measure first (a frame or two)...
        while (entry().offset.value.isNaN()) frame()
        // ...then the 150 ms tween is done long before the 0.4 s spring
        // would be (p(250 ms) ≈ 0.992, not 1).
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(1f, entry().progress, 0.001f)
        assertEquals(0f, entry().offset.value, 1f)
        compose.onNodeWithTag("settings-done").performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        assertTrue(state.entries.isEmpty())
        assertEquals(1, rootDisposals)
    }

    // ---------------------------------------------- semantics and the IME

    @Test
    fun theCardExposesThePaneTitleAndDismissActionAndHidesTheSplit() {
        present()
        settle()
        val config = compose.onNodeWithTag("settings-dialog").fetchSemanticsNode().config
        assertEquals("Settings", config.getOrNull(SemanticsProperties.PaneTitle))
        val actions = config.getOrNull(SemanticsActions.CustomActions)
        assertEquals(listOf("Dismiss"), actions?.map { it.label })
        // The VoiceOver escape action dismisses the card.
        assertNotNull(actions)
        compose.runOnUiThread { actions!!.single().action.invoke() }
        settle()
        assertTrue(state.entries.isEmpty())
        assertEquals(1, rootDisposals)
    }

    @Test
    fun presentingTheCardPutsTheSidebarKeyboardAway() {
        compose.onNodeWithTag("split-field").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("split-field").assertIsFocused()
        present()
        settle()
        // The card is up: the split's semantics are cleared, so the
        // keyboard state is checked once the card is gone again.
        compose.runOnUiThread { state.dismiss(animated = false) }
        // Idle first so the write reaches the recomposer, then one frame
        // recomposes (instantDismissRemovesTheCardInTheSameFrame's order).
        compose.waitForIdle()
        frame()
        compose.onNodeWithTag("split-field").assertIsNotFocused()
    }
}
