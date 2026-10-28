package sh.roboco.android.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import sh.roboco.android.design.ActionSheetHostState
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.Toaster
import sh.roboco.android.newsession.NewSessionScreen

/**
 * The real [PhoneShell] (android-ui-parity #32): one adapter over the
 * neighbour tickets' landed state — 26's [MainTabState], 24's [NavStack]
 * (through `stack(TabId.Sessions)`), 31's [SheetHostState], 18's [Alerts],
 * 19's action sheet, 14's [Toaster] and the IME. Nothing of its own:
 * motion, scrim, recession and the chrome are those tickets' work, and the
 * router only asks for operations in an order.
 *
 * Accessory visibility is **derived** by ticket 30 from the selected
 * stack's top route (`NavRoute.hidesBottomChrome`, which 76's
 * `SessionScreenRoute` sets), so [setAccessoryVisible] and
 * [syncAccessory] are no-ops: the port keeps the calls so the router's
 * order stays testable against iOS, and pushing the Session route hides
 * the chrome group, popping to root shows it.
 */
@Stable
internal class PhoneShellAdapter(
    private val tabs: MainTabState,
    private val sheetHost: SheetHostState?,
    private val alerts: Alerts,
    private val actionSheet: ActionSheetPresenter,
    private val toaster: Toaster,
    private val keyboard: SoftwareKeyboardController?,
    private val focusManager: FocusManager,
    private val view: View,
    /** Wave-10 37: the one-row bar — Settings and Search are not tabs. */
    private val oneRowBar: Boolean = false,
    /** The bar's field, activated by Search (one-row bar only). */
    private val search: SearchTabState? = null,
) : PhoneShell {

    /** 31's contract: the [SheetHandoff] from [hideSheetForHandoff], completed by [dismissSheet] once the motion ends. */
    private var pendingHandoff: SheetHandoff? = null

    override fun selectTab(tab: TabId) {
        if (oneRowBar) {
            selectOnTheFrontPage(tab)
            return
        }
        // Programmatic selection: no haptic, no re-select pop-to-root
        // (those are onTabTap's user-path behaviours).
        tabs.select(tab)
    }

    override val isSheetPresented: Boolean
        get() = sheetHost?.isPresenting == true

    /**
     * The one-row bar's "tabs" (upstream #967's `showSettings` /
     * `showSearch`): Sessions is the only stack. Settings dismisses a
     * presented sheet and pushes over the list (once — a second
     * `showSettings` with Settings on top does nothing); Search goes back
     * to the list and activates the bar's field.
     */
    private fun selectOnTheFrontPage(tab: TabId) {
        tabs.select(TabId.Sessions)
        val stack = tabs.stack(TabId.Sessions)
        when (tab) {
            TabId.Settings -> {
                if (isSheetPresented) dismissSheet(animated = false)
                if (stack.top.route == SettingsRoute) return
                stack.popToRoot(animated = false)
                stack.push(SettingsRoute, animated = true)
            }
            TabId.Search -> {
                stack.popToRoot(animated = false)
                search?.activate()
            }
            TabId.Sessions -> Unit
        }
    }

    override fun presentNewSession(prompt: String?, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit) {
        // `MainTabController.swift:136-148`: New Session in a page sheet at
        // the large detent, grabber visible. The sheet's `imePadding`
        // shrinks the canvas's constraints; the canvas measures the rest
        // (the wallpaper keeps the IME-less page height through its
        // `onConsumedWindowInsetsChanged` read).
        sheetHost?.present(
            key = "new-session",
            style = SheetStyle.Page(grabber = true),
        ) {
            NewSessionScreen(
                prompt = prompt,
                embedded = false,
                onCreated = onCreated,
                onClose = { dismiss(animated = true) },
            )
        }
    }

    override fun dismissSheet(animated: Boolean) {
        val handoff = pendingHandoff
        if (handoff != null) {
            // The hidden sheet: removal without animation, whatever
            // `animated` says — it is invisible anyway. 31 requires
            // `complete()` on the handle (the caller must call it); going
            // through `dismiss(false)` instead would tear it down the
            // wrong way.
            pendingHandoff = null
            handoff.complete()
            return
        }
        sheetHost?.dismiss(animated)
    }

    override fun hideSheetForHandoff() {
        // `MainTabController.swift:170-190`: the stack vanishes in one
        // frame (container alpha 0, scrim 0, recession reset) and is torn
        // down only once the handoff motion calls complete(). With no
        // sheet up there is nothing to hide — and no handle to store: a
        // stale one would hijack a later dismissSheet (its complete() is
        // a no-op on the empty hidden set).
        if (sheetHost == null || !sheetHost.isPresenting) return
        pendingHandoff = sheetHost.hideForHandoff()
    }

    override fun dismissAlerts() {
        // iOS `dismiss(animated:)` also removes a presented alert
        // controller, and a presented action sheet (19). 18's
        // dismissAll() clears the queue and fades the current alert
        // without running any handler; 19's host state is internal to
        // this module and cleared directly (the call 35's root swap
        // uses).
        alerts.dismissAll()
        (actionSheet as? ActionSheetHostState)?.clear()
    }

    override fun popSessionsToRoot(animated: Boolean) {
        tabs.stack(TabId.Sessions).popToRoot(animated)
    }

    override fun pushSession(route: SessionRoute, animated: Boolean) {
        // Every route the factory returns is also a NavRoute (76's
        // SessionScreenRoute, hidesBottomChrome = true); 26's
        // ShellRouteContent renders it. 24 has no keyed-content push.
        val navRoute = route as? NavRoute
        if (navRoute == null) {
            Log.w(TAG, "SessionRoute ${route.chatId} is not a NavRoute; not pushing")
            return
        }
        tabs.stack(TabId.Sessions).push(navRoute, animated)
    }

    override fun setAccessoryVisible(visible: Boolean, animated: Boolean) {
        // No-op: ticket 30 derives the group's visibility from the top
        // route's hidesBottomChrome — the non-animated Session push above
        // already hid the chrome (the group rides/never leaves
        // composition, upstream #577).
    }

    override fun syncAccessory() {
        // No-op: ticket 30 re-derives on every tab change and stack
        // change; after the popToRoot above the chrome shows again.
    }

    override fun hideIme() {
        // iOS `view.endEditing(true)`. 31 renders sheets in-window (no
        // Dialog), so the keyboard controller + focus clear normally
        // suffice as the focused field leaves composition; the
        // insets-controller hide is a belt-and-braces call ticket 106
        // copies.
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        val activity = view.context.findActivity() ?: return
        WindowCompat.getInsetsController(activity.window, view).hide(WindowInsetsCompat.Type.ime())
    }

    override fun toast(text: String) {
        toaster.show(text)
    }

    private companion object {
        const val TAG = "PhoneShell"
    }
}

/**
 * The adapter in composition: captures the neighbour tickets' state (the
 * [MainTabState] plus the sheet host, alerts, action sheet, toaster, IME
 * and view locals) and returns one stable [PhoneShell], so
 * [rememberPhoneAppRouter] does not recreate the router on recomposition.
 * Mount it inside the shell's [SheetHost] so [LocalSheetHost] is live; a
 * null host (no [SheetHost] up) makes every sheet member a no-op.
 */
@Composable
fun rememberPhoneShell(
    tabs: MainTabState,
    sheetHost: SheetHostState? = LocalSheetHost.current,
    alerts: Alerts = LocalAlerts.current,
    actionSheet: ActionSheetPresenter = LocalActionSheet.current,
    toaster: Toaster = LocalToaster.current,
    keyboard: SoftwareKeyboardController? = LocalSoftwareKeyboardController.current,
    focusManager: FocusManager = LocalFocusManager.current,
    view: View = LocalView.current,
    oneRowBar: Boolean = false,
    search: SearchTabState? = null,
): PhoneShell = remember(tabs, sheetHost, alerts, actionSheet, toaster, keyboard, focusManager, view, oneRowBar, search) {
    PhoneShellAdapter(tabs, sheetHost, alerts, actionSheet, toaster, keyboard, focusManager, view, oneRowBar, search)
}

/** Unwraps [ContextWrapper]s down to the hosting [Activity], or null (previews, tests). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
