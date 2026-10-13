package sh.roboco.android.session

import androidx.annotation.MainThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import uniffi.roboco_core.ClientEvent

/** What a registered controller asks for: one coalesced refresh. */
fun interface SessionEventListener {
    fun requestRefresh()
}

/**
 * App-scoped (one instance) fan-out from core events to session
 * controllers (android-ui-parity #51) — the port of `AppModel`'s
 * `sessionObservers`/`observers` hop (`ios:App/AppModel.swift:341-355`
 * and `:650-659`).
 *
 * The app's `ClientListener` calls [onClientEvent] **from a Rust
 * runtime thread**; the hop to main goes through exactly one named
 * handler (`post(Runnable { dispatchOnMain(event) })`). Upstream bug
 * #621: inside an anonymous `ClientListener`, a bare self-call to
 * its own `onEvent` re-posted every event to main forever — so the
 * entry point here is named [onClientEvent], and the wiring in the
 * listener must use the qualified `sessionEvents.onClientEvent(event)`,
 * never a bare `onEvent(…)` self-call.
 *
 * Controllers never pull in here: [dispatchOnMain] only calls
 * [SessionEventListener.requestRefresh], which is a conflated
 * `Channel.trySend` on the controller's side (upstream bug #609:
 * synchronous FFI on main froze the UI).
 *
 * Routing (`ClientEvent`, android-app#06/#07):
 * - `SessionChanged(chatId, revision)` / `ComposerChanged(chatId, revision)`:
 *   only controllers registered for that `chatId` (plain string
 *   compare — ids are scoped on both sides);
 * - `WorkspaceChanged`, `ConnectivityChanged`, `EnginesChanged`: every
 *   registered controller (the row's project/harness label, the
 *   connectivity posture and the engine device name all feed the
 *   chrome and the fillers 73/79);
 * - anything else (`Notice`, `EngineIdentityPinned`, …): nothing.
 */
class SessionEventHub(private val post: (Runnable) -> Unit = MainPoster) {

    private val byChat = ConcurrentHashMap<String, CopyOnWriteArraySet<SessionEventListener>>()
    private val all = CopyOnWriteArraySet<SessionEventListener>()

    /**
     * Any thread (in practice the Rust callback thread inside
     * `ClientListener.onEvent`). Only posts; never runs FFI or listener
     * code on the calling thread.
     */
    fun onClientEvent(event: ClientEvent) {
        post(Runnable { dispatchOnMain(event) })
    }

    /**
     * Any thread. Adds the listener to this chat's set **and** to the
     * global set (per-chat events and global events both reach it).
     * Returns the unregister action, which removes it from both.
     */
    fun register(chatId: String, listener: SessionEventListener): () -> Unit {
        byChat.getOrPut(chatId) { CopyOnWriteArraySet() }.add(listener)
        all.add(listener)
        return {
            byChat[chatId]?.remove(listener)
            all.remove(listener)
        }
    }

    /** Main thread (enforced by the single [post] hop). The routing table above. */
    @MainThread
    internal fun dispatchOnMain(event: ClientEvent) {
        when (event) {
            is ClientEvent.SessionChanged -> refreshChat(event.chatId)
            is ClientEvent.ComposerChanged -> refreshChat(event.chatId)
            is ClientEvent.WorkspaceChanged -> all.forEach { it.requestRefresh() }
            is ClientEvent.ConnectivityChanged -> all.forEach { it.requestRefresh() }
            ClientEvent.EnginesChanged -> all.forEach { it.requestRefresh() }
            else -> Unit // Notice, EngineIdentityPinned — no chrome impact
        }
    }

    private fun refreshChat(chatId: String) {
        byChat[chatId]?.forEach { it.requestRefresh() }
    }

    /** The production hop: one main-thread `Handler`, created once. */
    private object MainPoster : (Runnable) -> Unit {
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        override fun invoke(r: Runnable) {
            main.post(r)
        }
    }
}
