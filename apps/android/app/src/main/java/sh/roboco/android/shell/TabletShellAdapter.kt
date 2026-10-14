package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import sh.roboco.android.design.ActionSheetHostState
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.Toaster
import sh.roboco.android.notify.findActivity
import sh.roboco.android.shell.tablet.SidebarState

/**
 * The slot for ticket 104's [SettingsDialogPresenter] (android-ui-parity
 * #106). The presenter must be created **inside** the split's
 * `LocalAppRouter provides tabletRouter` (it captures
 * `currentCompositionLocalContext` so the card's rows reach this router,
 * 104 §Comments "Captured locals"), which is below where the router itself
 * is created. The split registers it here while composed and unregisters
 * on dispose (108 later recreates this wiring inside its `ShellStateScope`).
 */
class SettingsPresenterSlot {
    /** The split's presenter, or null while the split is off screen. */
    var presenter: SettingsDialogPresenter? by mutableStateOf(null)
}

/**
 * The real [TabletShell] (android-ui-parity #106): over ticket 33's
 * [TabletShellState] (the saved, hoisted split state), the router-owned
 * persistent detail [NavStack] (24), 105's [SidebarState] (one instance
 * above the tabs/split switch), 104's presenter through
 * [SettingsPresenterSlot], and the root hosts' state (31's
 * [SheetHostState] — which carries 104's Settings card, 18's alerts, 19's
 * action sheet, 14's toaster) captured as lambdas so they re-read the
 * composition-local values. Main thread.
 */
internal class TabletShellAdapter(
    private val state: TabletShellState,
    /** Ticket 24, owned here: one stack above the tabs/split switch. */
    val detailStack: NavStack,
    /** Ticket 105: one instance above the tabs/split switch. */
    private val sidebar: SidebarState,
    private val settings: SettingsPresenterSlot,
    /** 31: `LocalSheetHost.current` captured in composition. */
    private val sheets: () -> SheetHostState?,
    private val alerts: () -> Alerts,
    private val actionSheet: () -> ActionSheetPresenter,
    private val toaster: () -> Toaster,
    private val hideImeImpl: () -> Unit,
) : TabletShell {

    override val isCollapsed: Boolean
        get() = state.isCollapsed

    override var currentChatId: String?
        get() = state.currentChatId
        set(value) {
            state.currentChatId = value
        }

    override val detailRoot: DetailRoot
        get() = state.detailRoot

    override val sidebarVisible: Boolean
        get() = state.sidebarVisible

    /** The stack's ROOT route (iOS checks `viewControllers.first`). */
    override val detailShowsSession: Boolean
        get() = detailStack.entries.firstOrNull()?.route is SessionRoute

    override val isSettingsPresented: Boolean
        get() = settings.presenter?.isPresented == true

    override fun setDetailSession(route: SessionRoute) {
        // Every factory route is a NavRoute (ticket 76).
        detailStack.setRoutes(listOf(route as NavRoute))
        state.setDetailRoot(DetailRoot.Session(route.chatId))
    }

    override fun setDetailDraft(prompt: String?, focusOnAppear: Boolean, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit) {
        detailStack.setRoutes(listOf(DraftDetailRoute(prompt, focusOnAppear, onCreated)))
        state.setDetailRoot(DetailRoot.Draft(prompt, focusOnAppear))
    }

    override fun setDetailEmpty() {
        // Hostless while collapsed → completes synchronously and `close()`s
        // the old entry (24), so nothing composes against the stack.
        detailStack.setRoutes(listOf(EmptyDetailRoute))
        state.setDetailRoot(DetailRoot.Empty)
    }

    override fun dismissPresented(animated: Boolean) {
        // 18, then 19 (iOS `dismiss` removes a presented action sheet too),
        // then 31 — every SheetHost entry, 104's Settings card included; no
        // separate presenter dismiss (a second `sheetHost.dismiss` while
        // the first animates out).
        alerts().dismissAll()
        (actionSheet() as? ActionSheetHostState)?.clear()
        sheets()?.takeIf { it.isPresenting }?.dismiss(animated)
    }

    override fun presentSettings() {
        // 104's present() also refuses (with Log.w) while any sheet entry
        // exists — the router's isSettingsPresented check is the first gate.
        settings.presenter?.present()
    }

    override fun showSidebar() {
        state.sidebarVisible = true
    }

    override fun sidebarShowSessions() {
        sidebar.showSessions()
    }

    override fun focusSidebarSearch() {
        sidebar.focusSearch()
    }

    override fun hideIme() {
        hideImeImpl()
    }

    override fun toast(text: String) {
        toaster().show(text)
    }
}

/**
 * The tablet router in composition (android-ui-parity #106): built above
 * ticket 35's Phone ↔ Split switch together with [state], [sidebar] and
 * the slots, so the router, its detail stack and `tabPresence` survive the
 * switch (iOS keeps one `SplitRootController`). The adapter captures the
 * root hosts' state through `rememberUpdatedState` lambdas and the root
 * view's IME handles — the root [android.view.View] lives as long as the
 * activity (35's `configChanges`), so capturing them once is safe.
 */
@Composable
fun rememberTabletAppRouter(
    state: TabletShellState,
    phone: PhoneRouterSlot,
    sidebar: SidebarState,
    settings: SettingsPresenterSlot,
    hooks: RouterHooks = LocalRouterHooks.current,
): TabletAppRouter {
    val scope = rememberCoroutineScope()
    val sheets by rememberUpdatedState(LocalSheetHost.current)
    val alerts by rememberUpdatedState(LocalAlerts.current)
    val actionSheet by rememberUpdatedState(LocalActionSheet.current)
    val toaster by rememberUpdatedState(LocalToaster.current)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    return remember(state, hooks) {
        val adapter = TabletShellAdapter(
            state = state,
            detailStack = NavStack(EmptyDetailRoute),
            sidebar = sidebar,
            settings = settings,
            sheets = { sheets },
            alerts = { alerts },
            actionSheet = { actionSheet },
            toaster = { toaster },
            // The same three calls as 32's PhoneShellAdapter.hideIme: the
            // keyboard controller, the focus clear, and the insets
            // controller's hide (belt and braces — a focused field that
            // moves to the leaving list stays composed for one frame).
            hideImeImpl = {
                keyboard?.hide()
                focus.clearFocus(force = true)
                view.context.findActivity()?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                }
            },
        )
        TabletAppRouter(adapter, hooks, scope, phone).also { it.detailStack = adapter.detailStack }
    }
}
