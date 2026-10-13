package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the new-session draft hands the chat it created (android-ui-parity
 * #32's hook type). Ticket 94's capture implements it; ticket 95's animator
 * casts it back. Opaque to the router.
 */
interface DraftHandoff

/**
 * One opened session, ready to push (android-ui-parity #32's hook type).
 * Ticket 76 implements it — it holds the core session handle / controller.
 */
interface SessionRoute {

    /** The chat this route opens (`SessionViewController(app:chatId:)`'s id). */
    val chatId: String

    /** The Session screen. NavStack key: `"session:$chatId"` (ticket 24's push). */
    @Composable
    fun Content()

    /**
     * Handoff arrival: hide transcript + bottom stack (iOS
     * `prepareArrival`, `SessionViewController.swift`'s alpha-0 state).
     * Plain state writes, main thread — ticket 77. The router calls it
     * **before** the push, so Compose never composes a visible frame first
     * (the Roboco adaptation of `performWithoutAnimation`).
     */
    fun prepareArrival()

    /** Show everything (iOS `finishArrival`) — ticket 77. */
    fun finishArrival()

    /** Never pushed (superseded, or the caller was cancelled): release what open() acquired. Main thread. */
    fun discard()
}

/** Opens sessions; ticket 76 registers the real one at startup. */
fun interface SessionRouteFactory {

    /**
     * Called **off the main thread**: reads the cache seed from disk and
     * makes synchronous FFI pulls (`client.openSession(chatId)`, which
     * throws `CoreException` NotFound — `AppModel.swift:523-532`'s failable
     * `SessionViewController(app:chatId:)`). Returns null when the core
     * can't open the chat.
     */
    fun open(chatId: String): SessionRoute?

    companion object {
        /** Until ticket 76 registers the real factory: every open fails (toast). */
        val Unavailable = SessionRouteFactory { null }
    }
}

/** Plays the draft-to-chat motion; ticket 95 registers the real one at startup. */
fun interface HandoffAnimator {

    /**
     * Main thread. [session] is already pushed (not animated) with its
     * content hidden, the accessory hidden and the sheet hidden. Play the
     * motion, then call [onFinished] once on the main thread.
     */
    fun run(handoff: DraftHandoff, session: SessionRoute, onFinished: () -> Unit)

    companion object {
        /** Until ticket 95 registers the real animator: reveal at once. */
        val Instant = HandoffAnimator { _, session, done -> session.finishArrival(); done() }
    }
}

/**
 * One instance per app root (ticket 35 creates it). Tickets 76 and 95
 * assign their implementations at startup; the fields are volatile so the
 * assignments are visible to the router's open coroutines.
 */
class RouterHooks {

    @Volatile
    var sessionRoutes: SessionRouteFactory = SessionRouteFactory.Unavailable

    @Volatile
    var handoffAnimator: HandoffAnimator = HandoffAnimator.Instant
}

/** The app root's [RouterHooks]; a throwaway instance outside one. */
val LocalRouterHooks = staticCompositionLocalOf { RouterHooks() }

/**
 * The off-main open used by both routers (#32, #106) and by ticket 41's
 * list taps. Call it from a main-confined coroutine. Never throws except
 * for cancellation: a factory `Throwable` (other than
 * [CancellationException]) is logged — `"open session failed: …"`, the iOS
 * `NSLog` text (`AppModel.swift:529`) — and treated as a failed open.
 *
 * If the caller is cancelled after the factory built a route, the route is
 * [SessionRoute.discard]ed here — on the caller's (main) dispatcher —
 * before the [CancellationException] propagates, so no controller leaks.
 * This replaces the `withContext(NonCancellable)` work-around: `NonCancellable`
 * alone does not help, because the result is still dispatched back to the
 * cancelled caller with a prompt-cancellation check.
 */
suspend fun RouterHooks.openSessionRoute(
    chatId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    log: (String, Throwable?) -> Unit = { _, _ -> },
): SessionRoute? {
    val built = AtomicReference<SessionRoute?>(null)
    try {
        val route = withContext(dispatcher) {
            try {
                sessionRoutes.open(chatId)?.also { built.set(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("open session failed", t)
                null
            }
        }
        built.set(null) // handed over to the caller
        return route
    } catch (e: CancellationException) {
        built.getAndSet(null)?.discard() // resumed on the caller's (main) dispatcher
        throw e
    }
}
