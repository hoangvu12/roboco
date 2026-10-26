package sh.roboco.android.flows

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import sh.roboco.android.MainActivity

/**
 * The three fixture engines of the flow rig (android-app#30 §5):
 * `android_fixture --profile main|slow|question`, one process each, with
 * the control-port URL passed as the instrumentation argument
 * [arg] (`-Pandroid.testInstrumentationRunnerArguments.…`, set by
 * `apps/android/scripts/flow-tests.sh`). [portArg] is the engine's own
 * adb-reversed port (the room rig's member addresses, ticket 04).
 */
enum class FixtureEngine(val arg: String, val portArg: String) {
    /** The seeded workspace: links, tables, code, thinking in the replies. */
    Main("fixtureMain", "fixtureMainPort"),
    /** A paced, long streaming reply (queue/stop/scroll/notification). */
    Slow("fixtureSlow", "fixtureSlowPort"),
    /** A run that asks the user questions (the QuestionPanel). */
    Question("fixtureQuestion", "fixtureQuestionPort"),
    /**
     * android-next#02: a `main`-profile engine published over Tailcat
     * (local DERP relay); its engine port is not reachable from the device.
     * Present only when the rig built the Go pieces.
     */
    Tailcat("fixtureTailcat", "fixtureTailcatPort"),
}

/**
 * The instrumented flow rig's launch rule (android-app#30): the Android
 * replacement for upstream's `-demo` launch argument. For every test it
 *
 * 1. reads the fixture engines' control URLs from the instrumentation
 *    arguments (`fixtureMain` / `fixtureSlow` / `fixtureQuestion`);
 * 2. mints a fresh single-use pairing URL per engine the test needs
 *    (`GET /mint` — pair codes are single-use, so every test mints its
 *    own);
 * 3. launches [MainActivity] with the debug `pair` extra (comma-separated
 *    URLs, redeemed before the shell shows), the `route` extra
 *    ([routeFor] by test name — `chat:<id>` | `new` | `more` | `search`,
 *    like `ios:App/SceneDelegate.swift:64-71`) and `harness mock` (the
 *    Android form of iOS's `-harness`, `AppModel.swift:45-46`: live-stack
 *    tests must never start a real agent).
 *
 * Absent instrumentation arguments mean the rig is not running (a local
 * `connectedDebugAndroidTest` without the scripts) — the rule **skips**
 * the test, like the pre-rig tests did. Present-but-broken (a mint that
 * fails or returns no URL) **fails** loudly: that is the rig breaking,
 * not a missing rig.
 *
 * [pairAtLaunch] = false launches **unpaired** (only the `harness` and
 * `route` extras) — [LiveEngineRoundTripTest] drives the visible pairing
 * UI and mints through [controlUrl] + [mint] instead.
 *
 * Usage (the established shape — only the compose rule is a JUnit rule, so
 * the launch is applied exactly once):
 *
 * ```kotlin
 * private val launch = FixtureRule(routeFor = { "chat:chat-deploy" })
 *
 * @Suppress("DEPRECATION")
 * @get:Rule
 * val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
 *     AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }
 * ```
 */
class FixtureRule(
    /** The `route` extra per test method name (`null` = none). */
    private val routeFor: (String) -> String? = { null },
    /** The fixture engines to pair per test method name. */
    private val enginesFor: (String) -> Set<FixtureEngine> = { setOf(FixtureEngine.Main) },
    /** Mint + send the `pair` extra (false = launch unpaired). */
    private val pairAtLaunch: Boolean = true,
    /** Extra string launch extras per test (the wallpaper flow's `wallpaper`/`wallpaper-effect`). */
    private val extrasFor: () -> Map<String, String> = { emptyMap() },
) : TestRule {

    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    /**
     * Set by a test that brings MainActivity back through a NEW intent (a
     * notification tap): MainActivity.onNewIntent calls `setIntent`, and
     * ActivityScenario matches lifecycle events against its own start
     * intent, so from then on it ignores the activity's stage changes and
     * `close()` throws "Current state was null unexpectedly"
     * (NotificationFlowTest, run 38567067554 — the test body had passed).
     * Teardown then finishes the live activities directly.
     */
    var newIntentDelivered = false

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val name = description.methodName
                val control = controlUrls()

                val engines = enginesFor(name)
                val missing = engines.filter { control[it] == null }
                assumeTrue(
                    "the flow rig is absent (start the android_fixture engines: " +
                        "apps/android/scripts/flow-tests.sh / flow-tests.ps1)",
                    missing.isEmpty(),
                )

                val target = InstrumentationRegistry.getInstrumentation().targetContext
                val intent = Intent(target, MainActivity::class.java)
                    // iOS's `-harness mock`: never a real agent.
                    .putExtra("harness", "mock")
                routeFor(name)?.let { route -> intent.putExtra("route", route) }
                for ((key, value) in extrasFor()) {
                    intent.putExtra(key, value)
                }
                if (pairAtLaunch) {
                    val urls = engines.map { engine ->
                        val minted = mint(control.getValue(engine))
                        assertTrue(
                            "the rig's ${engine.arg} control endpoint is broken: /mint failed",
                            minted != null,
                        )
                        minted
                    }
                    // The debug `pair` extra (ticket 30 + ui-parity #36's
                    // LaunchOptions): redeemed before the shell shows.
                    intent.putExtra("pair", urls.joinToString(","))
                }

                scenario = ActivityScenario.launch(intent)
                try {
                    base.evaluate()
                } finally {
                    try {
                        scenario.close()
                    } catch (untracked: NullPointerException) {
                        if (!newIntentDelivered) throw untracked
                        finishLiveActivities()
                    }
                }
            }
        }

    /** Finishes every live activity (the untracked-scenario teardown, see [newIntentDelivered]). */
    private fun finishLiveActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)) {
                monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        instrumentation.waitForIdleSync()
    }

    /**
     * The fixture engines' control URLs from the instrumentation
     * arguments; an engine whose argument is absent maps to nothing.
     */
    fun controlUrls(): Map<FixtureEngine, String> = FixtureEngine.entries
        .mapNotNull { engine ->
            InstrumentationRegistry.getArguments()
                ?.getString(engine.arg)?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { engine to it }
        }
        .toMap()

    /** The control URL of one engine (null when the rig is absent). */
    fun controlUrl(engine: FixtureEngine): String? = controlUrls()[engine]

    /**
     * Mints a fresh single-use pairing URL from a control URL — the
     * tests that pair through the visible pairing UI
     * ([LiveEngineRoundTripTest]) instead of the launch extra.
     */
    fun mint(controlUrl: String): String? {
        val base = controlUrl.trim().trimEnd('/')
        val body = fetch("$base/mint") ?: return null
        return runCatching { JSONObject(body).getString("pairUrl") }.getOrNull()
    }

    /**
     * Mints a fresh `roboco-tailcat:` invite (`GET /mint-tailcat`,
     * android-next#02) from the [FixtureEngine.Tailcat] control URL.
     */
    fun mintTailcat(controlUrl: String): String? {
        val base = controlUrl.trim().trimEnd('/')
        val body = fetch("$base/mint-tailcat") ?: return null
        return runCatching { JSONObject(body).getString("invite") }.getOrNull()
    }

    // Rooms (ADR 0011, ticket 04) ------------------------------------------

    /**
     * The rig's fixed engine ports, as `flow-tests.sh` adb-reverses them
     * (a room book dials members by address, so the phone needs the
     * device-side loopback ports — the control URLs alone don't carry
     * them). Absent when the rig is not running.
     */
    fun enginePort(engine: FixtureEngine): Int? =
        InstrumentationRegistry.getArguments()
            ?.getString(engine.portArg)?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()

    /**
     * Mints a room on the engine behind `controlUrl`: a fresh secret, the
     * given members (label → device-side address), the engine joins it,
     * and the invite string comes back. The secret never logs — it lives
     * inside the invite, which only the join consumes.
     */
    fun roomInvite(controlUrl: String, name: String, members: List<Pair<String, String>>): String? {
        val base = controlUrl.trim().trimEnd('/')
        val payload = JSONObject()
            .put("name", name)
            .put(
                "members",
                JSONArray().apply {
                    for ((label, address) in members) {
                        put(JSONObject().put("label", label).put("address", address))
                    }
                },
            )
            .toString()
        val body = post("$base/room-invite", payload) ?: return null
        return runCatching { JSONObject(body).getString("invite") }.getOrNull()
    }

    /** Joins the engine behind `controlUrl` to an existing room from its
     * invite; returns the room id (or null when the rig refused). */
    fun roomJoin(controlUrl: String, invite: String): String? {
        val base = controlUrl.trim().trimEnd('/')
        val payload = JSONObject().put("invite", invite).toString()
        val body = post("$base/room-join", payload) ?: return null
        return runCatching { JSONObject(body).getString("roomId") }.getOrNull()
    }

    /**
     * `POST path` with a JSON body, returning the reply body. Runs on the
     * IO dispatcher like [fetch]: the rule evaluates on the
     * instrumentation main thread.
     */
    private fun post(url: String, body: String): String? = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                    val output: OutputStream = connection.outputStream
                    output.write(body.toByteArray(Charsets.UTF_8))
                    output.flush()
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }

    /**
     * Starts a run on [chatId] from the ENGINE side (`GET /run?chat=`, the
     * fixture's QueueCommand path) — a run this phone did not send, which
     * the notification flow needs: the core never posts "Run finished" for
     * the phone's own send (crates/mobile-client/src/notify.rs).
     */
    fun run(controlUrl: String, chatId: String): Boolean =
        fetch("${controlUrl.trim().trimEnd('/')}/run?chat=$chatId") != null

    /**
     * One GET against a control endpoint (`/listener/stop`, …), returning
     * the body — the room flow's listener control. IO dispatcher like
     * [fetch].
     */
    fun fetchControl(controlUrl: String, path: String): String? =
        fetch("${controlUrl.trim().trimEnd('/')}/$path")

    /**
     * `GET /listener/<command>` (stop | start) with the rig contract
     * checked: the reply must arrive and be JSON. The fixture reports
     * what it actually did (`stopped`, `closed`, `started`), so a rig
     * that stops nothing is a loud, named failure here instead of a
     * mystery timeout at the join — CI 40256510722, where the room heal
     * flow's join minted BOTH members because the stop's ignored result
     * had no effect on the slow engine.
     */
    fun listenerControl(controlUrl: String, command: String): JSONObject {
        val body = fetchControl(controlUrl, "listener/$command")
            ?: error("the rig's /listener/$command failed (the fixture control is unreachable)")
        return runCatching { JSONObject(body) }
            .getOrElse { error("the rig's /listener/$command returned no JSON: $body") }
    }

    /**
     * `GET /mint`, returning the body. Runs on the IO dispatcher: the rule
     * evaluates on the instrumentation main thread, and a mint that blocks
     * there would trip the network-on-main guard before anything is up.
     */
    private fun fetch(url: String): String? = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
}

/** The rule's launch helper for [androidx.compose.ui.test.junit4.AndroidComposeTestRule]'s activity factory. */
fun FixtureRule.mountedActivity(): MainActivity {
    var activity: MainActivity? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity) { "FixtureRule did not launch MainActivity" }
}
