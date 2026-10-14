package sh.roboco.android.flows

import android.content.ClipboardManager
import android.graphics.Point
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.unit.height
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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
 * routing), `testImageUploadShowsRingOnThumbnail` (ui-parity #93's
 * clipboard-image paste path), `testJumpToLatestAfterScrollingUp` /
 * `testScrollingUpWhileStreamingStaysPut` (the `jump-to-latest` tag),
 * `testNewSessionRemembersDraftWhenClosed`,
 * `testNewSessionHeadlineStaysAboveComposer`,
 * `testEffortPickerInNewSession`, `testModelChipNamesAModelAtOnce`,
 * `testNewProjectFolderBrowser` and
 * `testProjectPickerGroupsClonesAndHostPicksCheckout` (the chips and
 * menus of ui-parity #92/#109/#113/#114 — the sheet itself, 93, and the
 * create + first send, 94, have landed).
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

    /**
     * `testCancelledSwipeBackKeepsStreaming` (:524-545): a half swipe-back
     * (cancelled) never detaches the session — the reply keeps streaming
     * to completion, and no "New session" accessory shows over the
     * session. The gesture is dispatched through
     * `OnBackPressedDispatcher` (deterministic); the finger-level variant
     * is [cancelledSwipeBackKeepsStreaming_gesture].
     */
    @Test
    fun cancelledSwipeBackKeepsStreaming() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        val before = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        // The turn is running before the gesture: the send button flipped
        // to Stop (the slow fixture keeps the reply streaming for seconds).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag from the left edge a third of the way, slowly, hold, and
        // release towards the edge: the predictive-back pop cancels.
        val dispatcher = compose.activity.onBackPressedDispatcher
        val halfHeight = compose.activity.window.decorView.height / 2f
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, halfHeight, 0f, BackEventCompat.EDGE_LEFT))
        }
        for (progress in floatArrayOf(0.1f, 0.2f, 0.3f)) {
            SystemClock.sleep(50)
            compose.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(
                    BackEventCompat(40f, halfHeight, progress, BackEventCompat.EDGE_LEFT),
                )
            }
        }
        SystemClock.sleep(300) // the hold before the release
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertTheCancelledSwipeKeptTheSessionStreaming(before)
    }

    /**
     * The finger-level variant of [cancelledSwipeBackKeepsStreaming], on a
     * device with gesture navigation: a real out-and-back edge drag (the
     * system cancels a release that does not cross the commit threshold —
     * a plain 30 % drag-and-release would commit on Android). `@Tour`:
     * recorded, not in the PR emulator run.
     */
    @Test
    @Tour
    fun cancelledSwipeBackKeepsStreaming_gesture() {
        assumeTrue(
            "the finger variant needs gesture navigation",
            Settings.Secure.getInt(compose.activity.contentResolver, "navigation_mode", 0) == 2,
        )
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        val before = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Summarize the launch post in three bullets.")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag out to a third of the width, then back to the edge, so the
        // release cancels (the iOS motion, adapted for Android's commit).
        val width = compose.activity.window.decorView.width
        val height = compose.activity.window.decorView.height
        device.swipe(
            arrayOf(Point(1, height / 2), Point((0.3f * width).toInt(), height / 2), Point(4, height / 2)),
            40,
        )
        compose.waitForIdle()

        assertTheCancelledSwipeKeptTheSessionStreaming(before)
    }

    /**
     * The shared tail of the cancelled-swipe pair: still on the session,
     * no accessory over it, the turn still running right after the cancel,
     * a new markdown row within 20 s, the turn settled within 40 s, and the
     * last markdown text changed after the cancel (129 exposes the text;
     * until then only that comparison is skipped).
     */
    private fun assertTheCancelledSwipeKeptTheSessionStreaming(before: Int) {
        // Still on the session, and no accessory over it (an empty list
        // also passes — 30 does not place it over a session).
        compose.onNodeWithTag("composer-input").assertIsDisplayed()
        // isDisplayed() is a SemanticsNodeInteraction extension, not a
        // SemanticsNode member: check each node through the collection.
        val accessories = compose.onAllNodesWithTag("new-session")
        val count = accessories.fetchSemanticsNodes().size
        assertTrue(
            "no New session accessory over the session",
            (0 until count).none { accessories[it].isDisplayed() },
        )
        // The turn is still running right after the cancel.
        assertTrue(
            "the turn is still running after the cancelled swipe",
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty(),
        )
        val afterCancel = lastMarkdownText()

        // The reply keeps streaming: a new markdown row lands within 20 s.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().size > before
        }
        // Updates keep arriving to the end: the send button flips back and
        // the reply's tail moved on after the cancel.
        compose.waitUntil(timeoutMillis = 40_000) { sendLabel() == "Send message" }
        val settled = lastMarkdownText()
        if (afterCancel != null && settled != null) {
            assertNotEquals("the reply's tail changed after the cancel", afterCancel, settled)
        }
    }

    /**
     * The last `row-markdown` node's text or content description — 129's
     * a11y tree exposes one of them; `null` until it lands.
     */
    private fun lastMarkdownText(): String? {
        val nodes = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val config = nodes.last().config
        return when {
            config.contains(SemanticsProperties.Text) -> config[SemanticsProperties.Text].firstOrNull()?.text
            config.contains(SemanticsProperties.ContentDescription) ->
                config[SemanticsProperties.ContentDescription].firstOrNull()
            else -> null
        }
    }

    /** The composer's send-button label: "Send message" or "Stop response". */
    private fun sendLabel(): String? {
        val nodes = compose.onAllNodesWithTag("composer-send").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val config = nodes[0].config
        return if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].firstOrNull()
        } else {
            null
        }
    }

    /** `testTapOutsideMinimizesComposer` (:549): the tap dismisses the IME and the composer rests as the capsule. */
    @Test
    fun tapOutsideMinimizesComposer() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Half-written thought")

        // The IME is up (≤ 3 s) and the composer is the focused card: a
        // 1-line card is text 46 + toolbar 50 = 96 dp, so over 90 dp, and
        // the card toolbar's model chip is live.
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isNotEmpty()
        }
        val cardHeight = compose.onNodeWithTag("session-composer").getBoundsInRoot().height.value
        assertTrue("the composer is the card (was $cardHeight dp)", cardHeight > 90f)

        // 81: the tap lands 8 dp in — inside the 18 dp row margin
        // (`mob:layout/rows.rs:124`), where no link or control can be (the
        // fixture replies contain links, so the centre iOS taps at is not
        // safe) — at the iOS 0.35 height.
        compose.onNodeWithTag("transcript").performTouchInput {
            click(Offset(8.dp.toPx(), height * 0.35f))
        }

        // The IME goes down (≤ 3 s), the morph settles (iOS sleeps 1 s).
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        SystemClock.sleep(1_000)
        compose.waitForIdle()

        // The capsule (one line: 50 dp) and the kept draft: iOS asserts
        // hasPrefix("Half-written") — the sim keyboard's autocorrect may
        // append — so prefix, not contains (EditableText is an
        // AnnotatedString).
        val capsuleHeight = compose.onNodeWithTag("session-composer").getBoundsInRoot().height.value
        assertTrue("the composer rests as the capsule (was $capsuleHeight dp)", capsuleHeight < 90f)
        val draft = compose.onNodeWithTag("composer-input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertTrue("the draft is kept (was '$draft')", draft.startsWith("Half-written"))
        FlowSemantics.saveScreenshot("composer-minimized.png")
    }

    /** The IME's visibility through the window insets (81's probe). */
    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

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

    /**
     * `testToolGroupsRenderLikeDesktop` (:494): the static expanded view
     * on `chat-veil`, then a live streaming run screenshotted over time
     * on the slow engine's `chat-long` — both engines paired.
     */
    @Test
    fun toolGroupsRenderLikeDesktop() {
        // Static: file badges, inline stats/diff detail, thoughts.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Expand")[0].performClick()
        compose.waitForIdle()
        FlowSemantics.saveScreenshot("tool-groups.png")
        val details = compose.onAllNodesWithContentDescription("Show details")
        if (details.fetchSemanticsNodes().isNotEmpty()) {
            details[0].performClick()
            compose.waitForIdle()
            FlowSemantics.saveScreenshot("tool-row-detail.png")
        }

        // Live: a streaming run on the slow engine's chat, screenshotted
        // as the group streams in (the mock's paced events).
        device.pressBack()
        compose.waitForIdle()
        device.pressBack()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("session-chat-long").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-chat-long").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Refactor the layout pass")
        compose.onNodeWithTag("composer-send").performClick()
        for (k in 0 until 6) {
            Thread.sleep(1_000) // the live window the iOS test samples
            FlowSemantics.saveScreenshot("live-tools-$k.png")
        }
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

    // ── the offline read-only flow (Roboco ADD, 79) ──────────────────

    /**
     * An engine dropping mid-session makes the chat read-only — writes
     * are refused, never queued (android-app#06/#07). `GET
     * /listener/stop` on the fixture's control port kills the engine's
     * listener (every accepted WebSocket ends), the pill shows
     * `Reconnecting… Cached history is read-only.` within 15 s, Send
     * greys out (a click does nothing — the draft stays) and the cached
     * transcript keeps painting, seeded turn included. After `GET
     * /listener/start` the pill clears and Send re-enables within 30 s
     * without leaving the screen (the redial backoff caps at 15 s plus
     * 0–255 ms jitter, android-app#06).
     */
    @Test
    fun offlineEngineIsReadOnly() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Wrangler deploy hygiene").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("draft kept offline")
        compose.onNodeWithTag("composer-send").assertIsEnabled()

        val control = launch.controlUrl(FixtureEngine.Main)!!
        try {
            listenerCommand(control, "stop")

            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Reconnecting… Cached history is read-only.")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            // Send is disabled and a click does nothing: the draft stays.
            compose.onNodeWithTag("composer-send").assertIsNotEnabled()
            compose.onNodeWithTag("composer-send").performClick()
            compose.waitForIdle()
            // Ticket 80 (79's step 9.7): the ⋯ menu's rows are gated —
            // Pin and Archive read the engine and are inert, Copy
            // Transcript reads only the cached frame and stays live, and
            // the copy really lands on the clipboard.
            compose.onNodeWithTag("session-more").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("glass-menu").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("session-menu-pin").assertIsNotEnabled()
            compose.onNodeWithTag("session-menu-archive").assertIsNotEnabled()
            compose.onNodeWithTag("session-menu-copy").assertIsEnabled()
            compose.onNodeWithTag("session-menu-copy").performClick()
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            compose.waitUntil(timeoutMillis = 5_000) {
                val clip = clipboard?.primaryClip
                clip != null && clip.itemCount > 0 &&
                    clip.description.label?.toString() == "Transcript" &&
                    clip.getItemAt(0).coerceToText(app).isNotBlank()
            }
            compose.waitUntil(timeoutMillis = 2_000) {
                compose.onAllNodesWithTag("glass-menu").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(
                "the draft stays while offline",
                "draft kept offline",
                compose.onNodeWithTag("composer-input")
                    .fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
            )

            // The cached transcript keeps painting, seeded turn included.
            assertTrue(compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty())
            assertTrue(
                compose.onAllNodesWithTag("row-markdown", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            FlowSemantics.saveScreenshot("offline-readonly.png")
        } finally {
            // Whatever happened above, later tests in this run get their
            // engine back.
            listenerCommand(control, "start")
        }

        // Recovery without reopening the screen.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Reconnecting… Cached history is read-only.")
                .fetchSemanticsNodes().isEmpty() &&
                sendIsEnabled()
        }
        compose.onNodeWithTag("composer-send").assertIsEnabled()
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

    @Ignore("Needs the clipboard-image staging of ui-parity #93's Paste Image path (a content-URI image on the clipboard, unreachable from the instrumentation API before that ticket's share flow)")
    @Test
    fun imageUploadShowsRingOnThumbnail() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the 'jump-to-latest' test tag (the transcript bottom stack's owning ticket)")
    @Test
    fun jumpToLatestAfterScrollingUp() = fail("unwired: see the @Ignore reason")

    @Ignore("Needs the 'jump-to-latest' test tag; the slow engine's chat-long transcript is the fixture")
    @Test
    fun scrollingUpWhileStreamingStaysPut() = fail("unwired: see the @Ignore reason")

    // ── the New Session flow (94's create + first send) ──────────────

    /**
     * `testNewSessionFromAccessory` (`SessionFlowTests.swift:245-262`):
     * the accessory opens the sheet (93), typing and Send run 94's create
     * + first send, and the handoff lands in the new session — the
     * transcript exists within 10 s, the IME is down within 3 s, the
     * optimistic echo (`You: Add a dark mode toggle`) shows within 5 s,
     * and 1 s later the sheet (and its `Close` button) is gone. The
     * capture froze the page mid-handoff; until 95 lands, the router's
     * `HandoffAnimator.Instant` reveals the session at once.
     */
    @Test
    fun newSessionFromAccessory() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new-session").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("new-session.png")

        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Add a dark mode toggle to settings")
        compose.onNodeWithTag("composer-send").performClick()

        // "hands off into the new session"
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
        // Sending puts the keyboard away.
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(FlowSemantics.startsWith("You: Add a dark mode toggle"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The handoff ends with the sheet gone and the message in the
        // transcript (iOS sleeps 1 s before asserting no Close).
        SystemClock.sleep(1_000)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isEmpty()
        }
        FlowSemantics.saveScreenshot("new-session-created.png")
    }

    @Ignore("Needs the chips and menus of ui-parity #92/#109/#113/#114 (the draft-remember path)")
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
            name.startsWith("archiveWithUndo") ||
            name.startsWith("newSessionFromAccessory") -> null
        name.startsWith("questionPanel") -> "chat:chat-ask"
        name.startsWith("toolGroupsRender") -> "chat:chat-veil"
        name.startsWith("queue") ||
            name.startsWith("scrollingUp") ||
            name.startsWith("cancelledSwipeBack") -> "chat:chat-long"
        name.startsWith("unknownChat") -> "chat:${unknownChatId().also { routeChatId = it }}"
        name.startsWith("forgottenEngine") -> "chat:${forgottenEngineChatId().also { routeChatId = it }}"
        // offlineEngine… (79) and everything else: the main engine's
        // chat-deploy, exactly like sendEchoesAndStreamsReply.
        else -> "chat:chat-deploy"
    }

    /** The engines to pair per test method (§5 profiles). */
    private fun enginesFor(name: String): Set<FixtureEngine> = when {
        name.startsWith("questionPanel") -> setOf(FixtureEngine.Question)
        name.startsWith("toolGroupsRender") -> setOf(FixtureEngine.Main, FixtureEngine.Slow)
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

    // ── the fixture engine's listener control (79) ─────────────────────

    /**
     * True when the composer's send button exists and carries no
     * `disabled()` semantics — the waitUntil-safe form of
     * `assertIsEnabled` (assertions throw inside the predicate; this
     * reads the node's config instead).
     */
    private fun sendIsEnabled(): Boolean {
        val nodes = compose.onAllNodesWithTag("composer-send").fetchSemanticsNodes()
        return nodes.isNotEmpty() && !nodes[0].config.contains(SemanticsProperties.Disabled)
    }

    /**
     * `GET /listener/<command>` (`stop` | `start`) on the fixture engine's
     * control port — ticket 79's fixture routes, which drop and re-create
     * the engine's remote listener mid-session. On IO like [FixtureRule]'s
     * mint (the network-on-main guard); a non-200 fails loudly, because
     * that means the rig broke, not the app.
     */
    private fun listenerCommand(controlUrl: String, command: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val base = controlUrl.trim().trimEnd('/')
                val connection = URL("$base/listener/$command").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    assertEquals(
                        "the fixture's /listener/$command failed",
                        200,
                        connection.responseCode,
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
