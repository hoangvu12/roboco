package sh.roboco.android.flows

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.RobocoApplication

/**
 * The flow suite's SessionFlowTests port (android-app#30 §6): one Android
 * test per iOS test of `uit:SessionFlowTests.swift` with the same name
 * minus `test`, run against the **fixture engines** ([FixtureRule]) — a
 * real `roboco-engine` on the mock harness, paired exactly as a user
 * pairs — instead of upstream's in-app `-demo` host (D4: no demo
 * workspace in Roboco).
 *
 * Rows whose owning tickets have not landed yet stay in this class
 * `@Ignore`d with the ticket named, so the port table is visible in one
 * place and CI runs everything that can pass (the pre-rig
 * `KeyboardFlowTest` set the pattern). Unwired rows:
 * `testTappingLinkOpensIt` (ui-parity #126's link hit-test + Custom Tab
 * routing), `testJumpToLatestAfterScrollingUp` /
 * `testScrollingUpWhileStreamingStaysPut` (the `jump-to-latest` tag),
 * `testNewSessionFromAccessory`, `testNewSessionRemembersDraftWhenClosed`,
 * `testNewSessionHeadlineStaysAboveComposer`,
 * `testEffortPickerInNewSession`, `testModelChipNamesAModelAtOnce`,
 * `testNewProjectFolderBrowser` and
 * `testProjectPickerGroupsClonesAndHostPicksCheckout` (the New Session
 * sheet is still `NewSessionSheetPlaceholder`, ui-parity #92/#113).
 *
 * Route/engine per test (fixture §5): the `questionPanel*` test runs on
 * the `question` engine (`chat-ask`), the queue/scroll/cancelled-swipe
 * rows on the `slow` engine (`chat-long`), everything else on `main`
 * (`chat-deploy` / `chat-veil` / the front page).
 */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {

    /** The unknown-route ids, picked by method name (see [routeFor]). */
    private var routeChatId: String? = null

    private val launch = FixtureRule(
        routeFor = { name -> routeFor(name) },
        enginesFor = { name -> enginesFor(name) },
    )

    @Suppress("DEPRECATION") // the public AndroidComposeTestRule constructor is the one way to hand it a custom rule
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val app: RobocoApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as RobocoApplication

    @Before
    fun requirePairedFixtureEngine() {
        // The `pair` extra's redeem is async (it races this @Before by
        // design — the first-frame hold usually settles it first). Poll
        // briefly before running: a skip here means the rig broke, not
        // that a slow load was mistaken for its absence.
        val deadline = System.currentTimeMillis() + 10_000
        var engines = app.clientOrNull()?.engines().orEmpty()
        while (engines.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            engines = app.clientOrNull()?.engines().orEmpty()
        }
        assertTrue(
            "the rig's redeemed engine never appeared (android_fixture /mint + the pair extra)",
            engines.isNotEmpty(),
        )
    }

    // ── the front page ──────────────────────────────────────────────────

    /** `testFrontPageShowsFoldersAndRecents` (:24): sections fold in place and a long-press opens the folder. */
    @Test
    fun frontPageShowsFoldersAndRecents() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("section-pinned").fetchSemanticsNodes().isNotEmpty()
        }

        // Sections fold in place, like the desktop sidebar: the pinned row
        // leaves the tree when the header folds and returns on unfold.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("session-chat-veil").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("section-pinned").performClick()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("session-chat-veil").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("section-pinned").performClick()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("session-chat-veil").fetchSemanticsNodes().isNotEmpty()
        }

        // Long-press → Open drills into the folder (reorder lives there).
        compose.onNodeWithTag("section-pinned").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Open")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Pinned").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("front-page-folder.png")
    }

    /** `testTabsAndSearch` (:235): the Settings tab round-trip. */
    @Test
    fun tabsAndSearch() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Settings")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("settings-tab.png")
        compose.onAllNodesWithContentDescription("Sessions")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * `testSearchTabRoundTrip` (:143): Search → back to Sessions /
     * Settings — the accessory must be back and usable after the close.
     */
    @Test
    fun searchTabRoundTrip() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        for (back in listOf("Sessions", "Settings")) {
            compose.onAllNodesWithContentDescription(back)[0].performClick()
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("Search")[0].performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("search-field").fetchSemanticsNodes().isNotEmpty()
            }
            // Close beside the field leaves search for the tab you came from.
            compose.onNodeWithTag("search-close").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithTag("new-session")[0].assertExists()
        }
    }

    /** `testPullToRefreshEnds` (:671): the list settles back within 4 dp. */
    @Test
    fun pullToRefreshEnds() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("section-pinned").fetchSemanticsNodes().isNotEmpty()
        }
        val resting = compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value

        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.swipe(width / 2, height / 4, width / 2, (height * 0.85f).toInt(), 30)

        // The refresh runs against the real engine and the list settles
        // back — no sleeps: poll the row back to its resting position.
        compose.waitUntil(timeoutMillis = 5_000) {
            kotlin.math.abs(
                compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value - resting,
            ) <= 4f
        }
        assertEquals(resting, compose.onNodeWithTag("section-pinned").getBoundsInRoot().top.value, 4f)
    }

    // ── the session flows ───────────────────────────────────────────────

    /** `testSendEchoesAndStreamsReply` (:43): the echo lands, then the mock reply's markdown row. */
    @Test
    fun sendEchoesAndStreamsReply() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        // Optimistic echo appears immediately, then the host streams a reply.
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodes(FlowSemantics.startsWith("You: Summarize the launch post"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("streaming.png")
        // The host's reply lands after the echo and the turn settles.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("reply-complete.png")
    }

    /**
     * `testLongDraftRestsAsWholeLinePreview` (:65): a long draft rests as
     * whole lines (under 90 dp), focusing restores the full card.
     */
    @Test
    fun longDraftRestsAsWholeLinePreview() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performClick().performTextInput(
            "let's fix the new worktree icon it makes no sense, also let's make it so that " +
                "there's no descriptions the line is too long and wraps onto a fourth line",
        )
        FlowSemantics.saveScreenshot("draft-focused.png")

        // Leave the session (the IME first, then the pop) and come back.
        device.pressBack() // IME down
        compose.waitForIdle()
        device.pressBack() // pop to the sessions list
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("session-chat-deploy").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-chat-deploy").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // Two whole lines plus the capsule's insets — never a third.
        val restingHeight = compose.onNodeWithTag("composer-input").getBoundsInRoot().height.value
        assertTrue("resting preview must stay under 90 dp (was $restingHeight)", restingHeight < 90f)
        FlowSemantics.saveScreenshot("draft-resting.png")
        compose.onNodeWithTag("composer-input").performClick()
        val focusedHeight = compose.onNodeWithTag("composer-input").getBoundsInRoot().height.value
        assertTrue("focusing restores the full draft (was $focusedHeight)", focusedHeight > 90f)
        FlowSemantics.saveScreenshot("draft-reopened.png")
    }

    /** `testQuestionPanelAnswersAndResumes` (:86): the `question` engine's panel, answered. */
    @Test
    fun questionPanelAnswersAndResumes() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Pick a tone for the post")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("question-option-0").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("question.png")
        compose.onNodeWithTag("question-option-0").performClick()
        // The composer returns after answering.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testQueueWhileWorking` (:100): queue two follow-ups on the slow engine. */
    @Test
    fun queueWhileWorking() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Write a long reply please.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Then add a TL;DR.")
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithContentDescription("Queue message").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("queued.png")

        // A second queued message, then the composer put away.
        compose.onNodeWithTag("composer-input").performClick().performTextInput(
            "And link the docs, with a much longer follow-up line that has to wrap or fade somewhere.",
        )
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        // Regression: a long queued message pushed its row's buttons off the card.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().size >= 2
        }
        FlowSemantics.saveScreenshot("queued-two.png")
    }

    /** `testQueueSurvivesNavigatingAway` (:568): the queue re-materializes after a round trip. */
    @Test
    fun queueSurvivesNavigatingAway() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Write a long reply please.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Then add a TL;DR.")
        compose.onAllNodesWithContentDescription("Queue message")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        // Dismiss the IME first, then pop the session.
        device.pressBack()
        compose.waitForIdle()
        device.pressBack()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-chat-long").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("queue-after-return.png")
    }

    /** `testCancelledSwipeBackKeepsStreaming` (:527): a partial edge swipe never detaches the session. */
    @Test
    fun cancelledSwipeBackKeepsStreaming() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()

        // Drag from the left edge a third of the way and let go: the
        // predictive-back pop cancels (the release is before the commit
        // threshold — `NavStackHost`'s PredictiveBackHandler).
        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.swipe((width * 0.01f).toInt(), height / 2, (width * 0.3f).toInt(), height / 2, 40)
        compose.waitForIdle()

        // Still on the session, no accessory over it, and the reply keeps
        // streaming to completion.
        compose.onNodeWithTag("composer-input").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isEmpty() ||
                compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testTapOutsideMinimizesComposer` (:549): the tap dismisses the IME and the composer rests as the capsule. */
    @Test
    fun tapOutsideMinimizesComposer() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Half-written thought")
        compose.waitForIdle()

        // A plain tap on the transcript (a third down, centred).
        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.click(width / 2, (height * 0.35f).toInt())
        compose.waitForIdle()

        // The composer rests as the capsule (no card toolbar chip), and
        // the draft stays.
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("composer-input").assertExists()
        FlowSemantics.saveScreenshot("composer-minimized.png")
    }

    /** `testFileMentionSuggestions` (:265): "@lay" finds the fixture repo's layout files. */
    @Test
    fun fileMentionSuggestions() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Look at @lay")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("mention-0").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("mentions.png")
        compose.onNodeWithTag("mention-0").performClick()
        compose.onNodeWithTag("composer-input")
            .assertTextContains("@mod.rs", substring = true)
    }

    /** `testArchiveWithUndo` (:278): swipe → Archive → the toast's Undo restores the row. */
    @Test
    fun archiveWithUndo() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("session-chat-deploy").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-chat-deploy").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Archive").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Archive")[0].performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("toast-action").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("archive-undo.png")
        compose.onNodeWithTag("toast-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("session-chat-deploy").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testToolGroupExpandsAndShowsDetail` (:344): the disclosure and the inline detail. */
    @Test
    fun toolGroupExpandsAndShowsDetail() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Expand")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Show details").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("tools-expanded.png")
        // Details open inline under the row, like desktop.
        compose.onAllNodesWithContentDescription("Show details")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Hide details").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("tool-detail.png")
    }

    /** `testEffortPickerInSession` (:360): the effort chip's menu, twice. */
    @Test
    fun effortPickerInSession() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-chip-effort").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-chip-effort").performClick()
        // The mock model's efforts (Low…XHigh); the menu's first item.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Low").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("effort-menu.png")
        compose.onAllNodesWithText("Low")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // Reopening the menu must not crash (the regression the iOS test guards).
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Low").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testModelPickerInSession` (:459): the model menu completes and dismisses. */
    @Test
    fun modelPickerInSession() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-chip-model").performClick()
        // The mock engine's models (Mock 1 / Fable 5).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Mock 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Mock 1")[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testAttachMenuOpens` (:438): "+" opens the attach menu, also from the card state. */
    @Test
    fun attachMenuOpens() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-attach").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Photo Library").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("attach-menu.png")
        // Card state (focused, with a draft): the toolbar row must not cover "+".
        device.pressBack() // close the menu
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Draft")
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Photo Library").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `testSendGlidesPromptToTopWithRunway` (:653): the prompt glides to the top of the transcript. */
    @Test
    fun sendGlidesPromptToTopWithRunway() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Runway check: summarize the veil work")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.startsWith("You: Runway check"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The runway reserves the reply space below the glided prompt.
        compose.waitUntil(timeoutMillis = 5_000) {
            val promptTop = compose.onAllNodes(FlowSemantics.startsWith("You: Runway check"))
                .fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y ?: Float.MAX_VALUE
            val transcriptTop = compose.onNodeWithTag("transcript").getBoundsInRoot().top.value
            promptTop / compose.density.density - transcriptTop < 200f
        }
        FlowSemantics.saveScreenshot("runway.png")
    }

    // ── the failed-open flows (the pre-rig tests, now on the rig) ───────

    /** `testUnknownChatRouteReturnsToSessions` (:213): the verbatim iOS unknown-id form. */
    @Test
    fun unknownChatRouteReturnsToSessions() {
        failedOpenReturnsToSessions("unknown-chat-returned-to-sessions.png")
    }

    /** A well-formed scoped id whose engine key is not paired (the Roboco ADD of #76). */
    @Test
    fun forgottenEngineChatRouteReturnsToSessions() {
        failedOpenReturnsToSessions("forgotten-engine-chat-returned-to-sessions.png")
    }

    private fun failedOpenReturnsToSessions(artifactName: String) {
        val chatId = checkNotNull(routeChatId) { "routeFor did not record the chat id" }

        // Within 10 s the toast is on the Sessions root (iOS waits 10 s
        // for the same label). The failed open never slides anything in.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Couldn't open session. Try again.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Sessions")[0].assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }

        // A failed open must not present a conversation.
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isEmpty()
        }
        // No failure UI of any kind: the toast is the only visible result.
        compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().let { nodes ->
            assertTrue("no Unavailable failure UI", nodes.isEmpty())
        }
        // No fixture prompt for a missing chat, and no row for it.
        compose.onAllNodes(FlowSemantics.userEcho()).fetchSemanticsNodes().let { nodes ->
            assertTrue("no fixture user echo for a missing chat", nodes.isEmpty())
        }
        compose.onAllNodesWithTag("session-$chatId").fetchSemanticsNodes().let { nodes ->
            assertTrue("no row for the missing chat", nodes.isEmpty())
        }
        FlowSemantics.saveScreenshot(artifactName)
    }

    // ── rows whose owning tickets have not landed (see the class docs) ──

    @Ignore("Needs ui-parity #126: the transcript link hit-test + Custom Tab routing (no 'transcript-link' semantics yet)")
    @Test
    fun tappingLinkOpensIt() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the 'jump-to-latest' test tag (the transcript bottom stack's owning ticket)")
    @Test
    fun jumpToLatestAfterScrollingUp() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the 'jump-to-latest' test tag; the slow engine's chat-long transcript is the fixture")
    @Test
    fun scrollingUpWhileStreamingStaysPut() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113 replace NewSessionSheetPlaceholder)")
    @Test
    fun newSessionFromAccessory() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun newSessionRemembersDraftWhenClosed() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun newSessionHeadlineStaysAboveComposer() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun effortPickerInNewSession() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet (ui-parity #92/#113)")
    @Test
    fun modelChipNamesAModelAtOnce() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet + the folder browser write path (ui-parity #92/#110–#113)")
    @Test
    fun newProjectFolderBrowser() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the real New Session sheet + cross-engine project grouping (ui-parity #92/#113)")
    @Test
    fun projectPickerGroupsClonesAndHostPicksCheckout() = fail("unwired: see the @Ignore reason")

    // ── helpers ─────────────────────────────────────────────────────────

    /** The `route` extra per test method (§6: chat:<id> | new | more | search). */
    private fun routeFor(name: String): String? = when {
        name.startsWith("frontPage") ||
            name.startsWith("tabsAndSearch") ||
            name.startsWith("searchTab") ||
            name.startsWith("pullToRefresh") ||
            name.startsWith("archiveWithUndo") -> null
        name.startsWith("questionPanel") -> "chat:chat-ask"
        name.startsWith("queue") ||
            name.startsWith("scrollingUp") ||
            name.startsWith("cancelledSwipeBack") -> "chat:chat-long"
        name.startsWith("unknownChat") -> "chat:${unknownChatId().also { routeChatId = it }}"
        name.startsWith("forgottenEngine") -> "chat:${forgottenEngineChatId().also { routeChatId = it }}"
        else -> "chat:chat-deploy"
    }

    /** The engines to pair per test method (§5 profiles). */
    private fun enginesFor(name: String): Set<FixtureEngine> = when {
        name.startsWith("questionPanel") -> setOf(FixtureEngine.Question)
        name.startsWith("queue") ||
            name.startsWith("scrollingUp") ||
            name.startsWith("cancelledSwipeBack") -> setOf(FixtureEngine.Slow)
        else -> setOf(FixtureEngine.Main)
    }

    /** The verbatim iOS form (`SessionFlowTests.swift:215`). */
    private fun unknownChatId(): String = "missing-notification-chat-" + UUID.randomUUID()

    /**
     * A well-formed scoped id whose engine key is never paired:
     * `engine:v1:<base64url-no-pad(JSON ["<uuid>","missing-chat"])>`
     * (`crates/ui/src/engine_registry.rs:15,36-45`; the JSON is the
     * `serde_json` tuple form, no spaces).
     */
    private fun forgottenEngineChatId(): String {
        val json = "[\"${UUID.randomUUID()}\",\"missing-chat\"]"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "engine:v1:$encoded"
    }
}
