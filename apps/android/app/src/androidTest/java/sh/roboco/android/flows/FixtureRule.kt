package sh.roboco.android.flows

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 * `apps/android/scripts/flow-tests.sh`).
 */
enum class FixtureEngine(val arg: String) {
    /** The seeded workspace: links, tables, code, thinking in the replies. */
    Main("fixtureMain"),
    /** A paced, long streaming reply (queue/stop/scroll/notification). */
    Slow("fixtureSlow"),
    /** A run that asks the user questions (the QuestionPanel). */
    Question("fixtureQuestion"),
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
                    scenario.close()
                }
            }
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
