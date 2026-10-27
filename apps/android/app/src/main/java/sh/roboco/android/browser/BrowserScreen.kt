package sh.roboco.android.browser

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.ActivitySpinner
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LocalNavStack

/**
 * The in-app browser entry's test tags (ticket 22's rule).
 */
internal object BrowserTags {
    const val Screen = "browser-screen"
    const val Back = "browser-back"
    const val Reload = "browser-reload"
    const val Title = "browser-title"
    const val Loading = "browser-loading"
    const val Error = "browser-error"
    const val WebView = "browser-webview"
}

/**
 * The in-app browser page (agent-previews ticket 05): one [WebView] per
 * pushed entry, loading `http://{hostname}:7332` — the in-app client
 * proxy (ticket 04's module, embedded in the mobile core). No
 * `shouldInterceptRequest` and no `ProxyController`: the WebView's
 * Chromium synthesizes `*.localhost` to loopback in-process, so the page
 * and its WebSockets (HMR) travel real sockets through the proxy over the
 * engine session the phone already holds — LAN direct or the Tailcat
 * tunnel, whatever reaches the engine.
 *
 * The page state (loading, title, error) lives in [BrowserPage] — a
 * plain, testable holder the [WebViewClient] drives; this file only
 * draws. The proxy's listener is bound by the panel beneath this entry
 * (a NavStack composes every entry), so nothing here needs to start it —
 * the URL would not resolve otherwise.
 */
@Composable
fun BrowserScreen(
    route: BrowserRoute,
    modifier: Modifier = Modifier,
    webViewFactory: (Context) -> WebView = ::WebView,
) {
    val context = LocalContext.current
    val palette = RobocoTheme.palette
    val page = remember(route.url) { BrowserPage(route.title) }
    val webView = remember(route.url) {
        webViewFactory(context).apply {
            // A dev server's page needs script and storage (Vite HMR keeps
            // state in localStorage); everything else stays WebView
            // defaults. Cleartext to loopback is permitted platform-level.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = page.client
            loadUrl(route.url)
        }
    }
    val stack = LocalNavStack.current
    Column(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag(BrowserTags.Screen),
    ) {
        // The header: back, the page title, reload — pinned over the web
        // content, inset by the status bar.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(
                icon = RobocoIcons.ChevronBackward,
                contentDescription = PreviewsCopy.Back,
                onClick = { stack.pop() },
                modifier = Modifier.testTag(BrowserTags.Back),
                weight = SymbolWeight.Semibold,
            )
            BasicText(
                page.title,
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .testTag(BrowserTags.Title),
                style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(16f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GlassCircleButton(
                icon = RobocoIcons.ArrowUp,
                contentDescription = PreviewsCopy.Reload,
                onClick = { webView.reload() },
                modifier = Modifier.testTag(BrowserTags.Reload),
                weight = SymbolWeight.Semibold,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize().testTag(BrowserTags.WebView),
                onRelease = { it.stopLoading(); it.destroy() },
            )
            if (page.loading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.background)
                        .testTag(BrowserTags.Loading),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ActivitySpinner(palette.secondary)
                        Spacer(Modifier.height(10.dp))
                        BasicText(
                            PreviewsCopy.BrowserLoading,
                            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13f), palette.secondary),
                        )
                    }
                }
            } else {
                page.error?.let { error ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(palette.background)
                            .testTag(BrowserTags.Error),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            error,
                            Modifier.padding(horizontal = 24.dp),
                            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), palette.secondary),
                        )
                    }
                }
            }
        }
    }
}


/**
 * One browser page's observable state, driven by its [WebViewClient]:
 * `loading` until the first `onPageFinished` (or a main-frame error),
 * `title` the header shows (the route's title until the page reports its
 * own), `error` the main-frame failure note.
 *
 * The transitions are plain methods (the client delegates to them), so
 * the JVM tests drive the state machine without constructing Android's
 * package-private `WebResourceError`.
 */
@Stable
internal class BrowserPage(initialTitle: String) {

    var loading: Boolean by mutableStateOf(true)
        private set

    /** The header's title (the route's, then the page's own `<title>`). */
    var title: String by mutableStateOf(initialTitle)
        private set

    /** The main-frame failure note; null while healthy. */
    var error: String? by mutableStateOf(null)
        private set

    /** A navigation started: loading, the previous error cleared. */
    fun onPageStarted() {
        loading = true
        error = null
    }

    /** The navigation finished; `pageTitle` (the page's `<title>`) may be null/blank. */
    fun onPageFinished(pageTitle: String?) {
        loading = false
        pageTitle?.takeUnless { it.isBlank() }?.let { title = it }
    }

    /**
     * A resource failed. Sub-frame and sub-resource failures are the
     * page's own business; only a main-frame failure is the entry's state
     * (with the note, or the fallback when the error carries no text).
     */
    fun onReceivedError(mainFrame: Boolean, description: CharSequence?) {
        if (!mainFrame) return
        loading = false
        error = description?.toString() ?: PreviewsCopy.LoadFailed
    }

    /** The client that drives this page from the WebView's callbacks. */
    val client: WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            this@BrowserPage.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            this@BrowserPage.onPageFinished(view.title)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            this@BrowserPage.onReceivedError(request.isForMainFrame, error.description)
        }
    }
}

