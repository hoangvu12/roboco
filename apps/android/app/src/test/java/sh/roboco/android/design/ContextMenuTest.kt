package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
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
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem

/**
 * The context menu's behaviour (android-ui-parity #16) through a real
 * [ContextMenuHost] and `Modifier.contextMenu` — Robolectric +
 * compose-ui-test, the ticket's device (`w393dp-h852dp-xxhdpi`, 1 dp =
 * 3 px, no insets, so the safe area is the full 1179 × 2556 window inset
 * 8 dp). `Glass.forceFallback` is on for the whole class (the golden
 * convention).
 *
 * The clock starts frozen (the ticket), but the injected long presses need
 * virtual time to pass for the 400 ms timeout to elapse — the
 * `GlassMenuBehaviourTest` precedent — so those tests flip
 * `autoAdvance = true` for the gesture and settle animations explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ContextMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val feedback = RecordingFeedback()

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

        override fun cue(cue: Cue) = Unit
    }

    /** Test-side handle on the row's hoisted lift state. */
    private class LiftRef {
        var state: ContextMenuLiftState? = null
    }

    private val logs = mutableListOf<String>()

    /**
     * The host under test: a session row near the top (its menu opens
     * below it) and a "No menu" row beneath. `rowPresent` removes the
     * session row (the "Remove row" item) while the overlay is open.
     */
    @Composable
    private fun Harness(
        liftRef: LiftRef? = null,
        rowPresent: MutableState<Boolean>? = null,
    ) {
        RobocoTheme(Appearance.Light) {
            CompositionLocalProvider(LocalFeedback provides feedback) {
                ContextMenuHost {
                    Column(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Spacer(Modifier.height(48.dp))
                        if (rowPresent?.value != false) {
                            val liftState = rememberContextMenuLiftState()
                            liftRef?.state = liftState
                            HarnessRow(
                                tag = "ctx-row-1",
                                text = "Session 1",
                                liftState = liftState,
                                menu = {
                                    Menu(
                                        children = listOf(
                                            MenuItem("Pin", icon = MenuIcon.Symbol(RobocoIcons.Pin)) { logs += "Pin" },
                                            MenuItem("Rename…", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) { logs += "Rename…" },
                                            MenuItem(
                                                "Archive",
                                                icon = MenuIcon.Symbol(RobocoIcons.Archivebox),
                                                destructive = true,
                                            ) { logs += "Archive" },
                                            MenuItem(
                                                "Remove row",
                                                icon = MenuIcon.Symbol(RobocoIcons.Trash),
                                                destructive = true,
                                            ) { rowPresent?.value = false },
                                        ),
                                    )
                                },
                                onTap = { logs += "tapped Session 1" },
                            )
                        }
                        HarnessRow(
                            tag = "ctx-no-menu",
                            text = "No menu",
                            menu = { null },
                            onTap = { logs += "tapped No menu" },
                        )
                    }
                }
            }
        }
    }

    /** One 62 dp session-like row: the modifier under test, before `clickable`. */
    @Composable
    private fun HarnessRow(
        tag: String,
        text: String,
        menu: () -> Menu?,
        onTap: () -> Unit,
        liftState: ContextMenuLiftState? = null,
    ) {
        val typeScale = LocalTypeScale.current
        Box(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .testTag(tag)
                .contextMenu(LiftHighlight.SessionRow, liftState = liftState, menu = menu)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onTap,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                text,
                Modifier.padding(start = 20.dp),
                style = uiStyle(Face.SansMedium, typeScale.fixed(16.5f), RobocoTheme.palette.text),
            )
        }
    }

    /** Long-presses [tag] with the clock auto-advancing (the gesture needs time). */
    private fun longPress(tag: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Settles any running lift / menu animation past its duration. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1000) // past the 0.38 s lift and 15's 350 ms open / 150 ms close
        compose.waitForIdle()
    }

    private val hasShowMenuAction = SemanticsMatcher("'Show menu' long-click action") {
        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Show menu"
    }

    private val hasDismissDescription = SemanticsMatcher("'Dismiss context menu' description") {
        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Dismiss context menu")
    }

    private val hasLiftKey = SemanticsMatcher("has ContextLift") {
        it.config.contains(ContextLiftKey)
    }

    /** The overlay's live lift progress, via the test-only key. */
    private fun liftValue(): Float {
        val nodes = compose.onAllNodes(hasLiftKey).fetchSemanticsNodes()
        assertTrue("no ContextLiftKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[ContextLiftKey]
    }

    // ------------------------------------------------------------ open

    @Test
    fun aLongPressFiresOneHapticAndShowsOverlayPreviewBackdropAndMenu() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-row-1")
        assertEquals(listOf(Haptic.LongPress), feedback.haptics)
        compose.onNodeWithTag("context-menu-overlay").assertExists()
        compose.onNodeWithTag("context-menu-backdrop").assertExists()
        compose.onNodeWithTag("context-menu-preview").assertExists()
        compose.onNodeWithTag("glass-menu").assertExists()
    }

    @Test
    fun aLongPressOnTheNoMenuRowFiresNoHapticAndNothingOpens() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-no-menu")
        assertTrue("no haptic when the menu is null", feedback.haptics.isEmpty())
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    @Test
    fun theRowExposesTheShowMenuLongClickActionAndTheBackdropItsDismissLabel() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        compose.onNodeWithTag("ctx-row-1").assert(hasShowMenuAction)
        compose.onNodeWithTag("ctx-no-menu").assert(hasShowMenuAction)
        longPress("ctx-row-1")
        compose.onNodeWithTag("context-menu-backdrop").assert(hasDismissDescription)
    }

    @Test
    fun theAccessibilityLongClickActionOpensTheMenu() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        compose.onNodeWithTag("ctx-row-1").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        // The frozen clock: the open's state write composes the overlay on
        // the next frame only (waitForIdle never pumps one here).
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(Haptic.LongPress), feedback.haptics)
        compose.onNodeWithTag("context-menu-overlay").assertExists()
    }

    // ------------------------------------------------------------ dismissal

    @Test
    fun releasingAfterALongPressDoesNotClickTheRowWhileAShortTapStillDoes() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-row-1")
        settle()
        assertEquals("the swallowed release must not click the row", 0, logs.size)

        Espresso.pressBack() // close again
        compose.waitForIdle()
        settle()
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()

        compose.onNodeWithTag("ctx-no-menu").performClick()
        compose.waitForIdle()
        assertEquals(listOf("tapped No menu"), logs)
    }

    @Test
    fun backDismissesTheOverlayWithinOneSecondAndTheRowDrawsItselfAgain() {
        val liftRef = LiftRef()
        compose.setContent { Harness(liftRef = liftRef) }
        compose.waitForIdle()
        longPress("ctx-row-1")
        assertTrue("the row's own pressed fill is suppressed while lifted", liftRef.state!!.isLifted)
        settle()
        assertTrue("the lift is fully up while open", liftValue() > 0.99f)

        Espresso.pressBack()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // the menu's 150 ms fade + the 0.38 s lift reversal
        compose.waitForIdle()

        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        assertTrue(
            "the lift key left with the overlay (the lift settled at 0)",
            compose.onAllNodes(hasLiftKey).fetchSemanticsNodes().isEmpty(),
        )
        assertFalse("the lift settled at 0 and the row draws itself again", liftRef.state!!.isLifted)
        compose.onNodeWithTag("ctx-row-1").assertExists()

        // And it works again: a short tap now clicks.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("ctx-row-1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("tapped Session 1"), logs)
    }

    @Test
    fun aTapOnTheBackdropDismisses() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-row-1")
        settle()

        // The row is near the top with its menu below it; the bottom of the
        // window is bare backdrop.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("context-menu-backdrop").performTouchInput { click(Offset(60f, 2400f)) }
        compose.waitForIdle()
        settle()
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    @Test
    fun choosingAnItemDismissesAndRunsTheAction() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-row-1")
        settle()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Pin").performClick()
        compose.waitForIdle()
        settle()
        assertEquals(listOf("Pin"), logs)
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    @Test
    fun removingTheSourceRowWhileOpenDoesNotCrash() {
        val rowPresent = mutableStateOf(true)
        compose.setContent { Harness(rowPresent = rowPresent) }
        compose.waitForIdle()
        longPress("ctx-row-1")
        settle()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Remove row").performClick()
        compose.waitForIdle()
        settle()

        compose.onNodeWithTag("ctx-row-1").assertDoesNotExist()
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    @Test
    fun aSecondLongPressWhileOpenDoesNothingTheBackdropCatchesIt() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        longPress("ctx-row-1")
        settle()

        // The second gesture lands on the full-window backdrop, never on the
        // row underneath: no second haptic, no second lift. Its release
        // dismisses the open menu (a tap outside).
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("ctx-no-menu").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("one haptic only", listOf(Haptic.LongPress), feedback.haptics)
        settle()
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun movingPastTouchSlopBeforeTheTimeoutCancels() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        // A scroll starting: the finger travels 100 dp down, past touch slop
        // and out of the 62 dp row. The detector only observes (ticket 16),
        // so in the real list it is the scroll that claims the pointer; this
        // harness has no scroller, and Compose's `clickable` (1.12
        // ClickableNode) cancels a tap only when the pointer is consumed or
        // leaves the bounds — an in-bounds drag would still click here.
        compose.onNodeWithTag("ctx-row-1").performTouchInput {
            down(center)
            advanceEventTime(100)
            moveTo(center + Offset(0f, 300f))
            up()
        }
        compose.waitForIdle()
        assertTrue("a swipe before the timeout fires no haptic", feedback.haptics.isEmpty())
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        assertEquals("the swipe is not a click either", 0, logs.size)
    }

    @Test
    fun aSecondFingerCancels() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("ctx-row-1").performTouchInput {
            down(0, center)
            down(1, center + Offset(120f, 0f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue("a two-finger touch fires no haptic", feedback.haptics.isEmpty())
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }

    @Test
    fun aSlowDragPastTouchSlopCancels() {
        compose.setContent { Harness() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        // A slow drag in several steps: every step is well inside the
        // timeout, but the accumulated movement passes touch slop.
        compose.onNodeWithTag("ctx-row-1").performTouchInput {
            down(center)
            repeat(5) { step ->
                advanceEventTime(20)
                moveTo(center + Offset(20f * (step + 1), 0f))
            }
            up()
        }
        compose.waitForIdle()
        assertTrue(feedback.haptics.isEmpty())
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
    }
}
