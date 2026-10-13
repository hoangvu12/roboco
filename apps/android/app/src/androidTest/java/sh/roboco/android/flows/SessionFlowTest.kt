package sh.roboco.android.flows

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import sh.roboco.android.MainActivity
import sh.roboco.android.RobocoApplication

/**
 * The failable-open flow (android-ui-parity #76, CI-only — no emulator
 * in the worker slots): the Android port of
 * `testUnknownChatRouteReturnsToSessions`
 * (`iosui:SessionFlowTests.swift:208-233`), run twice — once per failure
 * kind:
 *
 * - `unknownChatRouteReturnsToSessions`: the verbatim iOS unknown-id form,
 *   `missing-notification-chat-<uuid>`;
 * - `forgottenEngineChatRouteReturnsToSessions` (Roboco ADD): a
 *   well-formed scoped id whose engine key is not paired — what a
 *   notification or link for a forgotten engine carries.
 *
 * The `route` extra (`chat:<id>`) drives the same
 * `AppRouter.openSession` entry point as a row tap (`SceneDelegate.swift:64-71`);
 * the core refuses the id, `SessionRouteFactory.open` returns null, and
 * the shell shows the toast on the Sessions root. No placeholder, no
 * empty conversation, no fixture data (the iOS demo-fixture assertions
 * become "no `You: ` transcript label at all").
 *
 * Prerequisite: android-app#30's flow rig with the `main` fixture engine
 * paired before the test (adb reverse + the rig's first-run pairing); a
 * fresh emulator without it shows the pairing root, not the Sessions
 * tab — skip, don't fail. When that rig's shared `FixtureRule` (mint +
 * the `pair` launch extra) lands, this file folds into it; until then
 * [RouteLaunchRule] is the launch half.
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=sh.roboco.android.flows.SessionFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {

    private val app: RobocoApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as RobocoApplication

    /**
     * Launches [MainActivity] once per test with the `route` extra the
     * method's name picks: a `forgottenEngine*` method launches the
     * scoped-id form, anything else the verbatim iOS unknown-id form.
     * [chatId] is the id this test launched with (for the
     * `session-<id>` assertion). The rule is applied by [compose] — only
     * the compose rule is a JUnit rule, so the launch happens exactly
     * once.
     */
    private val launch = RouteLaunchRule()

    @Suppress("DEPRECATION") // the public AndroidComposeTestRule constructor is the one way to hand it a custom rule
    @get:Rule
    val compose: AndroidComposeTestRule<RouteLaunchRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity) { "RouteLaunchRule did not launch MainActivity" }
        }

    @Before
    fun requirePairedFixtureEngine() {
        // The startup load is async (`coreStartup.start()` was only just
        // queued by the launch), so poll briefly for the paired engine
        // before deciding the rig is absent — a skip must mean the rig
        // is missing, not a slow load. The Sessions root (and its
        // new-session accessory) only exists with a paired engine; a
        // fresh emulator without the rig shows the pairing root instead.
        val deadline = System.currentTimeMillis() + 10_000
        var engines = app.clientOrNull()?.engines().orEmpty()
        while (engines.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            engines = app.clientOrNull()?.engines().orEmpty()
        }
        assumeTrue(
            "the flow rig's paired engine is missing (start android_fixture first)",
            engines.isNotEmpty(),
        )
    }

    // ── the two failure kinds ───────────────────────────────────────────

    @Test
    fun unknownChatRouteReturnsToSessions() {
        failedOpenReturnsToSessions("unknown-chat-returned-to-sessions")
    }

    @Test
    fun forgottenEngineChatRouteReturnsToSessions() {
        failedOpenReturnsToSessions("forgotten-engine-chat-returned-to-sessions")
    }

    // ── the shared body (the iOS test's assertion table) ────────────────

    private fun failedOpenReturnsToSessions(artifactName: String) {
        val chatId = launch.chatId

        // Within 10 s the toast is on the Sessions root (iOS waits 10 s
        // for the same label). The failed open never slides anything in.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Couldn't open session. Try again.")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The Sessions root is up within 5 s: the large title of ticket 46
        // (the tab pill label may also match).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Sessions")[0].assertIsDisplayed()
        // And nothing was pushed: the new-session accessory (ticket 27)
        // only shows on a tab root with no Session on top (ticket 30).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("new-session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("new-session")[0].assertIsDisplayed()

        // A failed open must not present a conversation.
        compose.onAllNodesWithTag("transcript").assertCountEquals(0)
        compose.onAllNodesWithTag("composer-input").assertCountEquals(0)
        // No failure UI of any kind: the toast is the only visible result.
        compose.onAllNodesWithText("Unavailable").assertCountEquals(0)
        compose.onAllNodesWithText("This session isn't available.").assertCountEquals(0)
        // Roboco has no demo fixtures (the DROP of the iOS prompt/reply
        // strings): the general rule is that no transcript row of any
        // chat is on screen — no user-message label at all.
        compose.onAllNodes(textOrDescriptionStartsWithYou()).assertCountEquals(0)
        // And no row for the missing chat.
        compose.onAllNodesWithTag("session-$chatId").assertCountEquals(0)

        // The screenshot artefact (the flow rig pulls it from the app's
        // external files dir).
        saveScreenshot("$artifactName.png")
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** No node whose text or content description starts with "You: " (129's user-message label prefix). */
    private fun textOrDescriptionStartsWithYou(): SemanticsMatcher =
        SemanticsMatcher("text or content description starts with 'You: '") { node ->
            val config = node.config
            (config.contains(SemanticsProperties.Text) &&
                config[SemanticsProperties.Text].any { it.text.startsWith("You: ") }) ||
                (config.contains(SemanticsProperties.ContentDescription) &&
                    config[SemanticsProperties.ContentDescription].any { it.startsWith("You: ") })
        }

    /**
     * Writes the PNG next to the other flow artefacts, on the device's
     * app-scoped external storage (`adb pull
     * /sdcard/Android/data/sh.roboco.android/files/<name>`).
     */
    private fun saveScreenshot(name: String) {
        val dir = app.getExternalFilesDir(null) ?: return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            dir.resolve(name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
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

    /** See the class docs; picks the id form from the test method's name. */
    private inner class RouteLaunchRule : TestRule {

        lateinit var scenario: ActivityScenario<MainActivity>
            private set

        lateinit var chatId: String
            private set

        override fun apply(base: Statement, description: Description): Statement {
            chatId = if (description.methodName.startsWith("forgottenEngine")) {
                forgottenEngineChatId()
            } else {
                unknownChatId()
            }
            val intent = Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                MainActivity::class.java,
            ).putExtra("route", "chat:$chatId")
            return object : Statement() {
                override fun evaluate() {
                    scenario = ActivityScenario.launch(intent)
                    try {
                        base.evaluate()
                    } finally {
                        scenario.close()
                    }
                }
            }
        }
    }
}
