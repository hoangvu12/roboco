package sh.roboco.android.shell

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The tab-shell operations the phone router needs (android-ui-parity #32):
 * the port of `MainTabController.swift:136-221`'s UIKit surface, minus
 * everything UIKit owns outright. The real implementation is
 * [rememberPhoneShell]'s adapter over tickets 24/26/30/31/14/18 state;
 * tests use a recording fake. Main thread.
 */
interface PhoneShell {

    /** Programmatic tab selection — 26's [MainTabState.select], no haptic (not [MainTabState.onTabTap]). */
    fun selectTab(tab: TabId)

    /** True while a sheet is presented, hidden-for-handoff included (31's `SheetHostState.isPresenting`). */
    val isSheetPresented: Boolean

    /** Presents the New Session page sheet (31); `onCreated(chatId, handoff)` routes through the router. */
    fun presentNewSession(prompt: String?, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit)

    /** The presenter's `dismiss(animated:)` — or the pending handoff's `SheetHandoff.complete()`. */
    fun dismissSheet(animated: Boolean)

    /** 31's `hideForHandoff()`: the sheet vanishes, stays composed, inert. */
    fun hideSheetForHandoff()

    /** 18's `Alerts.dismissAll()` (+ 19's action sheet clear). */
    fun dismissAlerts()

    /** 24's `popToRootViewController` on the Sessions stack. */
    fun popSessionsToRoot(animated: Boolean)

    /** 24's `pushViewController` on the Sessions stack. */
    fun pushSession(route: SessionRoute, animated: Boolean)

    /** No-op in the adapter (30 derives visibility); kept so the order stays testable against iOS. */
    fun setAccessoryVisible(visible: Boolean, animated: Boolean)

    /** No-op in the adapter (30 re-derives); kept for the `showSessionOpenError` order. */
    fun syncAccessory()

    /** IME down + focus cleared (iOS `view.endEditing(true)`). */
    fun hideIme()

    /** Ticket 14's toast. */
    fun toast(text: String)
}

/**
 * The phone [AppRouter] (android-ui-parity #32): the port of
 * `ios:Shell/MainTabController.swift:136-221`, driving the tab shell
 * through [PhoneShell]. Every order and every `animated` flag is kept from
 * the Swift; the one Android addition is the generation counter — iOS
 * opens synchronously inside a failable init, Android opens off main
 * through [RouterHooks.openSessionRoute] and applies the result back on
 * the router's main-confined [scope], so a later router call supersedes a
 * pending open (its route is discarded, never pushed).
 *
 * `sessionDidAppear`/`sessionDidClose` are phone no-ops that forward to
 * [presence] — on iPhone `splitViewController` is nil, so the split-only
 * callbacks never fire (ticket 108 listens across the 840 dp switch).
 */
class PhoneAppRouter(
    private val shell: PhoneShell,
    private val hooks: RouterHooks,
    /** Main-confined; dies with the tab shell (the 840 dp switch, ticket 35/108). */
    private val scope: CoroutineScope,
    /** The factory runs here (reads the cache seed, synchronous FFI pulls — ticket 76), never on main. */
    private val openDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val presence: SessionPresenceListener? = null,
    private val log: (String, Throwable?) -> Unit = { m, t -> Log.w("AppRouter", m, t) },
) : AppRouter {

    /**
     * Monotonically increasing. Each open captures it; every router call
     * except `sessionDidAppear/Close` bumps it. The latest intent wins —
     * a pending open that resolves afterwards calls
     * [SessionRoute.discard] and does nothing else.
     */
    private var generation = 0L

    /** The shared open-and-apply: captures the generation, opens off main, applies on main. */
    private fun resolve(chatId: String, apply: (SessionRoute?) -> Unit) {
        val gen = ++generation
        scope.launch {
            val route = hooks.openSessionRoute(chatId, openDispatcher, log)
            if (gen != generation) {
                route?.discard()
                return@launch
            }
            apply(route)
        }
    }

    override fun openSession(chatId: String) {
        // `MainTabController.swift:211-221`: select Sessions first — a
        // failed open also lands on the tab — then the failable open, and
        // only on success: dismiss any presented sheet animated, dismiss
        // alerts, pop to root without animation, push animated. Steps
        // after the open run in this one main-thread turn, no frame
        // between the pop and the push.
        shell.selectTab(TabId.Sessions)
        resolve(chatId) { route ->
            if (route == null) return@resolve showSessionOpenError()
            if (shell.isSheetPresented) shell.dismissSheet(animated = true)
            shell.dismissAlerts()
            shell.popSessionsToRoot(animated = false)
            shell.pushSession(route, animated = true)
        }
    }

    override fun openSession(chatId: String, handoff: DraftHandoff?) {
        // `MainTabController.swift:161-191`: a nil handoff is the plain open.
        if (handoff == null) return openSession(chatId)
        shell.selectTab(TabId.Sessions)
        resolve(chatId) { route ->
            if (route == null) return@resolve showSessionOpenError()
            // `performWithoutAnimation`, in one main-thread turn: prepare
            // the arrival (Compose may draw the pushed route before a
            // later callback, so the route hides its content first —
            // prepareArrival is plain state writes), pop, push, accessory,
            // IME down (sending put the keyboard away; it slides down with
            // the handoff), sheet hidden — torn down only once the motion
            // is done, because a mid-animation dismissal is a heavy
            // compositor frame that swallowed it.
            route.prepareArrival()
            shell.popSessionsToRoot(animated = false)
            shell.pushSession(route, animated = false)
            shell.setAccessoryVisible(visible = false, animated = false)
            shell.hideIme()
            shell.hideSheetForHandoff()
            var finished = false
            hooks.handoffAnimator.run(handoff, route) {
                // The completion dismisses the (hidden) sheet without
                // animation if it is still presented; first call only.
                if (finished) return@run
                finished = true
                if (shell.isSheetPresented) shell.dismissSheet(animated = false)
            }
        }
    }

    override fun presentNewSession(prompt: String?) {
        // `MainTabController.swift:136-148`: the New Session page sheet;
        // its `onCreated(chatId, handoff)` routes back through the router.
        // Any pending open is superseded, and a sheet already presented is
        // the UIKit no-op (it logs "already presenting" and refuses).
        generation++
        if (shell.isSheetPresented) return
        shell.presentNewSession(prompt) { id, handoff -> openSession(id, handoff) }
    }

    override fun showSettings() {
        // `MainTabController.swift:150-152`: selectedTab = tabs[more].
        generation++
        shell.selectTab(TabId.Settings)
    }

    override fun showSearch() {
        // `MainTabController.swift:154-156`: selectedTab = the search tab;
        // field activation is tickets 29/49.
        generation++
        shell.selectTab(TabId.Search)
    }

    override fun popToFrontPage() {
        // `MainTabController.swift:195-198`: only pops the Sessions stack,
        // without animation — it does NOT select the tab.
        generation++
        shell.popSessionsToRoot(animated = false)
    }

    override fun showSessionOpenError() {
        // `MainTabController.swift:200-208`: dismiss any presented
        // sheet/alert instantly, IME down, select Sessions, pop to root
        // (not animated), sync the accessory, then the toast — 14's toast
        // sits 12 dp above the bottom chrome actually on screen, which is
        // why the accessory is synced first.
        generation++
        if (shell.isSheetPresented) shell.dismissSheet(animated = false)
        shell.dismissAlerts()
        shell.hideIme()
        shell.selectTab(TabId.Sessions)
        shell.popSessionsToRoot(animated = false)
        shell.syncAccessory()
        shell.toast(OPEN_ERROR_TOAST)
    }

    override fun sessionDidAppear(chatId: String) {
        presence?.sessionDidAppear(chatId)
    }

    override fun sessionDidClose(chatId: String) {
        presence?.sessionDidClose(chatId)
    }
}

/**
 * The phone router in composition: results apply on the composition's
 * main dispatcher and the router's opens are cancelled with the shell (the
 * 840 dp swap to the split), each open's route discarded by
 * [RouterHooks.openSessionRoute]'s cancellation path.
 */
@Composable
fun rememberPhoneAppRouter(
    shell: PhoneShell,
    hooks: RouterHooks = LocalRouterHooks.current,
    presence: SessionPresenceListener? = null,
): AppRouter {
    val scope = rememberCoroutineScope() // AndroidUiDispatcher.Main; cancelled when the shell leaves
    return remember(shell, hooks, presence) { PhoneAppRouter(shell, hooks, scope, presence = presence) }
}
