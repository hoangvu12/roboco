package sh.roboco.android.sessions

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.LocalRouterHooks
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.SessionRoute
import sh.roboco.android.shell.openSessionRoute

/**
 * The tap-to-open override — the Android shape of iOS's
 * `splitViewController as? SplitRootController` check
 * (`SessionListController.swift:213-215`): when the list is the tablet
 * sidebar, sessions open beside it, not on the list's own stack. Ticket
 * 105 provides `{ router.openSession(it) }` around the sidebar; `null`
 * (the default) is the phone behaviour: push on this stack, and on a
 * failed open `router.showSessionOpenError()`.
 */
fun interface SessionListOpener {
    fun open(chatId: String)
}

/** null = phone behaviour (push on this stack). */
val LocalSessionListOpener = staticCompositionLocalOf<SessionListOpener?> { null }

/**
 * Latest-tap-wins gate over the off-main session open (the Android shape
 * of `SessionViewController.init?`, `SessionViewController.swift:23-30`):
 * every tap bumps the generation; when an older tap's route finally
 * arrives, it is discarded instead of pushed, so two quick taps open the
 * second session, never the first.
 *
 * Main thread only (the generation is unsynchronized); the [open] block
 * may suspend on any thread.
 */
class SessionListOpenGate {

    private var generation = 0L

    /**
     * Opens [chatId]: runs [open] (off main — ticket 32's
     * [openSessionRoute] already moves the factory to `Dispatchers.IO`)
     * and hands the result to [apply] on the caller's dispatcher — unless
     * a newer tap superseded this one, in which case a built route is
     * [SessionRoute.discard]ed and [apply] never runs.
     */
    @MainThread
    fun open(
        scope: CoroutineScope,
        chatId: String,
        open: suspend (String) -> SessionRoute?,
        apply: (SessionRoute?) -> Unit,
    ) {
        val gen = ++generation
        scope.launch {
            val route = open(chatId)
            if (gen != generation) {
                route?.discard()
                return@launch
            }
            apply(route)
        }
    }
}

/**
 * The list's default tap handler (`SessionListController.openSession`,
 * `:203-213`): the tablet override when [LocalSessionListOpener] is
 * provided; otherwise the off-main open through ticket 32's
 * [openSessionRoute] with latest-tap-wins, pushing on the list's **own**
 * stack (`LocalNavStack` — the Sessions tab, a folder page or the Search
 * tab; no pop to root, no tab switch — `MainTabController.openSession`
 * does both and is reserved for new-session, deep links and
 * notifications). A failed open (`route == null`) is
 * `router.showSessionOpenError()` — the `"Couldn't open session. Try
 * again."` toast.
 *
 * Never opens on the main thread: the factory (ticket 76's
 * `SessionRouteFactory`, `Unavailable` until then — every tap shows the
 * error toast, the correct failure mode) runs on `Dispatchers.IO` inside
 * [openSessionRoute], which also discards a built route if the caller's
 * scope is cancelled. Requires a [LocalNavStack] (a `NavStackHost`); the
 * gallery and previews pass their own `onOpenSession`.
 */
@Composable
fun rememberSessionListOpener(): (String) -> Unit {
    val override = LocalSessionListOpener.current
    val nav = LocalNavStack.current
    val router = LocalAppRouter.current
    val hooks = LocalRouterHooks.current
    // Main-confined: the same dispatcher the gate's apply block and the
    // stack's push must run on.
    val scope = rememberCoroutineScope()
    return remember(override, nav, router, hooks, scope) {
        val gate = SessionListOpenGate()
        val tap: (String) -> Unit = { id: String ->
            if (override != null) {
                override.open(id)
            } else {
                gate.open(
                    scope,
                    id,
                    open = { hooks.openSessionRoute(it) },
                ) { route ->
                    if (route == null) {
                        router.showSessionOpenError()
                    } else {
                        // Ticket 76's SessionScreenRoute is both; every
                        // route the factory returns is a NavRoute by
                        // contract, hence the cast.
                        nav.push(route as NavRoute, animated = true)
                    }
                }
            }
        }
        tap
    }
}
