package sh.roboco.android.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.TranscriptView

/**
 * What fillers read (android-ui-parity #51): fetched once per refresh
 * on the controller's IO lane, so a filler never makes an FFI call of
 * its own. Everything it needs is in here; a filler that needs a new
 * core value adds it here and to [SessionCore] in its own ticket.
 */
data class ChromeInput(
    val chatId: String,
    val composer: ComposerState,
    val row: SessionRow?,
    /** The owning engine's view — for 73 (banner) and 79 (canWrite). */
    val engine: EngineView?,
    // Ticket 89 (chip menus): the chat's local config — the #664
    // config-first selection the menus' deferred loads re-read. Read once
    // per refresh next to the row, so the provider never calls FFI.
    val config: ChatConfig? = null,
)

/**
 * One field-group of [SessionChrome] owned by another ticket: 78
 * chips, 73 banner, 91 questions, 82 queue, 79 canWrite. The
 * controller calls every registered filler in list order during each
 * refresh (the list is composed by ticket 76), so none of those
 * tickets ever edits the controller.
 */
interface ChromeFiller {
    /**
     * Called once by the controller during `create`. Keep the
     * [invalidate] handle and call it when the filler's own local state
     * changed, to re-derive the chrome (iOS: `refresh()` after
     * `sendFailure`, `CoreSessionSource.swift:121-122`).
     */
    fun bind(invalidate: () -> Unit) {}

    /**
     * Fill [chrome] (the base fill plus every earlier filler's output)
     * and return the new value. Called on the IO lane; must be
     * pure-ish and fast — no FFI, no main-thread state.
     */
    fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome
}

/**
 * The failure sink for refresh and lifecycle FFI errors: production
 * logs `Log.w("RobocoSession", …)`. Plain-JVM tests swap this — the
 * stub `android.util.Log` throws "not mocked" outside Robolectric.
 */
internal var sessionLogW: (String, Throwable?) -> Unit = { message, error ->
    if (error == null) {
        android.util.Log.w("RobocoSession", message)
    } else {
        android.util.Log.w("RobocoSession", message, error)
    }
}

/**
 * The state holder behind one open Session screen (android-ui-parity
 * #51) — the port of `CoreSessionSource` (`ios:Session/
 * CoreSessionSource.swift`). It re-derives [chrome] whenever the core
 * says this chat, the workspace, an engine or connectivity changed,
 * and owns the attach / detach / reattach lifecycle and `markSeen`.
 *
 * Threading (upstream bugs #609 and #621 are the two traps):
 * - every FFI call runs on [io] — a **per-controller serial IO lane**
 *   (default `Dispatchers.IO.limitedParallelism(1)`) — keeping attach →
 *   `setViewAttached(true)` → `markSeen` → … → `detach` in call order;
 * - core events reach it through [SessionEventHub.dispatchOnMain] (one
 *   named main-thread handler, never synchronous FFI on main) and only
 *   `trySend` a conflated token, so a burst of N events costs one
 *   refresh;
 * - the **first** refresh runs synchronously on the factory thread, so
 *   the screen has a title and subtitle on its first frame (iOS
 *   `CoreSessionSource.init` → `refresh()`), and `create` refuses to
 *   run on main;
 * - [chrome] is published from the IO lane; `MutableStateFlow` is
 *   thread-safe and skips equal values, and Compose collects on main.
 *
 * The *timing* of [attach]/[markSeen]/[reattach]/[detach]/[close]
 * belongs to the screen tickets: 75 (first composition), 85 (pop
 * commit, re-appear) and 76 (failable creation in the route factory).
 *
 * Fillers: this ticket fills title, subtitle, running, canSteer,
 * placeholder, uploadProgress and error ([baseChrome]); each other
 * field is filled by its ticket's [ChromeFiller] in list order.
 */
class SessionController private constructor(
    private val core: SessionCore,
    private val hub: SessionEventHub,
    @PublishedApi internal val fillers: List<ChromeFiller>,
    private val io: CoroutineDispatcher,
    private val onSeen: ((String) -> Unit)? = null,
) {
    private val job: CompletableJob = SupervisorJob()
    private val scope = CoroutineScope(job + io)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val _chrome = MutableStateFlow(SessionChrome())

    /** The current chrome; equal writes are skipped (equality drives recomposition). */
    val chrome: StateFlow<SessionChrome> = _chrome.asStateFlow()

    /**
     * `composer.host.deviceId` as of the last refresh (iOS `hostDevice`,
     * `CoreSessionSource.swift:42`) — cached for the model menus,
     * `@` file search and image loading (66/86/89).
     */
    @Volatile
    var hostDeviceId: String = ""
        private set

    /**
     * `SessionRow.pinned` as of the last refresh (iOS
     * `app.session(chatId)?.pinned ?? false`). Ticket 80's ⋯ menu reads
     * it on main; no lock needed — a stale read only until the next
     * refresh.
     */
    @Volatile
    var pinned: Boolean = false
        private set

    /**
     * The owning engine's reported version as of the last refresh
     * (`EngineView.version`), `null` while unknown. android-next #06's ⋯
     * reads it on main to offer the side-chat rows only to engines that
     * serve them; a stale read only until the next refresh.
     */
    @Volatile
    var engineVersion: String? = null
        private set

    /**
     * The chat's workspace row as of the last refresh (`null` before the
     * workspace knows it). android-next #06's New Side Chat seeds its draft
     * from it on main, with no FFI call.
     */
    @Volatile
    var sessionRow: SessionRow? = null
        private set

    /**
     * `SessionRow.harness` as of the last refresh — the session agent's
     * wire harness id. android-next#05's ⋯ menu and usage chip read it
     * on main; no lock needed — a stale read only until the next
     * refresh.
     */
    @Volatile
    var harness: String? = null
        private set

    private val closed = AtomicBoolean(false)

    /** The view [attach] bound; a second attach of the same instance is a no-op. Cleared again when the attach FFI failed, so a retry is possible. */
    private val attachedView = AtomicReference<TranscriptView?>(null)

    /** The hub unregister action, installed by [create] (factory thread) and invoked by [close]/[dispose] (main). */
    @Volatile
    private var unregister: (() -> Unit)? = null

    /** The session's scoped chat id (plain string; compare like-for-like with events). */
    val chatId: String
        get() = core.chatId

    /**
     * This route's own instance of a registered filler (73's
     * `StatusBanner`, 89's `SessionChipMenus`), or `null` when none is
     * registered.
     */
    inline fun <reified T : ChromeFiller> filler(): T? = fillers.filterIsInstance<T>().firstOrNull()

    // ── lifecycle (the FFI order is the table in the ticket; when each
    //    runs is 75/85/76) ────────────────────────────────────────────

    /**
     * `TranscriptView.attach(client, chatId)` first (its `false` return
     * is logged, otherwise ignored — iOS `CoreSessionSource.swift:26-29`),
     * then `setViewAttached(true)`. The second call with the same view
     * instance is a no-op. On the IO lane.
     */
    fun attach(view: TranscriptView) {
        if (closed.get()) return
        if (attachedView.get() === view) return
        attachedView.set(view)
        scope.launch {
            try {
                if (!core.attachTranscript(view)) {
                    logFailure("transcript attach returned false for ${core.chatId}", null)
                }
                core.setViewAttached(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // The bind failed: forget the view, so a later attach of
                // the same instance retries instead of no-op'ing.
                attachedView.compareAndSet(view, null)
                logFailure("attach failed", t)
            }
        }
    }

    /**
     * `client.markSeen(chatId)` — through the client, not the handle
     * (`AppModel.swift:519`). On the IO lane. The notification-cancel seam
     * (android-app#28) rides along: opening a chat cancels its
     * notification ("next to #51's markSeen on the session screen's first
     * composition", per the ticket) — tickets 75/76 bind [onSeen] to the
     * app's `NotificationCenter.cancelChatFor`; the default is a no-op so
     * this controller's own tests are untouched.
     */
    fun markSeen() {
        if (closed.get()) return
        onSeen?.invoke(core.chatId)
        scope.launch {
            try {
                // 79: an offline engine would only fail (android-app#06
                // refuses the RPC within 50 ms), so the mark is skipped
                // silently — no toast, no banner, no log. The check runs
                // here, at execution time on this lane, so it sees the
                // chrome of the latest refresh (which runs on the same
                // lane). The notification cancel above stays: it is local.
                if (_chrome.value.canWrite) core.markSeen()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logFailure("markSeen failed", t)
            }
        }
    }

    /** `handle.setViewAttached(true)` — the screen is showing again (`CoreSessionSource.swift:35-37`). On the IO lane. */
    fun reattach() {
        if (closed.get()) return
        scope.launch {
            try {
                core.setViewAttached(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logFailure("reattach failed", t)
            }
        }
    }

    /** `handle.setViewAttached(false)` (`CoreSessionSource.swift:31-33`). On the IO lane. */
    fun detach() {
        if (closed.get()) return
        scope.launch {
            try {
                core.setViewAttached(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logFailure("detach failed", t)
            }
        }
    }

    /**
     * Schedule a refresh (fillers call this when their own local state
     * changed). Coalesced: N calls between drains cost one refresh.
     */
    fun invalidate() {
        if (closed.get()) return
        trigger.trySend(Unit)
    }

    /**
     * A committed pop (`SessionViewController.swift:307-315`):
     * `setViewAttached(false)`, then `client.markSeen(chatId)`, both on
     * the IO lane and in that order; then unregister from the hub and
     * stop refreshing. Idempotent; calls after it are ignored (no FFI,
     * no crash).
     *
     * Ticket 108: the IO job is registered with
     * [SessionDetachFence.Default] under the chat id, so the next attach
     * of the same chat (the 840 dp carry-over, which detaches the old
     * copy before attaching the new one) can join it and keep the order
     * on the shared warm handle. `job.complete()` does not cancel
     * children, so the job always runs.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregister?.invoke()
        unregister = null
        trigger.close()
        val closeJob = scope.launch {
            try {
                core.setViewAttached(false)
                // 79: the same silent skip as markSeen() on the way out.
                // Never gate setViewAttached itself — it is a local
                // subscription switch and must work offline (85's rule);
                // there is no catch-up mark on a later reconnect either.
                if (_chrome.value.canWrite) core.markSeen()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logFailure("close failed", t)
            }
        }
        SessionDetachFence.Default.track(core.chatId, closeJob)
        // The pop also counts as "seen": the notification goes too.
        onSeen?.invoke(core.chatId)
        // Let the final calls run, then the scope ends: later launches
        // on this scope never execute.
        job.complete()
    }

    /**
     * No FFI at all: unregister and stop. For a route that was never
     * pushed (ticket 32's `SessionRoute.discard()` — iOS has only the
     * deinit). Idempotent; calls after it are ignored.
     */
    fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        unregister?.invoke()
        unregister = null
        trigger.close()
        job.cancel()
    }

    // ── refresh ──────────────────────────────────────────────────────

    /**
     * Runs on the factory thread once (inside [create]), then only on
     * [io]. Pulls composer + row + engine, updates [hostDeviceId] and
     * [pinned], applies [baseChrome] and every filler in order, and
     * publishes (a `StateFlow` skips equal values). Any `Throwable`
     * from FFI except `CancellationException` is logged and the
     * previous chrome is kept; the loop keeps running.
     */
    private fun refreshNow() {
        try {
            val c = core.composer()
            val row = core.sessionRow()
            val engine = core.engine()
            hostDeviceId = c.host.deviceId
            pinned = row?.pinned == true
            engineVersion = engine?.version
            sessionRow = row
            harness = row?.harness
            // Ticket 89: the local config beside the row — the menus'
            // deferred loads re-read it at load time (#664), so it rides
            // along on every refresh.
            val input = ChromeInput(
                chatId = core.chatId,
                composer = c,
                row = row,
                engine = engine,
                config = core.sessionConfig(),
            )
            var next = baseChrome(c, row)
            for (filler in fillers) next = filler.fill(next, input)
            _chrome.value = next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logFailure("refresh failed", t)
        }
    }

    private fun logFailure(message: String, error: Throwable?) {
        sessionLogW(message, error)
    }

    companion object {
        /**
         * OFF main only — throws `IllegalStateException` on main. Ticket
         * 76 calls this inside `SessionRouteFactory.open`, on a
         * background dispatcher. Order:
         * 1. main-thread check;
         * 2. construct (no FFI);
         * 3. `fillers.bind(::invalidate)`;
         * 4. register with the hub **before** the first pull, so an
         *    event that lands during the pull leaves a pending token
         *    and is not lost;
         * 5. one synchronous refresh on the calling thread (first-frame
         *    title/subtitle);
         * 6. start the drain loop on [io].
         */
        fun create(
            core: SessionCore,
            hub: SessionEventHub,
            fillers: List<ChromeFiller> = emptyList(),
            io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
            isMainThread: () -> Boolean = ::defaultIsMainThread,
            onSeen: ((String) -> Unit)? = null,
        ): SessionController {
            check(!isMainThread()) { "SessionController.create on main" }
            val controller = SessionController(core, hub, fillers, io, onSeen)
            controller.fillers.forEach { it.bind(controller::invalidate) }
            controller.unregister = hub.register(core.chatId) { controller.trigger.trySend(Unit) }
            controller.refreshNow()
            controller.scope.launch {
                for (token in controller.trigger) controller.refreshNow()
            }
            return controller
        }
    }
}

/**
 * Robust on every platform: a real main looper answers `isCurrentThread`;
 * the JVM unit-test stub throws (or returns null), which `runCatching`
 * folds to `false`.
 */
internal fun defaultIsMainThread(): Boolean =
    runCatching { android.os.Looper.getMainLooper()?.isCurrentThread == true }.getOrDefault(false)
