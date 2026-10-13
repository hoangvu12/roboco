package sh.roboco.android.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import sh.roboco.android.core.TextEngine
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRoute
import sh.roboco.android.shell.SessionRouteFactory
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.TextSystem

/**
 * The real [SessionRouteFactory] (android-ui-parity #76) — the port of
 * `SessionViewController.init?` + `AppModel.sessionSource`
 * (`ios:App/AppModel.swift:523-532`): the core open and the controller
 * creation complete **before** any navigation call; the navigation call
 * receives a ready route or `null`. A failure is only logged (the iOS
 * `NSLog` text) — the user sees the shell's toast, never the error
 * text, and no failure UI exists anywhere.
 *
 * Runs OFF main (ticket 32's `openSessionRoute` calls it on
 * `Dispatchers.IO`): the open reads the per-chat cache seed from disk
 * and `SessionController.create` then makes three synchronous FFI
 * pulls. No pre-checks — the core alone decides (an offline or parked
 * engine's chat opens; a just-created chat opens before the workspace
 * knows it).
 */
class CoreSessionRouteFactory(
    private val opener: SessionOpener,
    private val hub: SessionEventHub,
    private val fillers: ChromeFillerRegistry,
    private val isMainThread: () -> Boolean = ::defaultIsMainThread,
    /** Production logs `Log.w("RobocoSession", ...)`; plain-JVM tests swap this (the stub `android.util.Log` throws). */
    private val logW: (String, Throwable?) -> Unit = { message, error ->
        android.util.Log.w("RobocoSession", message, error)
    },
    /** A fresh serial IO lane per controller (ticket 51's default); tests inject a `StandardTestDispatcher`. */
    private val newIo: () -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(1) },
    /** iOS `openedAt` (75's `-measureopen` span): read right after the main-thread check, before the open. */
    private val now: () -> Long = System::nanoTime,
    /** Ticket 60's shared engine, read here — off main; never closed (it lives for the process). */
    private val text: () -> TextSystem = { TextEngine.shared },
    /**
     * android-app#28's cancel-on-open seam: [installSessionRoutes] binds
     * it to `NotificationCenter.cancelChatFor`, so opening a chat (the
     * session screen's first composition) or closing it (a committed
     * pop) cancels its notification. Null = no cancel (tests).
     */
    private val onSeen: ((String) -> Unit)? = null,
) : SessionRouteFactory {

    override fun open(chatId: String): SessionRoute? {
        // First statement, so a main-thread call never reaches the open.
        check(!isMainThread()) { "SessionRouteFactory.open on main" }
        val openedAt = now() // iOS openedAt: before the synchronous core open
        val core = try {
            opener.open(chatId) ?: return null // iOS: guard let client
        } catch (e: Exception) {
            // iOS: NSLog("open session failed: \(error)") — the user
            // never sees the error text, only the shell's toast.
            logW("open session failed: $e", e)
            return null
        }
        // Read before create, so a throw here leaks no controller. A
        // Throwable from `text()` or `create` propagates to ticket 32's
        // `openSessionRoute`, which logs and returns null — nothing must
        // be released (iOS never calls closeSession; the core's warm
        // cache evicts on its own).
        val ts = text()
        val controller = SessionController.create(
            core = core,
            hub = hub,
            fillers = fillers.create(core),
            io = newIo(),
            isMainThread = isMainThread,
            onSeen = onSeen,
        )
        // The transcript factory is only *stored* here — 61's production
        // constructor is main-thread only, so the first main-thread read
        // (Content, or 77's prepareArrival) creates it.
        return SessionScreenRoute(
            controller = controller,
            core = core,
            state = SessionScreenState(core.chatId, openedAt) { TranscriptState(ts) },
            text = ts,
        )
    }
}

/**
 * Called once from `RobocoApplication.onCreate`: swaps ticket 32's
 * `SessionRouteFactory.Unavailable` for the real one. After this, every
 * failed open is the core refusing (or the client being absent) — never
 * a missing registration.
 */
fun installSessionRoutes(
    hooks: RouterHooks,
    opener: SessionOpener,
    hub: SessionEventHub,
    fillers: ChromeFillerRegistry,
    onSeen: ((String) -> Unit)? = null,
) {
    hooks.sessionRoutes = CoreSessionRouteFactory(
        opener = opener,
        hub = hub,
        fillers = fillers,
        onSeen = onSeen,
    )
}
