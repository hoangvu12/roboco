package sh.roboco.android.browser

import sh.roboco.android.shell.NavRoute

/**
 * The in-app browser entry's route (agent-previews ticket 05): pushed by
 * the preview panel's Open, loading [url] — `http://{hostname}:7332`, the
 * in-app client proxy's port — in a WebView.
 *
 * Resolution is the WebView's own: Android WebView is Chromium, and
 * Chromium synthesizes `*.localhost` → `127.0.0.1`/`::1` in-process
 * before any system resolver, so the URL reaches the in-app proxy with
 * zero OS configuration and a real socket (WebSockets/HMR work; no
 * `shouldInterceptRequest`, which cannot carry them). Cleartext HTTP to
 * loopback is permitted by the app's `network_security_config.xml`
 * base-config.
 *
 * `hidesBottomChrome = true` like the Session screen: the browser is a
 * full-screen surface over a session, not a tab-level list page.
 *
 * [title] is the preview row's name (the panel's service title), shown
 * in the header until the page reports its own `<title>`.
 */
class BrowserRoute(
    val url: String,
    val title: String,
) : NavRoute {
    /** Full-screen: the tab pill and accessory hide while the browser is on top. */
    override val hidesBottomChrome: Boolean get() = true

    override val paneTitle: String = title
}
