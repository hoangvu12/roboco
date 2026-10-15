package sh.roboco.android.design

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face

/**
 * Ticket 18's host behaviour (Robolectric, `createAndroidComposeRule`): the
 * card geometry against the iOS 26 capture, the field focus with the caret
 * at the end (decision FOUNDATION-D2), the live `requiresText` enable, IME
 * Done, back and scrim taps, the handler-once rule, the present/dismiss
 * timeline through the test clock, the FIFO queue, the retry re-prompt,
 * `dismissAll` and off-main `show`. Written per the test policy; the batch
 * run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AlertHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val handlers = mutableListOf<String>()
    private val appTaps = mutableListOf<String>()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ fixtures

    /** The app under the alert: a tappable box, so blocked taps are visible. */
    @Composable
    private fun DemoContent() {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        Box(
            Modifier
                .fillMaxSize()
                .testTag("app-content")
                .clickable { appTaps += "app" },
        ) {
            BasicText("app", style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        }
    }

    private fun host(content: @Composable () -> Unit = { DemoContent() }): AlertHostState {
        val state = AlertHostState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                AlertHost(state = state) { content() }
            }
        }
        return state
    }

    private fun px(dp: Int): Float = with(compose.density) { dp.dp.toPx() }

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect = with(compose.density) { getBoundsInRoot().toRect() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).pxBounds()

    private val okAlert = RobocoAlert(
        title = "Couldn't start the session",
        message = "Choose a project or a host that can run it.",
        buttons = listOf(AlertButton("OK") { handlers += "ok" }),
    )

    private val notNowAlert = RobocoAlert(
        title = "Notifications are off",
        message = "Allow notifications for Roboco in Android settings.",
        buttons = listOf(
            AlertButton("Not Now", role = AlertButtonRole.Cancel) { handlers += "not-now" },
            AlertButton("Open Settings") { handlers += "settings" },
        ),
    )

    private val renameAlert = RobocoAlert(
        title = "Rename Session",
        field = AlertField(text = "Fix flaky test"),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel) { handlers += "cancel" },
            AlertButton("Rename") { text -> handlers += "rename:$text" },
        ),
    )

    private val newFolderAlert = RobocoAlert(
        title = "New Folder",
        message = "Create a folder in /Users/me/code on Studio.",
        field = AlertField(
            placeholder = "Folder name",
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel) { handlers += "cancel" },
            AlertButton("Create", requiresText = true) { text -> handlers += "create:$text" },
        ),
    )

    private fun retryAlert(name: String) = RobocoAlert(
        title = "New Folder",
        message = "Couldn't create the folder. Enter a folder name without path separators.",
        field = AlertField(
            text = name,
            placeholder = "Folder name",
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel) { handlers += "cancel" },
            AlertButton("Create", requiresText = true) { text -> handlers += "create:$text" },
        ),
    )

    private val twoDefaultsAlert = RobocoAlert(
        title = "Version conflict",
        buttons = listOf(
            AlertButton("Keep Both Versions") { handlers += "keep" },
            AlertButton("Replace Existing") { handlers += "replace" },
        ),
    )

    private val threeButtonsAlert = RobocoAlert(
        title = "Version conflict",
        message = "A session with this name already exists.",
        buttons = listOf(
            AlertButton("Keep Both Versions") { handlers += "keep" },
            AlertButton("Replace Existing") { handlers += "replace" },
            AlertButton("Cancel", role = AlertButtonRole.Cancel) { handlers += "cancel" },
        ),
    )

    // ------------------------------------------------------------ geometry

    @Test
    fun theCardMatchesTheCaptureGeometry() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(newFolderAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400) // present + settle (bounds are alpha-free)

        val card = bounds("alert")
        val field = bounds("alert-field")
        val cancel = bounds("alert-cancel")
        val primary = bounds("alert-primary")
        val window = compose.onRoot().pxBounds()

        // 320 dp wide, centred horizontally (|left gap − right gap| ≤ 1 px).
        assertEquals(px(320), card.width, 1f)
        assertEquals(card.left, window.right - card.right, 1f)

        // Field: 288 × 48 at card width 320, 16 dp from both card edges.
        assertEquals(px(288), field.width, 1f)
        assertEquals(px(48), field.height, 1f)
        assertEquals(px(16), field.left - card.left, 1f)
        assertEquals(px(16), card.right - field.right, 1f)

        // Title: leading, 30 dp from the card edge.
        val title = compose.onNodeWithText("New Folder").pxBounds()
        assertEquals(px(30), title.left - card.left, 1f)

        // Buttons: 48 dp capsules, 140 dp each, 8 dp gap, 20 dp below the
        // field, 16 dp from the card's sides and bottom. Cancel leads.
        assertEquals(px(48), cancel.height, 1f)
        assertEquals(px(140), cancel.width, 1f)
        assertEquals(px(48), primary.height, 1f)
        assertEquals(px(140), primary.width, 1f)
        assertEquals(px(8), primary.left - cancel.right, 1f)
        assertEquals(px(20), cancel.top - field.bottom, 1f)
        assertEquals(px(16), cancel.left - card.left, 1f)
        assertEquals(px(16), card.bottom - cancel.bottom, 1f)
        assertTrue("the Cancel button must lead", cancel.left < primary.left)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w340dp-h720dp-xxhdpi")
    fun aNarrowWindowShrinksTheCardTo308Dp() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(okAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400)
        assertEquals(px(308), bounds("alert").width, 1.5f)
    }

    @Test
    fun cancelLeadsEvenWhenListedSecond() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            state.show(
                RobocoAlert(
                    title = "Rename Session",
                    buttons = listOf(
                        AlertButton("Rename") { handlers += "rename" },
                        AlertButton("Cancel", role = AlertButtonRole.Cancel) { handlers += "cancel" },
                    ),
                ),
            )
        }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400)
        // The Cancel button (index 1) carries the cancel tag and leads.
        val cancel = bounds("alert-cancel")
        val primary = bounds("alert-primary")
        assertTrue("Cancel must lead when listed second", cancel.left < primary.left)
        assertEquals(px(140), cancel.width, 1f)
    }

    @Test
    fun threeButtonsStackFullWidthWithTheCancelLast() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(threeButtonsAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400)

        val card = bounds("alert")
        val keep = bounds("alert-button-0")
        // Buttons [Keep(0), Replace(1), Cancel(2)]: Replace is the primary.
        val replace = bounds("alert-primary")
        val cancel = bounds("alert-cancel")

        // Full-width capsules (288 at 320), stacked 8 dp apart, cancel last.
        assertEquals(px(288), keep.width, 1f)
        assertEquals(px(288), replace.width, 1f)
        assertEquals(px(288), cancel.width, 1f)
        assertEquals(px(8), replace.top - keep.bottom, 1f)
        assertEquals(px(8), cancel.top - replace.bottom, 1f)
        assertTrue("stacked order: keep, replace, cancel", keep.top < replace.top && replace.top < cancel.top)
        assertTrue("the button area is pinned at the card's bottom", cancel.bottom < card.bottom)
    }

    @Test
    fun longTitlesStackTwoButtonsFullWidth() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(twoDefaultsAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400)
        // "Keep Both Versions" and "Replace Existing" are wider than the 108 dp
        // half-capsule budget: stacked full width, 8 dp apart.
        val keep = bounds("alert-button-0")
        val replace = bounds("alert-primary")
        assertEquals(px(288), keep.width, 1f)
        assertEquals(px(288), replace.width, 1f)
        assertEquals(px(8), replace.top - keep.bottom, 1f)
    }

    // ------------------------------------------------------ field + focus

    @Test
    fun theFieldIsFocusedWithTheCaretAtTheEndAndTypingAppends() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(renameAlert) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        val field = compose.onNodeWithTag("alert-field")
        field.assertIsFocused()
        // Decision FOUNDATION-D2: the caret at the end of "Fix flaky test".
        assertEquals(TextRange(14), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

        field.performTextInput(" CI")
        compose.waitForIdle()
        // EditableText is an AnnotatedString: compare its text.
        assertEquals("Fix flaky test CI", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun requiresTextDisablesTheCreateButtonForEmptyAndWhitespace() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(newFolderAlert) }
        compose.waitForIdle()

        val create = compose.onNodeWithTag("alert-primary")
        val field = compose.onNodeWithTag("alert-field")
        create.assertIsNotEnabled() // ""
        field.performTextInput("   ")
        compose.waitForIdle()
        create.assertIsNotEnabled() // "   "
        field.performTextInput("a")
        compose.waitForIdle()
        create.assertIsEnabled() // "   a"
        field.performTextClearance()
        compose.waitForIdle()
        create.assertIsNotEnabled() // ""
    }

    /**
     * Round 44: the card's tap swallower consumed every move, so a real
     * finger's tap (a few ACTION_MOVEs between down and up) was cancelled by
     * the button's clickable, as in the ActionSheet bug. A finger-like tap
     * must fire the button's handler.
     */
    @Test
    fun aFingerTapWithSmallMovesFiresTheButton() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(notNowAlert) }
        compose.waitForIdle()

        compose.onNodeWithTag("alert-primary").performTouchInput {
            down(center)
            moveBy(Offset(0f, 2f))
            moveBy(Offset(3f, -1f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("settings"), handlers)
    }

    @Test
    fun tappingADisabledButtonDoesNothing() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(newFolderAlert) }
        compose.waitForIdle()

        compose.onNodeWithTag("alert-primary").performClick()
        compose.waitForIdle()
        assertTrue("a disabled Create must not run its handler", handlers.isEmpty())
        compose.onNodeWithTag("alert").assertExists()
    }

    @Test
    fun imeDoneFiresThePrimaryHandlerOnceWithTheRawText() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(renameAlert) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        val field = compose.onNodeWithTag("alert-field")
        field.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("rename:Fix flaky test"), handlers)

        // The alert is resolved and fading: it is gone after the fade, with
        // the handler having run exactly once.
        compose.onNodeWithTag("alert").assertDoesNotExist()
    }

    @Test
    fun imeDoneDoesNothingWhileThePrimaryIsDisabled() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(newFolderAlert) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        compose.onNodeWithTag("alert-field").performImeAction()
        compose.waitForIdle()
        assertTrue(handlers.isEmpty())
        compose.onNodeWithTag("alert").assertExists()
    }

    // ------------------------------------------------- back / scrim rules

    @Test
    fun backRunsTheCancelHandler() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(notNowAlert) }
        compose.waitForIdle()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("not-now"), handlers)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertTrue("the app must not see the back as a tap", appTaps.isEmpty())
    }

    @Test
    fun backOnASingleOkAlertRunsTheOkHandler() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(okAlert) }
        compose.waitForIdle()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("ok"), handlers)
        compose.onNodeWithTag("alert").assertDoesNotExist()
    }

    @Test
    fun aScrimTapRunsTheCancelHandlerAndNeverReachesTheApp() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(notNowAlert) }
        compose.waitForIdle()

        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()
        assertEquals(listOf("not-now"), handlers)
        assertTrue("a tap under the scrim must not reach the app", appTaps.isEmpty())
        compose.onNodeWithTag("alert").assertDoesNotExist()

        // With the alert gone the app is tappable again.
        compose.onNodeWithTag("app-content").performClick()
        compose.waitForIdle()
        assertEquals(listOf("app"), appTaps)
    }

    @Test
    fun aScrimTapIsIgnoredWhenThereIsNoCancelAction() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(twoDefaultsAlert) }
        compose.waitForIdle()

        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()
        assertTrue("no cancel action: the tap is ignored", handlers.isEmpty())
        compose.onNodeWithTag("alert").assertExists()

        // Back dismisses it without running any handler.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(handlers.isEmpty())
        compose.onNodeWithTag("alert").assertDoesNotExist()
    }

    // ------------------------------------------------- handler once + gone

    @Test
    fun aDoubleTapRunsTheHandlerOnceAndRemovesTheAlertAfter200Ms() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(notNowAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400) // present + settle

        compose.onNodeWithTag("alert-cancel").performClick()
        compose.mainClock.advanceTimeByFrame() // flush the gesture's frame
        compose.onNodeWithTag("alert-cancel").performClick() // the double tap
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a double tap must not run the handler twice", 1, handlers.size)

        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.advanceTimeByFrame() // +1 frame
        compose.onNodeWithTag("alert").assertDoesNotExist()
    }

    // ------------------------------------------------------ present/fade

    private fun hasAlertKey(key: SemanticsPropertyKey<Float>): SemanticsMatcher =
        SemanticsMatcher("has ${key.name}") { it.config.contains(key) }

    private fun alertValue(key: SemanticsPropertyKey<Float>): Float {
        val nodes = compose.onAllNodes(hasAlertKey(key)).fetchSemanticsNodes()
        assertTrue("no ${key.name} node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[key]
    }

    @Test
    fun thePresentStartsAtAlpha0Scale11AndSettles() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(okAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame() // the card composes, animation at frame 0

        assertEquals(0f, alertValue(AlertAlphaKey), 0.02f)
        assertEquals(1.1f, alertValue(AlertScaleKey), 0.001f)

        compose.mainClock.advanceTimeBy(300) // 250 ms fade + margin
        assertEquals(1f, alertValue(AlertAlphaKey), 0.001f)
        assertEquals("the 0.4 s spring must be settled", 1f, alertValue(AlertScaleKey), 0.01f)
    }

    @Test
    fun reducedMotionDropsTheScaleButKeepsTheFade() {
        ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(okAlert) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0f, alertValue(AlertAlphaKey), 0.02f)
        assertEquals("no scale under Reduce Motion", 1f, alertValue(AlertScaleKey), 0.0001f)

        compose.mainClock.advanceTimeBy(300)
        assertEquals("the cross-fade still runs", 1f, alertValue(AlertAlphaKey), 0.001f)
        assertEquals(1f, alertValue(AlertScaleKey), 0.0001f)
    }

    // ---------------------------------------------------------- the queue

    @Test
    fun twoShowsInOneFramePresentOnlyTheFirstThenTheSecondAfterTheFade() {
        compose.mainClock.autoAdvance = false
        val state = host()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            state.show(RobocoAlert("First", buttons = listOf(AlertButton("Dismiss First") { handlers += "first" })))
            state.show(RobocoAlert("Second", buttons = listOf(AlertButton("Dismiss Second") { handlers += "second" })))
        }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400)

        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithText("Second").assertDoesNotExist()
        assertEquals("the second alert waits in the queue", 1, state.queue.size)

        compose.onNodeWithText("Dismiss First").performClick()
        // The fade-out starts on the frame after the click; 200 ms rounds up
        // to 13 frames (208 ms), so it ends on post-click frame 14, where
        // `removed()` swaps `current` to the queued alert. That write
        // recomposes on the following frame (15).
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Second").assertExists()
        compose.onNodeWithText("First").assertDoesNotExist()
        assertEquals(0, state.queue.size)
    }

    @Test
    fun aHandlerThatShowsAgainPresentsAfterTheFadeWithTheNamePreFilled() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread {
            state.show(
                RobocoAlert(
                    title = "New Folder",
                    message = "Create a folder in /Users/me/code on Studio.",
                    field = AlertField(
                        placeholder = "Folder name",
                        capitalization = KeyboardCapitalization.None,
                        autocorrect = false,
                    ),
                    buttons = listOf(
                        AlertButton("Cancel", role = AlertButtonRole.Cancel),
                        AlertButton("Create", requiresText = true) { text -> state.show(retryAlert(text)) },
                    ),
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("alert-field").performTextInput("../outside")
        compose.waitForIdle()
        compose.onNodeWithTag("alert-primary").performClick()
        compose.waitForIdle() // the auto clock runs the fade and the re-present

        compose
            .onNodeWithText("Couldn't create the folder. Enter a folder name without path separators.")
            .assertExists()
        val field = compose.onNodeWithTag("alert-field")
        assertEquals("the typed name survives the retry", "../outside", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(10), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test
    fun dismissAllClearsTheQueueAndFadesWithoutHandlers() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread {
            state.show(notNowAlert)
            state.show(okAlert)
        }
        compose.waitForIdle()
        assertEquals(1, state.queue.size)

        compose.runOnUiThread { state.dismissAll() }
        compose.waitForIdle()
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertTrue("dismissAll runs no handler", handlers.isEmpty())
        assertEquals(0, state.queue.size)
    }

    @Test
    fun showFromABackgroundThreadPresentsWithoutSnapshotExceptions() {
        val state = host()
        compose.waitForIdle()
        val thread = Thread { state.show(okAlert) }
        thread.start()
        thread.join()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        compose.onNodeWithTag("alert").assertExists()
    }

    // ------------------------------------------------------- accessibility

    @Test
    fun theCardIsAPaneAndTheContentIsHiddenWhileTheAlertIsUp() {
        val state = host()
        compose.waitForIdle()
        compose.runOnUiThread { state.show(okAlert) }
        compose.waitForIdle()

        val card = compose.onNodeWithTag("alert").fetchSemanticsNode()
        assertEquals("Couldn't start the session", card.config[SemanticsProperties.PaneTitle])
        assertTrue(card.config.contains(SemanticsProperties.IsTraversalGroup))

        // The wrapped content is hidden from accessibility while the alert
        // is up: the host's content box carries the hide property.
        val hidden = compose
            .onAllNodes(SemanticsMatcher("has HideFromAccessibility") { it.config.contains(SemanticsProperties.HideFromAccessibility) })
            .fetchSemanticsNodes()
        assertTrue("the wrapped content must be hidden from a11y while the alert is up", hidden.isNotEmpty())
    }
}
