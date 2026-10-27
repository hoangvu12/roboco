package sh.roboco.android.flows

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity

/**
 * The preview panel + in-app browser flow (agent-previews ticket 05): the
 * phone's half of the session relay, end to end against the fixture
 * engine — pair, open the `chat-preview` chat, open the ⋯ menu's
 * "Previews" row, and let the panel's `WatchPreviews` subscription list
 * the fixture's seeded dev server. Tapping the row pushes the browser
 * entry, whose WebView loads `http://{hostname}:7332` — the in-app client
 * proxy — over the engine session the phone already holds.
 *
 * The load is proven at the **engine**: the seeded dev server counts its
 * requests (`GET /preview-hits` on the control endpoint), so a rising
 * count means the WebView really reached the engine's server through the
 * phone's 7332 proxy and the session relay — real sockets the whole way,
 * no `shouldInterceptRequest`.
 *
 * Runs on CI's emulator job (the flow rig + KVM); a local
 * `connectedDebugAndroidTest` without the rig skips it, like every flow
 * test.
 */
@RunWith(AndroidJUnit4::class)
class PreviewFlowTest {

    /**
     * The rig: paired at launch (the `pair` extra), routed straight into
     * the chat whose dev server the fixture seeds into its preview
     * catalog.
     */
    private val launch = FixtureRule(
        routeFor = { "chat:chat-preview" },
        enginesFor = { setOf(FixtureEngine.Main) },
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    @Test
    fun previewOpensTheRelayedDevServerInApp() {
        // 1. The routed chat is open: the composer is on screen.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. The ⋯ menu's Previews row opens the panel.
        compose.onNodeWithTag("session-more").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("glass-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-menu-previews").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("previews-panel").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. The panel lists the seeded dev server (the row tag carries
        //    the engine's stable service id, a fresh UUID, so match the
        //    tag prefix).
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodes(previewRow()).fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("preview-panel.png")

        // 4. Open pushes the browser entry; the WebView loads the preview
        //    through the in-app 7332 proxy. The hit count is read before
        //    the tap: the WebView often finishes its load before the
        //    browser screen's node is seen, so a baseline taken after it
        //    would already include the load this test is waiting for.
        val control = launch.controlUrl(FixtureEngine.Main)!!
        val before = previewHits(control)
        compose.onNode(previewRow()).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("browser-screen").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("preview-browser.png")

        // 5. The engine's dev server saw the request: the round trip is
        //    real (proxy → session relay → engine relay → dev server).
        compose.waitUntil(timeoutMillis = 30_000) {
            previewHits(control) > before
        }
        assertTrue("the relayed preview was actually loaded", previewHits(control) > before)
    }

    /** Any preview row: the tag prefix (`preview-row-<engine service id>`). */
    private fun previewRow(): SemanticsMatcher =
        SemanticsMatcher("a preview row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("preview-row-") == true
        }

    /** `GET /preview-hits` — the seeded dev server's request count. */
    private fun previewHits(controlUrl: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching {
                val base = controlUrl.trim().trimEnd('/')
                val connection = URL("$base/preview-hits").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
                ?.let { body -> runCatching { JSONObject(body).getInt("hits") }.getOrNull() }
                ?: 0
        }
    }
}
