package sh.roboco.android.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost

/**
 * The in-app browser entry (agent-previews ticket 05) on the JVM: the
 * [BrowserPage] state machine (the WebViewClient's transitions — started,
 * finished, a main-frame error vs a sub-frame one) as plain unit tests,
 * and the screen through Robolectric: the WebView is created with script
 * and storage enabled and loads the route's `http://{hostname}:7332` URL,
 * the header shows the title with the back and reload affordances, and
 * the entry hides the bottom chrome (the Session screen's rule) while
 * the panel beneath does not (a list page).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class BrowserScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------- the page state machine
    // The plain transitions (the WebViewClient delegates to them), driven
    // directly — Android's WebResourceError is package-private, so the
    // client layer is exercised through the screen mount below instead.

    @Test
    fun startedThenFinishedKeepsTheRoutesTitleWithoutAPageTitle() {
        val page = BrowserPage("Vite")
        assertTrue(page.loading)
        assertNull(page.error)
        page.onPageStarted()
        assertTrue(page.loading)
        page.onPageFinished(null)
        assertFalse(page.loading)
        // No <title> reported: the route's title stays.
        assertEquals("Vite", page.title)
    }

    @Test
    fun thePagesOwnTitleWinsWhenReported() {
        val page = BrowserPage("Vite")
        page.onPageFinished("fixture dev server")
        assertEquals("fixture dev server", page.title)
    }

    @Test
    fun aMainFrameFailureIsTheEntryStateASubFrameOneIsNot() {
        val page = BrowserPage("Vite")
        page.onReceivedError(mainFrame = false, description = "net::ERR_FAILED")
        assertTrue("a sub-frame failure is the page's own business", page.loading)
        assertNull(page.error)
        page.onReceivedError(mainFrame = true, description = "net::ERR_CONNECTION_REFUSED")
        assertFalse(page.loading)
        assertEquals("net::ERR_CONNECTION_REFUSED", page.error)
        // A later good start clears the error again.
        page.onPageStarted()
        assertNull(page.error)
    }

    @Test
    fun aMainFrameFailureWithoutADescriptionFallsBackToTheNote() {
        val page = BrowserPage("Vite")
        page.onReceivedError(mainFrame = true, description = null)
        assertEquals(PreviewsCopy.LoadFailed, page.error)
    }

    @Test
    fun theClientDelegatesTheWebViewsOwnTitle() {
        val page = BrowserPage("Vite")
        val view = WebView(RuntimeEnvironment.getApplication())
        page.client.onPageStarted(view, "http://studio.localhost:7332/", null)
        assertTrue(page.loading)
        page.client.onPageFinished(view, "http://studio.localhost:7332/")
        assertFalse(page.loading)
        // No <title> in Robolectric's WebView: the route's title stays.
        assertEquals("Vite", page.title)
    }

    // ------------------------------------------------ the screen (Robolectric)

    /** The WebViews the factory created, and the URLs they were asked to load. */
    private val created = mutableListOf<WebView>()
    private val loadedUrls = mutableListOf<String>()

    private fun mount(route: BrowserRoute) {
        val stack = NavStack(TestRoot)
        stack.push(route, animated = false)
        val pushed = stack.entries.last()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides pushed,
                ) {
                    NavStackHost(stack = stack, active = true) { entry ->
                        if (entry.route is BrowserRoute) {
                            BrowserScreen(
                                route = entry.route as BrowserRoute,
                                webViewFactory = { context: Context ->
                                    object : WebView(context) {
                                        override fun loadUrl(url: String) {
                                            loadedUrls += url
                                        }
                                    }.also { created += it }
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private object TestRoot : NavRoute

    @Test
    fun theWebViewLoadsTheProxyUrlWithScriptAndStorage() {
        mount(BrowserRoute(url = "http://studio.localhost:7332", title = "Vite"))
        compose.onNodeWithTag(BrowserTags.Screen).assertExists()
        compose.onNodeWithTag(BrowserTags.Back).assertExists()
        compose.onNodeWithTag(BrowserTags.Reload).assertExists()
        compose.onNodeWithText("Vite").assertExists()
        compose.onNodeWithTag(BrowserTags.Loading).assertExists()
        assertEquals(listOf("http://studio.localhost:7332"), loadedUrls)
        val view = created.single()
        assertTrue("script enabled (a dev server's page needs it)", view.settings.javaScriptEnabled)
        assertTrue("DOM storage enabled (HMR keeps state in localStorage)", view.settings.domStorageEnabled)
    }

    @Test
    fun theEntryHidesTheBottomChromeLikeTheSessionScreen() {
        val route = BrowserRoute(url = "http://studio.localhost:7332", title = "Vite")
        assertTrue("the browser is a full-screen surface", route.hidesBottomChrome)
        val panel = PreviewPanelRoute("engine:v1:chat-1", "Fix the deploy script")
        assertFalse("the panel is a list page", panel.hidesBottomChrome)
    }
}
