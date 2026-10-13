package sh.roboco.android.design.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import kotlinx.coroutines.delay
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme

/**
 * The menu's behaviour (android-ui-parity #15) through a real
 * [GlassMenuHost] — Robolectric + compose-ui-test, the clock frozen and
 * advanced like `ToastHostTest`. The device is `w393dp-h852dp-xxhdpi`
 * (1 dp = 3 px; Robolectric reports no insets, so the bounds are the full
 * 1179 × 2556 window) and the anchor is a 60 × 44 px window-space rect in
 * the top-right quadrant (1000, 200): the panel opens below, right edge
 * aligned, at (310, 200).
 *
 * `Glass.forceFallback` is on for the whole class (the golden convention):
 * the panels draw the fallback fill; nothing here depends on blur.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class GlassMenuBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private var density: Density = Density(1f)

    private val anchor = Rect(1000f, 200f, 1060f, 244f)

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun tearDown() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    /** Records every haptic; the fake Feedback provided via LocalFeedback. */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: sh.roboco.android.design.Cue) = Unit
    }

    /** Test-side handle on the remembered [GlassMenuState]. */
    private class StateRef {
        var state: GlassMenuState? = null
    }

    /** The host under test: a full-size clickable "underneath" plus [content]. */
    @Composable
    private fun Harness(
        feedback: Feedback = NoFeedback,
        stateRef: StateRef? = null,
        onUnderClick: (() -> Unit)? = null,
        content: @Composable () -> Unit = {},
    ) {
        density = LocalDensity.current
        RobocoTheme(Appearance.Light) {
            CompositionLocalProvider(LocalFeedback provides feedback) {
                GlassMenuHost {
                    if (stateRef != null) {
                        val state = rememberGlassMenuState()
                        stateRef.state = state
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("under")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onUnderClick?.invoke() },
                            ),
                    )
                    content()
                }
            }
        }
    }

    private fun dpPx(dp: Float): Float = with(density) { dp.dp.toPx() }

    private val hasProgressKey = SemanticsMatcher("has GlassMenuProgress") {
        it.config.contains(GlassMenuProgressKey)
    }

    /** The panel's animating alpha, via the test-only progress key. */
    private fun progress(): Float {
        val nodes = compose.onAllNodes(hasProgressKey).fetchSemanticsNodes()
        assertTrue("no GlassMenuProgressKey node in the tree", nodes.isNotEmpty())
        return nodes.first().config[GlassMenuProgressKey]
    }

    /*
     * The frozen clock (autoAdvance = false) and state writes made outside a
     * frame (open/close calls, taps, Back, a deferred load resuming after a
     * delay): `waitForIdle` only sends the snapshot apply notifications
     * (ComposeIdlingResource.isIdleNow pumps frames only under autoAdvance),
     * and `advanceTimeBy` alone never delivers them, so such a write is
     * recomposed by the first frame *after* a `waitForIdle`. The helpers
     * below always pair the two.
     */

    /** Delivers pending writes and runs the frame that recomposes them. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /**
     * Delivers pending writes, advances [ms], then recomposes whatever was
     * written off-frame during the advance (a load's result, `onClosed`).
     */
    private fun advance(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        frame()
    }

    /** Opens [menu]; one frame composes the overlay (frame 0 of the open). */
    private fun openMenu(state: GlassMenuState, menu: Menu): Boolean {
        val opened = state.open(anchor, menu)
        frame()
        return opened
    }

    private fun settleOpen() {
        advance(600) // past the 0.35 s open spring
    }

    private fun finishClose() {
        advance(250) // past the 150 ms close fade, then the removal frame
    }

    private fun plainMenu(): Menu = Menu(
        children = listOf(
            MenuItem("Pick me") { actions++ },
            MenuItem("Also me") {},
        ),
    )

    private var actions = 0

    // ------------------------------------------------------- dismissal

    @Test
    fun anOutsideTapClosesTheMenuAndDoesNotClickTheButtonUnderneath() {
        val ref = StateRef()
        var underClicks = 0
        compose.setContent {
            Harness(stateRef = ref, onUnderClick = { underClicks++ })
        }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, plainMenu()))
        settleOpen()
        compose.onNodeWithTag("glass-menu").assertExists()

        // The tap lands on the scrim (the panel is top-right), not the "under" box.
        compose.onNodeWithTag("under").performClick()
        assertEquals("the dismissing tap must not pass through", 0, underClicks)
        assertTrue("the close started at once", ref.state!!.isClosing)

        finishClose()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertFalse(ref.state!!.isOpen)
    }

    @Test
    fun aTapOnThePanelRootSwallowsInsteadOfClosing() {
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, plainMenu()))
        settleOpen()

        // A tap on the menu's own padding (below the last row, still on the
        // panel): the panel swallows it; the scrim never sees it.
        val panel = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        val bottom = panel.positionInRoot.y + panel.size.height - 6f
        compose.onNodeWithTag("glass-menu-scrim").performTouchInput {
            click(Offset(panel.positionInRoot.x + 20f, bottom))
        }
        assertFalse("a tap on the panel must not close the menu", ref.state!!.isClosing)
        compose.onNodeWithTag("glass-menu").assertExists()
    }

    @Test
    fun aTapOnADisabledRowKeepsTheMenuOpen() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuItem("Move Up", disabled = true) {},
                MenuItem("Move Down") {},
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        settleOpen()

        compose.onNodeWithText("Move Up").performClick()
        assertFalse("a disabled row tap must not close the menu", ref.state!!.isClosing)
        compose.onNodeWithTag("glass-menu").assertExists()
    }

    // ------------------------------------------------------- back / drill-in

    private fun drillMenu(): Menu = Menu(
        children = listOf(
            MenuItem("Pin") {},
            MenuSection(
                title = "Move to Section",
                inline = false,
                children = listOf(MenuItem("Bugs") {}, MenuItem("No Section") {}),
            ),
            MenuItem("Archive") {},
        ),
    )

    @Test
    fun systemBackPopsADrilledLevelThenClosesAtTheRoot() {
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, drillMenu()))
        settleOpen()

        compose.onNodeWithText("Move to Section").performClick()
        advance(250) // past the drill cross-fade
        compose.onNodeWithTag("glass-menu-back").assertExists()
        compose.onNodeWithText("Pin").assertDoesNotExist()

        Espresso.pressBack() // pops the drilled level
        advance(250)
        compose.onNodeWithTag("glass-menu-back").assertDoesNotExist()
        compose.onNodeWithText("Pin").assertExists()

        Espresso.pressBack() // at the root: closes
        finishClose()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun drillingReplacesTheContentInTheSamePanel() {
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, drillMenu()))
        settleOpen()
        compose.onNodeWithText("Move to Section")
            .assert(SemanticsMatcher("is a submenu row") {
                it.config[SemanticsProperties.StateDescription] == "Submenu"
            })

        compose.onNodeWithText("Move to Section").performClick()
        advance(250)
        assertEquals("never a second panel", 1, compose.onAllNodesWithTag("glass-menu").fetchSemanticsNodes().size)
        compose.onNodeWithTag("glass-menu-back").assertExists()
        compose.onNodeWithText("Bugs").assertExists()
        compose.onNodeWithText("Pin").assertDoesNotExist()

        compose.onNodeWithTag("glass-menu-back").performClick()
        advance(250)
        compose.onNodeWithText("Pin").assertExists()
        compose.onNodeWithTag("glass-menu-back").assertDoesNotExist()
    }

    // ------------------------------------------------------- picks

    @Test
    fun aPickClosesTheMenuAndRunsTheActionOnceAfterTheCloseStarts() {
        val ref = StateRef()
        actions = 0
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, plainMenu()))
        settleOpen()

        compose.onNodeWithText("Pick me").performClick()
        assertTrue("isClosing flips before the action runs", ref.state!!.isClosing)
        assertEquals("the action runs on the next frame, not synchronously", 0, actions)
        // Deliver the close and run the pick's launched coroutine up to its
        // withFrameNanos (advanceTimeBy(0) = runCurrent, no frame), so the
        // next frame is the one that runs the action. Still no action.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(0)
        assertEquals("no frame yet, no action", 0, actions)

        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("the action ran exactly once", 1, actions)

        finishClose()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertEquals(1, actions)
    }

    @Test
    fun anEmptyMenuDoesNotOpen() {
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertFalse(openMenu(ref.state!!, Menu(null, emptyList())))
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    // ------------------------------------------------------- the menu lambda

    @Test
    fun theMenuLambdaRunsOncePerOpenAndNeverDuringRecomposition() {
        var built = 0
        var tick by mutableStateOf(0)
        compose.setContent {
            Harness {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(100)
                        tick++
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("trigger")
                        .glassMenu(menu = { built++; plainMenu() }),
                ) {}
            }
        }
        compose.waitForIdle()
        assertEquals(0, built)

        compose.mainClock.autoAdvance = true
        repeat(3) {
            compose.onNodeWithTag("trigger").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("glass-menu").assertExists()
            Espresso.pressBack()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        }
        assertEquals("the lambda ran exactly once per open", 3, built)

        // Recompositions alone (the tick label) never evaluate the lambda.
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertTrue(tick > 0)
        assertEquals(3, built)
    }

    // ------------------------------------------------------- triggers

    @Test
    fun aTapTriggerRecordsNoHapticAndOpensTheMenu() {
        val feedback = RecordingFeedback()
        compose.setContent {
            Harness(feedback) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("trigger")
                        .glassMenu(menu = { plainMenu() }),
                ) {}
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("trigger").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        assertTrue("tap triggers are silent", feedback.haptics.isEmpty())
    }

    @Test
    fun aLongPressTriggerOpensTheMenuOnceWithOneHapticAndATapRunsOnClick() {
        val feedback = RecordingFeedback()
        var taps = 0
        compose.setContent {
            Harness(feedback) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("trigger")
                        .glassMenu(
                            trigger = MenuTrigger.LongPress,
                            onClick = { taps++ },
                            menu = { plainMenu() },
                        ),
                ) {}
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag("trigger").performClick()
        compose.waitForIdle()
        assertEquals("a tap still runs the element's own onClick", 1, taps)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertTrue(feedback.haptics.isEmpty())

        compose.onNodeWithTag("trigger").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        assertEquals(listOf(Haptic.LongPress), feedback.haptics)
        assertEquals("the long-press tap was not a click", 1, taps)
    }

    @Test
    fun aLongPressOnAnEmptyMenuFiresNoHaptic() {
        val feedback = RecordingFeedback()
        compose.setContent {
            Harness(feedback) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("trigger")
                        .glassMenu(trigger = MenuTrigger.LongPress, menu = { Menu(null, emptyList()) }),
                ) {}
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("trigger").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("no haptic when the menu is empty", feedback.haptics.isEmpty())
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    // ------------------------------------------------------- open/close motion

    @Test
    fun theOpenSpringFadesInAndTheCloseFadesOutOver150Ms() {
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, plainMenu()))

        // Frame 0: fully transparent (scale 0.9, alpha 0).
        assertEquals(0f, progress(), 1e-4f)
        // Early in the open flight: in flight. The open spring is
        // settle-matched (IosSpring: within 0.1 % at T = 0.35 s, ζ 0.86), so
        // at half of T it is already ≈0.94; 80 ms after frame 0 (≈64 ms of
        // playtime) it is ≈0.47 — neither a snap nor a slow tween.
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertTrue("in flight early in the spring (was ${progress()})", progress() in 0.1f..0.9f)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(1f, progress(), 1e-4f)

        // The close: a 150 ms easeInOut alpha fade.
        ref.state!!.close()
        compose.waitForIdle()
        assertTrue(ref.state!!.isClosing)
        compose.mainClock.advanceTimeBy(75)
        compose.waitForIdle()
        assertTrue("half-closed at 75 ms (was ${progress()})", progress() in 0.1f..0.9f)
        // The fade's end clears the presentation off-frame (onClosed).
        advance(300)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun underReducedMotionTheMenuFadesInOver150Ms() {
        ReduceMotion.debugOverride = true
        val ref = StateRef()
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, plainMenu()))

        assertEquals(0f, progress(), 1e-4f)
        compose.mainClock.advanceTimeBy(75)
        compose.waitForIdle()
        assertTrue("half-faded at 75 ms (was ${progress()})", progress() in 0.1f..0.9f)
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        assertEquals(1f, progress(), 1e-4f)
    }

    @Test
    fun theMenuLambdaAlsoRunsOncePerLongPressOpen() {
        var built = 0
        compose.setContent {
            Harness {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("trigger")
                        .glassMenu(
                            trigger = MenuTrigger.LongPress,
                            menu = { built++; plainMenu() },
                        ),
                ) {}
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        repeat(3) {
            compose.onNodeWithTag("trigger").performTouchInput { longClick() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            Espresso.pressBack()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
        assertEquals("the long-press trigger also builds exactly one menu per open", 3, built)
    }

    @Test
    fun openingASecondMenuReplacesTheFirstAndFiresItsOnDismissed() {
        val ref = StateRef()
        var firstDismissed = 0
        val first = plainMenu()
        compose.setContent {
            Harness(stateRef = ref) {
                GlassMenuPopup(anchor, first, onDismissed = { firstDismissed++ })
            }
        }
        // The popup presents from its DisposableEffect, after the first
        // composition: one more frame composes the overlay.
        frame()
        compose.onNodeWithText("Pick me").assertExists()
        assertEquals(0, firstDismissed)

        // The second menu replaces the first instantly and notifies it.
        assertTrue(
            openMenu(
                ref.state!!,
                Menu(children = listOf(MenuItem("Second menu row") {})),
            ),
        )
        compose.waitForIdle()
        assertEquals("the superseded menu's onDismissed fired", 1, firstDismissed)
        assertEquals("one menu at a time", 1, compose.onAllNodesWithTag("glass-menu").fetchSemanticsNodes().size)
        compose.onNodeWithText("Pick me").assertDoesNotExist()
        compose.onNodeWithText("Second menu row").assertExists()
    }

    @Test
    fun whenTheTriggerLeavesCompositionTheMenuCloses() {
        var showTrigger by mutableStateOf(true)
        compose.setContent {
            Harness {
                if (showTrigger) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("trigger")
                            .glassMenu(menu = { plainMenu() }),
                    ) {}
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("trigger").performClick()
        advance(600)
        compose.onNodeWithTag("glass-menu").assertExists()

        showTrigger = false
        frame() // the trigger leaves; its onDispose starts the close
        advance(300)
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    // ------------------------------------------------------- deferred

    @Test
    fun aSuspendingLoadShowsOneLoadingRowThenTheRows() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuElement.Deferred(cached = false) {
                    delay(1500)
                    listOf(MenuItem("Loaded row") {})
                },
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        compose.waitForIdle()

        compose.onNodeWithTag("glass-menu-loading").assertExists()
        compose.onNodeWithText("Loaded row").assertDoesNotExist()

        advance(1600)
        compose.onNodeWithTag("glass-menu-loading").assertDoesNotExist()
        compose.onNodeWithText("Loaded row").assertExists()
    }

    @Test
    fun aDeferredInsideAnInlineSectionShowsHeaderAndLoadingThenHeaderAndRows() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuItem("New worktree", checked = true) {},
                MenuSection(
                    title = "Branch",
                    inline = true,
                    children = listOf(
                        MenuElement.Deferred(cached = false) {
                            delay(800)
                            listOf(MenuItem("main") {}, MenuItem("feature/glass-menu") {})
                        },
                    ),
                ),
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        compose.waitForIdle()

        compose.onNodeWithText("Branch").assertExists()
        compose.onNodeWithTag("glass-menu-loading").assertExists()
        compose.onNodeWithText("main").assertDoesNotExist()
        // Measure at rest: settle the open spring's 0.9 scale first (the
        // 800 ms load is still pending after it).
        advance(400)
        compose.onNodeWithTag("glass-menu-loading").assertExists()
        // SemanticsNode.size / positionInRoot read the live layout node (the
        // same panel node survives the load), so snapshot the values now —
        // keeping the node and reading it after the load compared the
        // grown panel with itself (CI r23).
        val panelBefore = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        val topBefore = panelBefore.positionInRoot.y
        val heightBefore = panelBefore.size.height

        advance(900)
        // Let the panel's resize spring (the 0.35 s open spring) settle.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Branch").assertExists()
        compose.onNodeWithText("main").assertExists()
        compose.onNodeWithTag("glass-menu-loading").assertDoesNotExist()

        // The panel resized in place: the anchored top corner stayed put.
        val panelAfter = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        assertEquals(
            "the anchored corner stays fixed while the panel resizes",
            topBefore,
            panelAfter.positionInRoot.y,
            1f,
        )
        assertTrue("the panel grew to fit the branches", panelAfter.size.height > heightBefore)
    }

    @Test
    fun aNonSuspendingLoadNeverShowsTheLoadingRow() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuElement.Deferred(cached = false) { listOf(MenuItem("Instant row") {}) },
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        compose.waitForIdle()
        // No frame ever composed the placeholder: the load finished before
        // the first frame (UNDISPATCHED).
        assertTrue(compose.onAllNodesWithTag("glass-menu-loading").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Instant row").assertExists()

        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("glass-menu-loading").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aCachedHoistedDeferredLoadsOnceAcrossThreeOpens() {
        val ref = StateRef()
        var loads = 0
        val deferred = MenuElement.Deferred(cached = true) {
            loads++
            listOf(MenuItem("Cached row") {})
        }
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        repeat(3) {
            assertTrue(openMenu(ref.state!!, Menu(children = listOf(deferred))))
            settleOpen()
            compose.onNodeWithText("Cached row").assertExists()
            // close via the scrim: an outside tap
            compose.onNodeWithTag("under").performClick()
            finishClose()
            compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        }
        assertEquals(1, loads)
    }

    @Test
    fun anUncachedDeferredLoadsOnEveryOpen() {
        val ref = StateRef()
        var loads = 0
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        repeat(3) {
            val menu = Menu(
                children = listOf(
                    MenuElement.Deferred(cached = false) {
                        loads++
                        listOf(MenuItem("Uncached row") {})
                    },
                ),
            )
            assertTrue(openMenu(ref.state!!, menu))
            settleOpen()
            compose.onNodeWithText("Uncached row").assertExists()
            compose.onNodeWithTag("under").performClick()
            finishClose()
            compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        }
        assertEquals(3, loads)
    }

    @Test
    fun aThrowingLoadResolvesToNothingAndTheMenuClosesWithoutCrashing() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuSection(
                    title = "Branch",
                    inline = true,
                    children = listOf(
                        MenuElement.Deferred(cached = false) { error("boom") },
                    ),
                ),
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))

        // The load throws synchronously: no rows, no "Branch" header, the
        // empty root closes the menu.
        advance(400)
        compose.onNodeWithText("Branch").assertDoesNotExist()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertFalse(ref.state!!.isOpen)
    }

    // ------------------------------------------------------- geometry

    @Test
    fun thePanelIs250WideWithA42DpRowPitchAnd61DpTextStart() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuItem("Edit") {},
                MenuItem("Move Up") {},
                MenuItem("Move Down") {},
                MenuItem("Remove") {},
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        settleOpen()

        val panel = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        assertEquals("panel width 250 dp for short content", dpPx(250f), panel.size.width.toFloat(), 1f)
        assertEquals("panel opens below the anchor", anchor.top, panel.positionInRoot.y, 1f)
        assertEquals(
            "the panel's right edge aligns to the anchor's",
            anchor.right,
            panel.positionInRoot.x + panel.size.width,
            1f,
        )

        val rows = compose.onAllNodesWithTag("glass-menu-item").fetchSemanticsNodes()
        assertEquals(4, rows.size)
        val pitch = rows[1].positionInRoot.y - rows[0].positionInRoot.y
        assertEquals("row pitch 42 dp", dpPx(42f), pitch, dpPx(0.5f))
        val rowHeight = rows[0].size.height.toFloat()
        assertEquals("row height 42 dp", dpPx(42f), rowHeight, 1f)

        // The row's clickable merges its descendants, so the merged tree's
        // "Edit" node is the whole row (x = the panel's, CI r23: 0 px):
        // read the title text's own node in the unmerged tree.
        val edit = compose.onNodeWithText("Edit", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("no icons and no checks: text starts at 20 dp", dpPx(20f), edit.positionInRoot.x - panel.positionInRoot.x, 2f)
    }

    @Test
    fun iconedRowsStartTheirTextAt61Dp() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuItem("Edit", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) {},
                MenuItem("Move Up", icon = MenuIcon.Symbol(RobocoIcons.ArrowUp)) {},
                MenuItem(
                    "Custom",
                    // A tagged box as a stand-in glyph: measures the slot itself.
                    icon = MenuIcon.Custom { _ ->
                        Box(Modifier.size(20.dp).testTag("custom-icon"))
                    },
                ) {},
            ),
        )
        compose.setContent { Harness(stateRef = ref) }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        settleOpen()

        val panel = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        // The row's clickable merges its descendants, so the merged tree's
        // "Edit" node is the whole row (x = the panel's, CI r23: 0 px):
        // read the title text's own node in the unmerged tree.
        val edit = compose.onNodeWithText("Edit", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "the icon slot reserves the 61 dp text column",
            dpPx(61f),
            edit.positionInRoot.x - panel.positionInRoot.x,
            2f,
        )
        val icon = compose.onNodeWithTag("custom-icon", useUnmergedTree = true).fetchSemanticsNode()
        val iconCentre = icon.positionInRoot.x + icon.size.width / 2f
        assertEquals(
            "the leading slot centre sits 36 dp from the panel's leading edge",
            dpPx(36f),
            iconCentre - panel.positionInRoot.x,
            2f,
        )
    }

    @Test
    fun atFontScale13RowsGrowButTheTextColumnStaysAt61Dp() {
        val ref = StateRef()
        val menu = Menu(
            children = listOf(
                MenuItem("Edit", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) {},
                MenuItem("Move Up", icon = MenuIcon.Symbol(RobocoIcons.ArrowUp)) {},
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 3f, fontScale = 1.3f)) {
                Harness(stateRef = ref)
            }
        }
        compose.waitForIdle()
        assertTrue(openMenu(ref.state!!, menu))
        settleOpen()

        val rows = compose.onAllNodesWithTag("glass-menu-item").fetchSemanticsNodes()
        assertTrue(
            "rows grow past 42 dp at font scale 1.3 (was ${rows[0].size.height}px)",
            rows[0].size.height > dpPx(42f),
        )
        val panel = compose.onNodeWithTag("glass-menu").fetchSemanticsNode()
        // The row's clickable merges its descendants, so the merged tree's
        // "Edit" node is the whole row (x = the panel's, CI r23: 0 px):
        // read the title text's own node in the unmerged tree.
        val edit = compose.onNodeWithText("Edit", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "the text column does not move",
            dpPx(61f),
            edit.positionInRoot.x - panel.positionInRoot.x,
            2f,
        )
    }
}
