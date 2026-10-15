package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.withKeysDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The split shell's frame (android-ui-parity #33) at 1280 × 800 dp × 2.0
 * (xhdpi — the ticket's hairline pixels are physical): pane bounds, the
 * 1-px hairline, the 300 ms easeInOut show/hide through a focused field,
 * the hidden sidebar's state/semantics/focus, the detail slot's
 * re-invocation, `LocalPrefersLargeTitles` and state restoration.
 *
 * The shortcut *matcher* table and the sink/helper are
 * [ShellShortcutsTest]; the phone shell's one-line wiring is
 * [MainTabShellShortcutTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class SplitRootTest {

    @get:Rule
    val compose = createComposeRule()

    // Observables the fixtures write from composition / callbacks (the
    // composition runs on the test's main thread; assertions read after
    // waitForIdle on the same thread).
    // Snapshot state: the detail field is a controlled BasicTextField, so
    // its value must recompose with each edit — a plain var keeps feeding
    // "" back and every key is applied to an empty field.
    private var detailFieldText: String by mutableStateOf("")
    private var sidebarFieldFocused: Boolean = false
    private var detailLargeTitles: Boolean? = null
    private var sidebarListState: LazyListState? = null

    /**
     * A split that is up: `isCollapsed` defaults to `true` (the ticket's
     * table — its one production writer is the root's `SideEffect`, which
     * this fixture stands in for), and Ctrl+B is ignored while collapsed.
     */
    private fun shell(visible: Boolean = true): TabletShellState =
        TabletShellState(null, visible, DetailRoot.Draft(null, false)).apply { isCollapsed = false }

    /** Hands a test-thread / key-dispatch snapshot write to the Recomposer (frozen clock). */
    private fun sendApplyNotifications() {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    /** The default sidebar fixture: a focusable field plus a labelled row. */
    @Composable
    private fun FieldSidebar() {
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = "",
                onValueChange = { },
                modifier = Modifier
                    .width(200.dp)
                    .testTag("split-sidebar-field")
                    .onFocusChanged { state -> sidebarFieldFocused = state.isFocused },
            )
            BasicText(
                "sidebar content",
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("split-sidebar-label"),
            )
        }
    }

    /** The state-retention fixture: 40 rows in a LazyColumn. */
    @Composable
    private fun LazySidebar() {
        val state = rememberLazyListState()
        sidebarListState = state
        LazyColumn(Modifier.fillMaxSize().testTag("split-sidebar-list"), state = state) {
            items(40) { index ->
                BasicText(
                    "Row ${index + 1}",
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("split-sidebar-row-$index"),
                )
            }
        }
    }

    /** The default detail fixture: the root's text plus a focusable field. */
    @Composable
    private fun FieldDetail(root: DetailRoot) {
        detailLargeTitles = LocalPrefersLargeTitles.current
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(root.toString(), Modifier.testTag("split-detail-root"))
            BasicTextField(
                value = detailFieldText,
                onValueChange = { detailFieldText = it },
                modifier = Modifier
                    .width(240.dp)
                    .testTag("split-detail-field"),
            )
        }
    }

    private fun setContent(
        state: TabletShellState,
        sidebar: @Composable (Boolean) -> Unit = { FieldSidebar() },
        detail: @Composable (DetailRoot) -> Unit = { root -> FieldDetail(root) },
        router: AppRouter = AppRouter.Noop,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                SplitRoot(shell = state, sidebar = sidebar, detail = detail, router = router)
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ pane bounds

    @Test
    fun panesOnA1280dpWindow() {
        setContent(shell())
        compose.onNodeWithTag("split-sidebar").apply {
            assertLeftPositionInRootIsEqualTo(0.dp)
            assertTopPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(360.dp)
            assertHeightIsEqualTo(800.dp)
        }
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(360.dp)
            assertTopPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(920.dp)
            assertHeightIsEqualTo(800.dp)
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h1200dp-xhdpi")
    fun panesOnA840dpWindow() {
        setContent(shell())
        compose.onNodeWithTag("split-sidebar").apply {
            assertWidthIsEqualTo(360.dp)
            assertHeightIsEqualTo(1200.dp)
        }
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(360.dp)
            assertWidthIsEqualTo(480.dp)
        }
    }

    // --------------------------------------------------------- the hairline

    @Test
    fun hairlineIsExactlyOnePhysicalPixel() {
        // Density 2.0: the sidebar spans px 0..719, so its trailing pixel
        // column 719 is the hairline, 718 the sidebar fill and 720 the
        // detail fill. Mid-height avoids the fixture content.
        setContent(shell())
        val px = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(0xFFE2E2E6.toInt(), px[719, 800].toArgb())
        assertEquals(0xFFF3F3F5.toInt(), px[718, 800].toArgb())
        assertEquals(0xFFF3F3F5.toInt(), px[720, 800].toArgb())
        // Full height — through the status-bar and nav-bar areas too.
        assertEquals(0xFFE2E2E6.toInt(), px[719, 0].toArgb())
        assertEquals(0xFFE2E2E6.toInt(), px[719, px.height - 1].toArgb())
    }

    @Test
    fun hairlineDrawsOverSidebarContent() {
        // A red full-bleed sidebar fixture still shows the hairline column.
        setContent(
            shell(),
            sidebar = { Box(Modifier.fillMaxSize().background(Color.Red)) },
            detail = { Box(Modifier.fillMaxSize()) },
        )
        val px = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(0xFFFF0000.toInt(), px[718, 800].toArgb())
        assertEquals(0xFFE2E2E6.toInt(), px[719, 800].toArgb())
        assertEquals(0xFFF3F3F5.toInt(), px[720, 800].toArgb())
    }

    // ------------------------------------------------------- the show/hide

    @Test
    fun ctrlBHidesTheSidebarWhileAFieldIsFocused() {
        val state = shell()
        setContent(state)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        compose.waitForIdle()

        // t = 0: no frame has run — the fraction is untouched and the
        // sidebar still sits at x = 0 (easeInOut starts at a standstill).
        assertEquals(1f, state.sidebarFraction)
        compose.onNodeWithTag("split-sidebar").assertLeftPositionInRootIsEqualTo(0.dp)

        // Mid-flight: strictly between 0 and −360 dp.
        compose.mainClock.advanceTimeBy(150)
        val mid = compose.onNodeWithTag("split-sidebar").fetchSemanticsNode().positionInRoot.x
        assertTrue("mid-flight sidebar x must be strictly inside (-720, 0) px: $mid", mid > -720 && mid < 0)

        // >= 300 ms + a frame: settled hidden — the detail fills the window
        // and the sidebar left the semantics tree entirely.
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(1280.dp)
        }
        compose.onNodeWithTag("split-sidebar").assertDoesNotExist()
        assertEquals(0f, state.sidebarFraction)
        // No "b" was typed: the preview handler consumed the event before
        // the focused field.
        assertEquals("", detailFieldText)
    }

    @Test
    fun ctrlBShowsTheSidebarAgainOver300ms() {
        val state = shell(visible = false)
        setContent(state)
        compose.onNodeWithTag("split-sidebar").assertDoesNotExist()
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(150)
        val mid = compose.onNodeWithTag("split-sidebar").fetchSemanticsNode().positionInRoot.x
        assertTrue("mid-flight sidebar x must be strictly inside (-720, 0) px: $mid", mid > -720 && mid < 0)

        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithTag("split-sidebar").apply {
            assertLeftPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(360.dp)
        }
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(360.dp)
            assertWidthIsEqualTo(920.dp)
        }
    }

    @Test
    fun togglingTwiceWithin100msReversesFromTheCurrentPosition() {
        val state = shell()
        setContent(state)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        // The toggle is written during key dispatch: hand it over, or the
        // frozen frames never see it.
        sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        val p1 = compose.onNodeWithTag("split-sidebar").fetchSemanticsNode().positionInRoot.x
        assertTrue("p1 mid-flight: $p1", p1 > -720 && p1 < 0)

        // Reverse before it settles: no jump to an end value — the sidebar
        // turns around from where it is and slides back toward 0.
        //
        // The turnaround is not on the very next frame, and the old
        // `p2 > p1` after two frames could not hold (CI r30: p1=-154,
        // p2=-198). The hide keeps going for a frame after the key (the
        // collector picks up the new target after that frame is queued), the
        // relaunched animateTo takes its start time on the next one without
        // moving, and a 300 ms ease-in-out tween starting at rest moves only
        // ~4 px on its first step — it cannot climb back past p1 in one
        // frame. So: sample frame by frame and check the shape of the motion
        // instead of comparing against p1.
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        sendApplyNotifications()
        val samples = mutableListOf(p1)
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            samples += compose.onNodeWithTag("split-sidebar").fetchSemanticsNode().positionInRoot.x
        }
        for (p in samples) assertTrue("mid-flight: $samples", p > -720 && p < 0)
        // A frame's step, never a jump: a jump to either end from here is
        // >= 150 px (to 0) or >= 500 px (to -720); the steepest frame step of
        // this tween over 720 px is ~65 px.
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue("a frame's step, not a jump: $samples", kotlin.math.abs(b - a) < 100f)
        }
        // It turns around early — at most a few frames of hide after the key
        // — and is moving back up by the end.
        val turn = samples.indexOf(samples.min())
        assertTrue("the reverse must start within 3 frames: $samples", turn <= 3)
        assertTrue("the reverse must move back up: $samples", samples.last() > samples.min() + 20f)
        assertTrue("and keep moving up: $samples", samples.last() > samples[samples.size - 2])
    }

    @Test
    fun holdingCtrlBDoesNotStrobe() {
        // Repeats (repeatCount >= 1) are not commands; the key held past
        // the 500 ms auto-repeat delay injects repeat events, which must
        // not re-toggle. (The exact matcher guarantee is
        // ShellShortcutsTest.repeatsDoNotMatch.)
        val state = shell()
        setContent(state)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(Key.B)
                advanceEventTime(600)
                keyUp(Key.B)
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        // Settled hidden: the detail fills the window.
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(1280.dp)
        }
        assertEquals(0f, state.sidebarFraction)
    }

    // --------------------------------------------- the hidden sidebar's life

    @Test
    fun hiddenSidebarKeepsItsStateAndLeavesSemantics() {
        val state = shell()
        setContent(state, sidebar = { LazySidebar() })

        compose.onNodeWithTag("split-sidebar-row-1").assertExists()
        compose.onNodeWithTag("split-sidebar-list").performScrollToIndex(20)
        compose.waitForIdle()
        assertEquals(20, sidebarListState!!.firstVisibleItemIndex)

        state.toggleSidebar()
        compose.waitForIdle()
        // Settled hidden: no semantics nodes at all.
        compose.onNodeWithTag("split-sidebar-list").assertDoesNotExist()
        compose.onNodeWithTag("split-sidebar-row-20").assertDoesNotExist()
        // ... but still composed: the LazyListState (the scroll position)
        // survives untouched.
        assertEquals(20, sidebarListState!!.firstVisibleItemIndex)

        state.toggleSidebar()
        compose.waitForIdle()
        // Item 20 is still first visible — the scroll position survived.
        assertEquals(20, sidebarListState!!.firstVisibleItemIndex)
        compose.onNodeWithTag("split-sidebar-row-20")
            .assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun hidingTheSidebarClearsFocus() {
        val state = shell()
        setContent(state, sidebar = { FieldSidebar() })
        compose.onNodeWithTag("split-sidebar-field").requestFocus()
        compose.waitForIdle()
        assertTrue(sidebarFieldFocused)

        state.toggleSidebar()
        compose.waitForIdle()
        // The off-screen field must not keep focus (or the IME) up.
        assertFalse(sidebarFieldFocused)
    }

    @Test
    fun tabTraversalNeverLandsInTheHiddenSidebar() {
        val state = shell(visible = false)
        setContent(state, sidebar = { FieldSidebar() })
        compose.onNodeWithTag("split-sidebar-field").assertDoesNotExist()
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        repeat(6) {
            compose.onNodeWithTag("split-detail-field").performKeyInput { pressKey(Key.Tab) }
            compose.waitForIdle()
        }
        // Focus never entered the hidden pane.
        assertFalse(sidebarFieldFocused)
    }

    // -------------------------------------------------------- the detail slot

    @Test
    fun setDetailRootReInvokesTheSlotWithoutAnimating() {
        val state = shell()
        setContent(state)
        compose.onNodeWithText("Draft(prompt=null, focusOnAppear=false)").assertExists()

        state.setDetailRoot(DetailRoot.Session("a"))
        compose.waitForIdle()
        compose.onNodeWithText("Session(chatId=a)").assertExists()

        state.setDetailRoot(DetailRoot.Session("b"))
        compose.waitForIdle()
        compose.onNodeWithText("Session(chatId=b)").assertExists()
        compose.onNodeWithText("Session(chatId=a)").assertDoesNotExist()

        // Nothing in the frame animated: the pane never moved.
        compose.onNodeWithTag("split-sidebar").assertLeftPositionInRootIsEqualTo(0.dp)
        assertEquals(1f, state.sidebarFraction)
        assertEquals(2, state.detailGeneration)
    }

    @Test
    fun detailPaneForcesInlineTitles() {
        setContent(shell())
        compose.waitForIdle()
        // SplitRootController's detail column: `prefersLargeTitles` off, so
        // a ticket-25 LargeTitleScaffold in TitleMode.Always renders inline
        // only inside this pane.
        assertEquals(false, detailLargeTitles)
    }

    // ------------------------------------------------------------- shortcuts

    @Test
    fun ctrlNAndCtrlShiftFReachTheRouterWithAFieldFocused() {
        val router = RecordingRouter()
        val state = shell()
        setContent(state, router = router)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.N) }
        }
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeysDown(listOf(Key.CtrlLeft, Key.ShiftLeft)) { pressKey(Key.F) }
        }
        compose.waitForIdle()
        assertEquals(listOf("presentNewSession(null)", "showSearch()"), router.calls)
        // No letters typed into the focused field either.
        assertEquals("", detailFieldText)
        // The shortcut never touched the sidebar.
        assertEquals(1f, state.sidebarFraction)
    }

    @Test
    fun unmatchedKeysAreNotConsumed() {
        val router = RecordingRouter()
        val state = shell()
        setContent(state, router = router)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        // Ctrl+Shift+N, Ctrl+F and Meta+N: matched by nothing, the shell
        // must not consume them (no router call, no toggle).
        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeysDown(listOf(Key.CtrlLeft, Key.ShiftLeft)) { pressKey(Key.N) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.F) }
            withKeyDown(Key.MetaLeft) { pressKey(Key.N) }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), router.calls)
        assertEquals(1f, state.sidebarFraction)
    }

    @Test
    fun plainKeysAreTypedIntoTheFocusedField() {
        setContent(shell())
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithTag("split-detail-field").performKeyInput {
            pressKey(Key.N)
            pressKey(Key.F)
            pressKey(Key.B)
        }
        compose.waitForIdle()
        assertEquals("nfb", detailFieldText)
    }

    @Test
    fun ctrlBIsIgnoredWhileCollapsed() {
        val state = shell()
        state.isCollapsed = true
        setContent(state)
        compose.onNodeWithTag("split-detail-field").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithTag("split-detail-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        compose.waitForIdle()
        assertTrue(state.sidebarVisible)
        compose.onNodeWithTag("split-sidebar").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    // ------------------------------------------------------ state restoration

    @Test
    fun stateRestorationKeepsChatSidebarAndDetailRoot() {
        val restoration = StateRestorationTester(compose)
        var current: TabletShellState? = null
        restoration.setContent {
            RobocoTheme(Appearance.Light) {
                SplitRoot(
                    shell = rememberTabletShellState().also { current = it },
                    sidebar = { FieldSidebar() },
                    detail = { root -> FieldDetail(root) },
                )
            }
        }
        val first = requireNotNull(current)

        first.currentChatId = "chat-7"
        first.toggleSidebar()
        first.setDetailRoot(DetailRoot.Draft("hi", true))

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        val second = requireNotNull(current)
        assertNotSame(first, second)
        assertEquals("chat-7", second.currentChatId)
        assertFalse(second.sidebarVisible)
        // focusOnAppear is one-shot: never restored.
        assertEquals(DetailRoot.Draft("hi", false), second.detailRoot)

        // Hidden without animating: the detail already fills the window on
        // the first frame after restore (the fraction re-snapped, it never
        // tweened from 1).
        compose.onNodeWithTag("split-detail").apply {
            assertLeftPositionInRootIsEqualTo(0.dp)
            assertWidthIsEqualTo(1280.dp)
        }
        compose.onNodeWithTag("split-sidebar").assertDoesNotExist()
        assertEquals(0f, second.sidebarFraction)
    }

    // ------------------------------------------------------------- fixtures

    private class RecordingRouter : AppRouter {
        val calls = mutableListOf<String>()
        override fun openSession(chatId: String) {
            calls.add("openSession($chatId)")
        }
        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls.add("openSession($chatId, handoff)")
        }
        override fun presentNewSession(prompt: String?) {
            calls.add("presentNewSession($prompt)")
        }
        override fun showSettings() {
            calls.add("showSettings()")
        }
        override fun showSearch() {
            calls.add("showSearch()")
        }
        override fun showSessionOpenError() {
            calls.add("showSessionOpenError()")
        }
        override fun popToFrontPage() {
            calls.add("popToFrontPage()")
        }
    }
}
