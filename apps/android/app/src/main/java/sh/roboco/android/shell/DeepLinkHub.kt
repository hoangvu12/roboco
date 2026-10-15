package sh.roboco.android.shell

import android.util.Log

/**
 * Test seam over `android.util.Log.i` — the plain-JVM tests replace it
 * because `android.util.Log` throws "not mocked" there. The hub is the
 * only `DeepLinks`-tagged logger in this file; the ignored-link log
 * lives at the `MainActivity` call site.
 */
internal var deepLinksLog: (String) -> Unit = { message -> Log.i("DeepLinks", message) }

/**
 * The deep-link hold-and-dispatch hub (android-ui-parity #36): one slot
 * that holds links until the shell can take them — the shape of iOS's
 * single `pendingChat` slot (`PushNotifications.swift:27-30,184-193`),
 * generalised from notifications to every inbound link.
 *
 * Main thread only (Compose callbacks, `onCreate`/`onNewIntent` already
 * run there). Process-wide — it lives on `RobocoApplication`, so a link
 * held at cold start survives activity recreation:
 *
 * - [submit] routes a [DeepLink.Pair] straight to the registered pairing
 *   handler (held in a one-slot `pendingPair` until 139 registers) and
 *   **honours it on every root** — the pairing root is where
 *   `roboco://pair` matters most. Any other link is dropped while the
 *   pairing root is on screen (iOS ignores `-route` on the SignIn root),
 *   dispatched at once when a router is attached, otherwise held in the
 *   one `pending` slot — the **latest submit wins**.
 * - [showingPairingRoot] keys on the root the cross-fade is heading
 *   **to**, not on composition lifetime: during the 350 ms fade the old
 *   shell is still composed and attached, but links are already dropped.
 *   `pairingRoot` beats an attached router.
 * - [attachRouter] attaches after the shell's first frame (see
 *   `DeepLinkRouterEffect`) and dispatches a held link then, unless the
 *   pairing root took over in between.
 * - [detachRouter] is identity-checked: a stale detach from an old
 *   composition must not clear a newer router (the one-frame overlap of
 *   the phone/split switch).
 * - [registerPairingHandler] delivers a held pair link on registration
 *   and returns the unregister lambda ticket 139 calls in `onDispose`.
 *
 * [knownChatIds] must return an in-memory list (`RobocoApplication`'s
 * `workspaceFeed.snapshot`), never a blocking FFI call on the main
 * thread — and it is only ever read for an `OpenChat` with a raw id.
 */
class DeepLinkHub(
    private val knownChatIds: () -> Collection<String>,
) {

    private var router: AppRouter? = null
    private var pairingRoot: Boolean = false
    private var pending: DeepLink? = null
    private var pairingHandler: ((String) -> Unit)? = null
    private var pendingPair: String? = null

    /** One inbound link: dispatch, hold or drop, per the state machine. */
    fun submit(link: DeepLink) {
        if (link is DeepLink.Pair) {
            val handler = pairingHandler
            if (handler != null) handler(link.link) else pendingPair = link.link
            return
        }
        if (pairingRoot) {
            // Never a Pair link: those are honoured on the pairing root.
            deepLinksLog("dropped $link: pairing root")
            return
        }
        val attached = router
        if (attached != null) dispatch(attached, link) else pending = link
    }

    /**
     * The root the cross-fade is heading to: `true` for the pairing root,
     * `false` for the shells and while `Loading` (no router attached
     * then, so cold-start links stay held).
     */
    fun showingPairingRoot(shown: Boolean) {
        pairingRoot = shown
        if (shown) pending = null
    }

    /**
     * The shell reports its [AppRouter] (after its first frame). A held
     * link is dispatched now — unless the pairing root took over first.
     */
    fun attachRouter(router: AppRouter) {
        this.router = router
        val held = pending
        if (held != null && !pairingRoot) {
            pending = null
            dispatch(router, held)
        }
    }

    /** Identity-checked: a stale detach must not clear a newer router. */
    fun detachRouter(router: AppRouter) {
        if (this.router === router) this.router = null
    }

    /**
     * Ticket 139's handler for `roboco://pair…` links (unregister in
     * `onDispose`). A held pair link is delivered right here — including
     * while the pairing root is on screen.
     */
    fun registerPairingHandler(handler: (String) -> Unit): () -> Unit {
        pairingHandler = handler
        val held = pendingPair
        if (held != null) {
            pendingPair = null
            handler(held)
        }
        return { if (pairingHandler === handler) pairingHandler = null }
    }

    /** `DeepLink` → `AppRouter` call, per the ticket's dispatch table. */
    private fun dispatch(router: AppRouter, link: DeepLink) {
        when (link) {
            is DeepLink.OpenChat -> router.openSession(
                // The known-id lookup runs only for a raw engine id.
                if (ScopedIds.isScoped(link.chatId)) link.chatId
                else ChatIds.resolve(link.chatId, knownChatIds()),
            )

            is DeepLink.NewSession -> router.presentNewSession(link.prompt)

            DeepLink.Settings -> router.showSettings()

            DeepLink.Search -> router.showSearch()

            is DeepLink.Pair -> {
                // Unreachable: submit() hands Pair links straight to the
                // pairing handler (they are never held in `pending`).
                // Kept for exhaustiveness; a Pair never reaches a router.
                pairingHandler?.invoke(link.link)
            }
        }
    }
}
