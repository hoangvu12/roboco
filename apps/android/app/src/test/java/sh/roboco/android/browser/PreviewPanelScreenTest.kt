package sh.roboco.android.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import uniffi.roboco_core.PreviewPanelPhase
import uniffi.roboco_core.PreviewPanelState
import uniffi.roboco_core.PreviewServiceRow

/**
 * The preview panel page (agent-previews ticket 05) through the real
 * [PreviewPanelScreen]: the loading, live (rows + the desktop's
 * vocabulary), empty, reconnect and unsupported states over a fake
 * [PreviewsCore], the inline error footer, and the row's Open pushing
 * [BrowserRoute] on the stack (the NavStack wiring). Robolectric, the
 * pushed-route shape of `AgentAccountsScreenTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class PreviewPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ the fake

    /**
     * The fake core: hands the panel out and holds the listener the model
     * registered, so each test drives the state callback like the Rust
     * side does.
     */
    private class FakePreviewsCore : PreviewsCore {
        var listener: uniffi.roboco_core.PreviewPanelListener? = null
        var current = PreviewPanelState(
            phase = PreviewPanelPhase.LOADING,
            engineId = "engine-1",
            services = emptyList(),
            error = null,
        )

        override suspend fun watch(
            chatId: String,
            listener: uniffi.roboco_core.PreviewPanelListener,
        ): PanelWatch = object : PanelWatch {
            init {
                this@FakePreviewsCore.listener = listener
            }

            override fun state(): PreviewPanelState = current
            override fun stop() {}
        }

        override suspend fun proxyStatus() =
            uniffi.roboco_core.PreviewProxyStatus(port = null, error = null)

        override fun setProxyWatching(engineId: String, watching: Boolean) {}
    }

    private fun service(id: String, name: String, port: UShort) = PreviewServiceRow(
        id = id,
        name = name,
        hostname = "studio-$id.localhost",
        port = port,
        deviceName = "Studio",
        url = "http://studio-$id.localhost:7332",
    )

    private fun state(
        phase: PreviewPanelPhase,
        services: List<PreviewServiceRow> = emptyList(),
        error: String? = null,
    ) = PreviewPanelState(phase = phase, engineId = "engine-1", services = services, error = error)

    // ------------------------------------------------------------ mounting

    private lateinit var stack: NavStack
    private val core = FakePreviewsCore()

    private fun mount(route: PreviewPanelRoute = PreviewPanelRoute("engine:v1:chat-1", "Fix the deploy script")) {
        stack = NavStack(TestRoot)
        stack.push(route, animated = false)
        val pushed = stack.entries.last()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides pushed,
                ) {
                    NavStackHost(stack = stack, active = true) { entry ->
                        when (entry.route) {
                            is PreviewPanelRoute -> PreviewPanelScreen(
                                route = entry.route as PreviewPanelRoute,
                                core = core,
                                scope = kotlinx.coroutines.CoroutineScope(
                                    Dispatchers.Main.immediate,
                                ),
                                io = Dispatchers.Main.immediate,
                            )
                            is BrowserRoute -> BrowserScreen(entry.route as BrowserRoute)
                            else -> Unit
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private object TestRoot : sh.roboco.android.shell.NavRoute

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun deliver(state: PreviewPanelState) {
        core.current = state
        core.listener?.onState(state)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- states

    @Test
    fun loadingShowsTheLookingForServersNote() {
        mount()
        compose.onNodeWithTag(PanelTags.Screen).assertExists()
        assertTrue("the loading note", textExists(PreviewsCopy.Loading))
    }

    @Test
    fun liveRowsCarryTheDesktopVocabularyAndOpenPushesTheBrowserEntry() {
        mount()
        deliver(
            state(
                PreviewPanelPhase.LIVE,
                services = listOf(
                    service("srv-1", "Vite", 5173u),
                    service("srv-2", "API", 8787u),
                ),
            ),
        )
        assertTrue("the remote subtitle", textExists(PreviewsCopy.RunningOnYourDevice))
        compose.onNodeWithTag(PanelTags.row("srv-1")).assertExists()
        compose.onNodeWithTag(PanelTags.row("srv-2")).assertExists()
        assertTrue("the row title", textExists("Vite"))
        assertTrue("the row subtitle", textExists("Studio \u00b7 localhost:5173"))
        assertTrue("the second subtitle", textExists("Studio \u00b7 localhost:8787"))
        compose.onNodeWithTag(PanelTags.row("srv-1")).assertHasClickAction().performClick()
        compose.waitForIdle()
        // The Open pushed the browser entry with the row's URL.
        val top = stack.entries.last().route
        assertTrue("the browser entry is on top", top is BrowserRoute)
        top as BrowserRoute
        assertEquals("http://studio-srv-1.localhost:7332", top.url)
        assertEquals("Vite", top.title)
        assertTrue("the browser page is composed", compose.onAllNodesWithTag(BrowserTags.Screen).fetchSemanticsNodes().isNotEmpty())
        // The panel beneath is still composed (the NavStack keeps every
        // entry): its rows stay in the tree.
        compose.onNodeWithTag(PanelTags.row("srv-2")).assertExists()
    }

    @Test
    fun liveWithNoRowsShowsTheHonestRemoteEmptyState() {
        mount()
        deliver(state(PreviewPanelPhase.LIVE))
        compose.onNodeWithTag(PanelTags.Empty).assertExists()
        assertTrue("the honest empty state", textExists(PreviewsCopy.EmptyRemote))
    }

    @Test
    fun reconnectingClearsTheRowsAndShowsTheReconnectNote() {
        mount()
        deliver(state(PreviewPanelPhase.LIVE, services = listOf(service("srv-1", "Vite", 5173u))))
        deliver(state(PreviewPanelPhase.RECONNECTING))
        assertTrue("the reconnect note", textExists(PreviewsCopy.Reconnecting))
        assertTrue("the rows cleared", compose.onAllNodesWithTag(PanelTags.row("srv-1")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun unsupportedShowsTheEngineTooOldNote() {
        mount()
        deliver(state(PreviewPanelPhase.UNSUPPORTED))
        assertTrue("the unsupported note", textExists(PreviewsCopy.Unsupported))
    }

    @Test
    fun theInlineErrorShowsAsTheFooter() {
        mount()
        deliver(state(PreviewPanelPhase.LIVE, error = "Remote previews could not listen on port 7332: busy"))
        compose.onNodeWithTag(PanelTags.Error).assertExists()
        assertTrue("the inline error", textExists("Remote previews could not listen on port 7332: busy"))
    }
}
