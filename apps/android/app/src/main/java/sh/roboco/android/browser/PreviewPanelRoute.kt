package sh.roboco.android.browser

import sh.roboco.android.shell.NavRoute

/**
 * The preview panel's route (agent-previews ticket 05) — the phone's half
 * of the session relay: the chat's `WatchPreviews` list rendered as rows
 * (name, port, device subtitle), each Open pushing [BrowserRoute] at
 * `http://{hostname}:7332` — the in-app client proxy's port, which the
 * WebView's Chromium resolves (`*.localhost` → loopback, in-process) with
 * no OS configuration.
 *
 * Pushed from the session screen's ⋯ menu ("Previews"); the slot decision
 * (NavStack entry, not a `session/SessionBottomStack` slot) is recorded in
 * the ticket: the bottom stack's slot set is fixed by its owner tickets
 * (73 pill, 82 queue, 90/91 questions, 78 composer) and the WebView
 * surface needs a full-screen NavStack entry anyway, so the panel lives
 * on the same stack, one push beneath the browser entry — which also
 * keeps the proxy's watcher held while a preview is viewed (a NavStack
 * composes every entry, not just the top).
 *
 * [chatId] is the engine-scoped id the session screen holds;
 * [chatTitle] is the session's title, shown as the panel's context line.
 * `hidesBottomChrome` stays false: like the Accounts & Usage page pushed
 * from the same menu, the panel is a list page that keeps the tab bar
 * (only the Session screen and the [BrowserRoute] hide it).
 */
class PreviewPanelRoute(
    val chatId: String,
    val chatTitle: String,
) : NavRoute {
    override val paneTitle: String = PreviewsCopy.pageTitle(chatTitle)
}
