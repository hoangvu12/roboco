package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.session.FakeSessionCore
import sh.roboco.android.session.QuestionsChromeFiller
import sh.roboco.android.session.SessionController
import sh.roboco.android.session.SessionEventHub
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.composerState
import uniffi.roboco_core.InputRequest
import uniffi.roboco_core.UserInputQuestion

/**
 * [SessionQuestionsSlot] — android-ui-parity #91's acceptance criteria
 * (Robolectric + compose-ui-test at 420 dpi, like ticket 90's card
 * tests; glass forced to its fallback tier so the pixel probes read
 * exact composites):
 *
 *  - the 200 ms page cross-fade: after Next both pages are in the tree
 *    at +100 ms and each is partially transparent (the ink of the
 *    sampled region sits strictly between the full ink and the settled
 *    ink), and only page 2 remains at +200 ms plus a frame;
 *  - a reset (F1 → F2) shows the new first page in the next frame with
 *    **no** fade, and snaps the container height to the new page's
 *    height in that same frame (no size spring);
 *  - a tap on the outgoing page during the fade is ignored;
 *  - focus: composing the slot never clears the composer's focus; the
 *    panel's own Other field loses focus when the questions go away
 *    (`configure(null)`), so the IME goes down while the slot collapses;
 *  - IME Done on the Other field answers page 1, moves to page 2, and
 *    page 2's Other field takes the focus (the keyboard stays up);
 *  - [rememberQuestionPanelState] configures from the controller's
 *    chrome on the first composition and tracks later emissions.
 *
 * State writes happen through `runOnUiThread` +
 * `Snapshot.sendApplyNotifications()` on the frozen clock (the
 * ActionSheetHost convention); `autoAdvanceMs` is Long.MAX_VALUE in
 * every fixture so the 220 ms timer never interferes (its timing is
 * [QuestionPanelStateTest]'s).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w388dp-h640dp-420dpi")
class SessionQuestionsSlotTest {

    @get:Rule
    val compose = createComposeRule()

    private val submits = mutableListOf<Pair<String, List<QuestionAnswer>>>()
    private lateinit var state: QuestionPanelState

    @Before
    fun forceFallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        compose.mainClock.autoAdvance = true
    }

    // ------------------------------------------------------------ the harness

    /**
     * Mounts the slot over the page background (90's test convention —
     * no haze stage here; the goldens own the drawn material) plus
     * [extra] (the composer stand-in of the focus tests).
     */
    private fun mount(
        fixture: SessionChrome.Questions? = QuestionPanelFixtures.F2,
        extra: @Composable () -> Unit = {},
    ) {
        state = QuestionPanelState(
            scope = CoroutineScope(Dispatchers.Main.immediate),
            feedback = NoFeedback,
            onSubmit = { requestId, answers -> submits += requestId to answers },
            autoAdvanceMs = Long.MAX_VALUE,
        )
        fixture?.let { state.configure(it) }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    with(PreviewSlotScope) { SessionQuestionsSlot(state) }
                    extra()
                }
            }
        }
        compose.waitForIdle()
    }

    /** The composer stand-in: a plain field the slot must never defocus. */
    private val composerStandIn: @Composable () -> Unit = {
        BasicTextField(
            value = "",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().height(30.dp).testTag("composer-stand-in"),
            textStyle = TextStyle(color = Palette.Light.text),
        )
    }

    private fun pageStateDescription(): String? =
        compose.onNodeWithTag(QuestionPanelTags.PAGE).fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    /**
     * The darkest (least luminant) pixel of the root image inside
     * [bounds] — the text ink of the question region. Mid-fade the ink
     * is a half-alpha composite of two pages, so it is strictly lighter
     * than both the full ink before the fade and the settled ink after.
     */
    private fun darkestInk(bounds: DpRect): Float {
        val px = compose.onRoot().captureToImage().toPixelMap()
        val rect = with(compose.density) { bounds.toRect() }
        var darkest = 1f
        val x0 = max(0, rect.left.toInt())
        val x1 = min(px.width - 1, rect.right.toInt())
        val y0 = max(0, rect.top.toInt())
        val y1 = min(px.height - 1, rect.bottom.toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c: Color = px[x, y]
                val l = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                darkest = min(darkest, l)
            }
        }
        return darkest
    }

    // ------------------------------------------------------------ the first page

    @Test
    fun theFirstPageShowsTheCardAndItsStateDescription() {
        mount(QuestionPanelFixtures.F2)
        compose.onNodeWithTag(QuestionCardTags.CARD).assertExists()
        compose.onNodeWithTag(QuestionCardTags.option(0)).assertExists()
        compose.onNodeWithText("Which sync strategy should the rewrite use?").assertExists()
        assertEquals("page 1 of 2", pageStateDescription())
        // Page 0: no Back, Next disabled (unanswered).
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithTag(QuestionCardTags.NEXT).assertIsNotEnabled()
    }

    // ------------------------------------------------------------ the fade

    @Test
    fun afterNextBothPagesFadeAndOnlyTheSecondRemains() {
        mount(QuestionPanelFixtures.F2)
        state.pick(0, "Event-driven fold with coalesced commits")
        compose.waitForIdle()
        compose.onNodeWithTag(QuestionCardTags.NEXT).assertIsEnabled()

        val inkRegion = compose.onNodeWithText("Which sync strategy should the rewrite use?").getBoundsInRoot()
        val before = darkestInk(inkRegion)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state.next() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // frame 0: both pages composed
        compose.mainClock.advanceTimeBy(100) // +100 ms of the 200 ms fade

        assertEquals(
            "both pages are in the tree at +100 ms",
            2,
            compose.onAllNodesWithTag(QuestionCardTags.option(0)).fetchSemanticsNodes().size,
        )
        val mid = darkestInk(inkRegion)

        compose.mainClock.advanceTimeBy(150)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            "only page 2 remains at +200 ms (plus a frame)",
            1,
            compose.onAllNodesWithTag(QuestionCardTags.option(0)).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Which suites should gate the merge?").assertExists()
        assertEquals("page 2 of 2", pageStateDescription())
        val settled = darkestInk(inkRegion)

        assertTrue("the mid-fade ink is lighter than the full ink ($before vs $mid)", mid - before > 0.08f)
        assertTrue("the settled ink is full again ($mid vs $settled)", mid - settled > 0.08f)
    }

    @Test
    fun aResetShowsTheNewFirstPageInTheNextFrameWithNoFade() {
        mount(QuestionPanelFixtures.F1)
        compose.onNodeWithText("Should the fix cover Android too?").assertExists()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.configure(QuestionPanelFixtures.F2) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Which sync strategy should the rewrite use?").assertExists()
        // A fade would keep F1's question in the tree until +200 ms.
        compose.onNodeWithText("Should the fix cover Android too?").assertDoesNotExist()
        assertEquals(
            "one page after the reset, not two",
            1,
            compose.onAllNodesWithTag(QuestionCardTags.option(0)).fetchSemanticsNodes().size,
        )
        assertEquals("page 1 of 2", pageStateDescription())
    }

    @Test
    fun aResetSnapsThePageHeightInTheSameFrame() {
        mount(QuestionPanelFixtures.F2)
        val tall = compose.onNodeWithTag(QuestionPanelTags.PAGE).getBoundsInRoot().height.value
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.configure(QuestionPanelFixtures.F3) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        val short = compose.onNodeWithTag(QuestionPanelTags.PAGE).getBoundsInRoot().height.value
        // F2's page 1 is 384.4 dp and F3's 294.5 dp at this width (≈ 90 dp
        // apart: one option row fewer, one wrapped line fewer), so the old
        // "> 100 dp" margin failed even with the snap working. Pin the snap
        // itself: the first frame already holds the settled F3 height.
        compose.mainClock.advanceTimeBy(1_000)
        val settled = compose.onNodeWithTag(QuestionPanelTags.PAGE).getBoundsInRoot().height.value
        assertTrue(
            "F3's page 1 is clearly shorter than F2's (tall=$tall, settled=$settled)",
            tall - settled > 50f,
        )
        assertEquals(
            "the height snapped to F3's page 1 in the first frame (tall=$tall, short=$short)",
            settled,
            short,
            0.5f,
        )
    }

    @Test
    fun tappingTheOutgoingPageDuringTheFadeChangesNothing() {
        mount(QuestionPanelFixtures.F2)
        state.pick(0, "Event-driven fold with coalesced commits")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.next() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100) // mid-fade, both pages live

        // Page 1's row 0 — a tap during the fade must not touch page 2's
        // state (nor replace page 1's own pick). Fired as the outgoing
        // row's own click action: a touch at its centre would hit page 2's
        // option 0 drawn on top at the same spot (top-aligned pages).
        compose.onNodeWithText("Poll the doc host every 120ms").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()

        assertEquals(
            "the outgoing page's tap is ignored",
            setOf("Event-driven fold with coalesced commits"),
            state.picked("q-sync"),
        )
        assertEquals("the new page's picks are untouched", emptySet<String>(), state.picked("q-gates"))
        assertEquals("the tap moved nothing", 1, state.page)
        assertTrue("no submit leaked", submits.isEmpty())
    }

    // ------------------------------------------------------------ focus

    @Test
    fun theComposerKeepsItsFocusWhileQuestionsComeAndGo() {
        mount(null, composerStandIn)
        compose.onNodeWithTag("composer-stand-in").performClick()
        compose.onNodeWithTag("composer-stand-in").assertIsFocused()

        compose.runOnUiThread { state.configure(QuestionPanelFixtures.F2) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-stand-in").assertIsFocused()

        // The questions resolve: the panel must not steal the composer's
        // focus (the slot clears only focus it owns).
        compose.runOnUiThread { state.configure(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-stand-in").assertIsFocused()
    }

    @Test
    fun configureNullClearsTheOtherFieldsFocus() {
        mount(QuestionPanelFixtures.F2)
        compose.onNodeWithTag(QuestionCardTags.OTHER).performClick()
        compose.onNodeWithTag(QuestionCardTags.OTHER).assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.configure(null) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        // The items are kept (74's slot stays composed while collapsing),
        // but the field lost focus so the IME goes down.
        compose.onNodeWithTag(QuestionCardTags.OTHER).assertIsNotFocused()
    }

    @Test
    fun typingAndImeDoneOnPage1AnswersAndMovesToPage2KeepingTheKeyboardUp() {
        mount(QuestionPanelFixtures.F2)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(QuestionCardTags.OTHER).performTextInput("own")
        compose.onNodeWithTag(QuestionCardTags.OTHER).performImeAction()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(250) // the fade settles

        compose.onNodeWithText("Which suites should gate the merge?").assertExists()
        assertEquals("page 2 of 2", pageStateDescription())
        compose.onAllNodesWithTag(QuestionCardTags.OTHER).assertCountEquals(1)
        // iOS: the same UITextField stays first responder — page 2's Other
        // field takes the focus the outgoing field kept.
        compose.onNodeWithTag(QuestionCardTags.OTHER).assertIsFocused()
        assertEquals("own", state.customText("q-sync"))
    }

    // ------------------------------------------------------------ rememberQuestionPanelState

    @Test
    fun theFirstCompositionConfiguresFromTheControllerChromeAndTracksIt() {
        val core = FakeSessionCore()
        core.composerResult = composerState(chatId = "chat-a").copy(
            openInput = InputRequest(
                entryId = "e1",
                requestId = "r2",
                questions = listOf(
                    UserInputQuestion(
                        id = "q-sync",
                        header = "Question",
                        question = "Which sync strategy should the rewrite use?",
                        options = listOf("A", "B", "C"),
                        multiSelect = false,
                        prefill = null,
                        multiline = false,
                    ),
                    UserInputQuestion(
                        id = "q-gates",
                        header = "Question",
                        question = "Which suites should gate the merge?",
                        options = listOf("Unit tests"),
                        multiSelect = true,
                        prefill = null,
                        multiline = false,
                    ),
                ),
            ),
        )
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            fillers = listOf(QuestionsChromeFiller()),
            io = Dispatchers.Unconfined,
            isMainThread = { false },
        )
        assertEquals("r2", controller.chrome.value.questions?.requestId)

        var captured: QuestionPanelState? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                captured = rememberQuestionPanelState(
                    controller,
                    QuestionActions({ _, _ -> }, CoroutineScope(Dispatchers.Unconfined)),
                )
            }
        }
        compose.waitForIdle()
        val panel = captured ?: error("no state")
        assertEquals(2, panel.count)
        assertEquals("r2", panel.requestId)
        assertEquals("Which sync strategy should the rewrite use?", panel.current?.text)
        assertTrue("no picks on the first frame", panel.picked("q-sync").isEmpty())

        // A later chrome emission re-configures through the collector.
        core.composerResult = core.composerResult.copy(openInput = null)
        controller.invalidate()
        compose.waitUntil(timeoutMillis = 5_000) { panel.requestId == null }
        assertEquals("the items survive the resolve", 2, panel.count)
    }
}
