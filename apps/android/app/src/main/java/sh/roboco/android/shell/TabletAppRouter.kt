package sh.roboco.android.shell

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the tablet router drives (android-ui-parity #106): the Android port
 * of `ios:Shell/SplitRootController.swift:69-154`'s split surface, minus
 * everything the shell tickets own outright (the frame and sidebar motion
 * are 33, the sidebar's search/stack bodies are 105, the Settings card is
 * 104, the toast is 14). Real implementation: [TabletShellAdapter] over
 * tickets 33/105/104/31/18/19/14. Tests: a recording fake. Main thread.
 */
interface TabletShell {

    /**
     * Ticket 33's `TabletShellState.isCollapsed` — one writer in
     * production: 108's `SideEffect` in `ShellStateScope` (ShellStates.kt),
     * derived from the shell kind. Default true.
     */
    val isCollapsed: Boolean

    /**
     * The one "open session" id (ticket 33's `TabletShellState`): writing
     * it **is** the sidebar highlight (105's rows read the state directly;
     * iOS mirrors it to `sidebar.currentChatId`).
     */
    var currentChatId: String?

    /** Ticket 33's `TabletShellState.detailRoot` (construction/restore reads it). */
    val detailRoot: DetailRoot

    /**
     * True when the detail stack's **root** route is a [SessionRoute]
     * (iOS `detail.viewControllers.first is SessionViewController`,
     * `SplitRootController.swift:74` — the first controller, not the top).
     */
    val detailShowsSession: Boolean

    /** Ticket 33's `TabletShellState.sidebarVisible`. */
    val sidebarVisible: Boolean

    /** True while 104's Settings card is one of the host's entries. */
    val isSettingsPresented: Boolean

    /** `detailStack.setRoutes(listOf(route))` + `setDetailRoot(Session(id))`; no animation. */
    fun setDetailSession(route: SessionRoute)

    /** `setRoutes([DraftDetailRoute])` + `setDetailRoot(Draft(prompt, focusOnAppear))`; no animation. */
    fun setDetailDraft(prompt: String?, focusOnAppear: Boolean, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit)

    /** `setRoutes([EmptyDetailRoute])` + `setDetailRoot(Empty)`; for 108's `clear()`. */
    fun setDetailEmpty()

    /**
     * iOS `dismiss(animated:)`: 18's alerts, 19's action sheet, then every
     * [SheetHost] entry (31) — which includes 104's Settings card, so no
     * separate presenter dismiss (a second `sheetHost.dismiss` while the
     * first animates out).
     */
    fun dismissPresented(animated: Boolean)

    /** 104's `SettingsDialogPresenter.present()`. */
    fun presentSettings()

    /** `sidebarVisible = true` (33 animates the reveal). */
    fun showSidebar()

    /** 105's `SidebarState.showSessions()`: search inactive + cleared, query "", stack popToRoot(false). */
    fun sidebarShowSessions()

    /** 105's `SidebarState.focusSearch()`: stack popToRoot(false), focus the search field (IME up). */
    fun focusSidebarSearch()

    /** IME down + focus cleared (iOS `view.endEditing(true)`). */
    fun hideIme()

    /** Ticket 14's toast. */
    fun toast(text: String)
}

/**
 * The phone router while collapsed (android-ui-parity #106). iOS's split
 * stays the root and forwards to its `tabs` child; Android swaps
 * `SplitRoot` ↔ `MainTabShell` (35), so while collapsed `LocalAppRouter`
 * is already the phone router and this slot serves only the calls that
 * reach the tablet router anyway: callbacks captured while expanded (the
 * draft's `onCreated`), deep links routed by 36 and 33's shortcuts.
 * [MainTabShell] reports the phone router through ticket 32's `onRouter`
 * → [attach]; the wiring detaches when the phone shell leaves. Main thread.
 */
class PhoneRouterSlot {
    private var router: AppRouter? = null
    private var pending: ((AppRouter) -> Unit)? = null

    /** The phone shell came up: use [r] from now on and run whatever was kept. */
    fun attach(r: AppRouter) {
        router = r
        val waiting = pending
        pending = null
        waiting?.invoke(r)
    }

    /** The phone shell left (the 840 dp expand cancels its scope): forget [r]. */
    fun detach(r: AppRouter) {
        if (router === r) router = null
    }

    /** Runs now, or keeps only the latest call until a phone router attaches. */
    fun run(block: (AppRouter) -> Unit) {
        val r = router
        if (r != null) block(r) else pending = block
    }

    /** An expanded router call drops the kept one (the latest intent wins). */
    fun clearPending() {
        pending = null
    }
}

/**
 * The tablet [AppRouter] (android-ui-parity #106): the port of
 * `ios:Shell/SplitRootController.swift:69-154` — what `openSession`,
 * `presentNewSession`, `showSettings`, `showSearch`,
 * `showSessionOpenError`, `sessionDidAppear/Close` and 80's
 * `leaveArchivedSession` do when the split is on screen — driving the
 * split through the [TabletShell] port and delegating to the phone router
 * while collapsed.
 *
 * Every order and `animated` flag is kept from the Swift; the one Android
 * addition is ticket 32's generation counter, plus the three tablet
 * extras: a second `openSession` for an id already pending is ignored
 * (double taps don't open twice), a same-id no-op still invalidates a
 * pending open of another id, and a result that lands after the split
 * collapsed is discarded (108's "stale results" contract).
 *
 * **Threading:** public methods are main-thread only; the open runs off
 * main through [RouterHooks.openSessionRoute] (never `CoreClient` on
 * main); the apply steps of [openSession] and [onDraftCreated] run in one
 * main-thread turn; lost routes are left to [RouterHooks.openSessionRoute]
 * (no `NonCancellable` wrapper — it discards on cancellation itself).
 *
 * The persistent detail [NavStack] (ticket 24) lives **above** the tabs /
 * split switch, owned by the adapter; [detailStack] exposes it so the
 * split renders it through [TabletDetailHost]. Every detail change is
 * `setRoutes(listOf(route))` — unanimated, delivering
 * `onWillDisappear`/`onDidDisappear(removed = true)` to the old root and
 * `close()`ing its entry (a keyed rebuild would leave the replaced session
 * attached and streaming).
 */
class TabletAppRouter(
    private val shell: TabletShell,
    private val hooks: RouterHooks,
    /** Main-confined; lives above the tabs/split switch (dies with the window). */
    private val scope: CoroutineScope,
    /** The phone router while collapsed ([PhoneRouterSlot]). */
    private val phone: PhoneRouterSlot,
    /** The factory runs here (reads the cache seed, synchronous FFI pulls — ticket 76), never on main. */
    private val openDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = { m, t -> android.util.Log.w("AppRouter", m, t) },
) : AppRouter {

    /**
     * The persistent detail stack (ticket 24), owned by the adapter;
     * [rememberTabletAppRouter] assigns it. [TabletDetailHost] renders it
     * and 108 empties the column through [clearDetail], never by touching
     * the stack directly.
     */
    lateinit var detailStack: NavStack
        internal set

    /**
     * Monotonically increasing. Each open captures it; every detail change
     * bumps it. The latest intent wins — a pending open that resolves
     * afterwards calls [SessionRoute.discard] and does nothing else.
     * ([showSettings], [showSearch], [popToFrontPage] and the presence
     * calls do not bump it: they do not touch the detail column.)
     */
    private var generation = 0L

    /** The chat id of the open that is currently resolving, or null. */
    private var pendingChatId: String? = null

    /**
     * Tab-shell session presence, forwarded by the phone router (the
     * `presence` argument of 32's [rememberPhoneAppRouter]) — iOS's
     * `tabBarController != nil` distinction (`SessionViewController.swift:309-310`):
     * only a session inside the **tab shell** reports a close.
     *
     * One implementation of the two rules (108): ticket 108's
     * [CarryOverPresence] over this split's selection, instead of a
     * second copy of the same logic.
     */
    val tabPresence: SessionPresenceListener = CarryOverPresence(
        object : CarryOverSelection {
            override var currentChatId: String?
                get() = shell.currentChatId
                set(v) {
                    shell.currentChatId = v
                }

            override val isCollapsed: Boolean
                get() = shell.isCollapsed
        },
    )

    init {
        // Construction / restore: from the saved detail root (33). Runs
        // inside `remember {}` before anything in this composition has
        // read the state — a LaunchedEffect would show one frame of an
        // empty detail at launch.
        when (val r = shell.detailRoot) {
            is DetailRoot.Draft -> shell.setDetailDraft(r.prompt, false, ::onDraftCreated)
            is DetailRoot.Session -> resolve(r.chatId) { route -> applyOpened(r.chatId, route) }
            DetailRoot.Empty -> Unit // 108 decides what an empty column shows
        }
    }

    /** Bumps the generation and clears the pending chat id. */
    private fun invalidate() {
        generation++
        pendingChatId = null
    }

    /**
     * The shared open-and-apply: captures the generation, opens off main,
     * applies on main. A superseded open discards its route. Never cancel
     * the coroutine to supersede it — the generation check (or the route
     * would leak without `discard()`).
     */
    private fun resolve(chatId: String, apply: (SessionRoute?) -> Unit) {
        val gen = ++generation
        pendingChatId = chatId
        scope.launch {
            // No withContext(NonCancellable): 32's openSessionRoute
            // discards a built route itself when this scope is cancelled.
            val route = hooks.openSessionRoute(chatId, openDispatcher, log)
            if (gen != generation) {
                route?.discard()
                return@launch
            }
            pendingChatId = null
            apply(route)
        }
    }

    /**
     * Collapsed meanwhile (checked before the null check — 108's "stale
     * results" contract) → discard, nothing else. `null` → the open-error
     * recovery. Otherwise true and the caller swaps the column.
     */
    private fun accept(route: SessionRoute?): Boolean {
        if (shell.isCollapsed) {
            route?.discard()
            return false
        }
        if (route == null) {
            showSessionOpenError()
            return false
        }
        return true
    }

    /** The openSession result handling: highlight only after a successful open. */
    private fun applyOpened(chatId: String, route: SessionRoute?) {
        // accept() already rejects null (the open-error path); the null check
        // only gives the compiler its smart cast — never a crash.
        if (!accept(route) || route == null) return
        shell.currentChatId = chatId
        shell.setDetailSession(route)
    }

    override fun openSession(chatId: String) {
        // `SplitRootController.swift:72`: dismiss whatever the split
        // presents (Settings card, alert, sheet) **animated**, before the
        // collapsed check and before the same-id check.
        shell.dismissPresented(animated = true)
        if (shell.isCollapsed) return phone.run { it.openSession(chatId) }
        phone.clearPending()
        // Same id and a session in the column: nothing else happens (no
        // reload, no re-highlight) — but the bump still supersedes a
        // pending open of another id (the latest intent wins).
        if (shell.currentChatId == chatId && shell.detailShowsSession) return invalidate()
        // A second open for an id already pending is ignored (double taps).
        if (pendingChatId == chatId) return
        resolve(chatId) { route -> applyOpened(chatId, route) }
    }

    override fun openSession(chatId: String, handoff: DraftHandoff?) = openSession(chatId)

    override fun presentNewSession(prompt: String?) {
        // `:92-95`: the expanded branch does NOT dismiss anything.
        if (shell.isCollapsed) return phone.run { it.presentNewSession(prompt) }
        showDraft(prompt, focus = true)
    }

    /**
     * iOS `showDraft(prompt:focus:)` (`:99-123`), public: ticket 108 (expand
     * with nothing open) calls it too. Always rebuilds — even if a draft is
     * already shown (`:8`) — and clears the highlight first.
     */
    fun showDraft(prompt: String?, focus: Boolean) {
        invalidate()
        phone.clearPending()
        shell.currentChatId = null
        shell.setDetailDraft(prompt, focus, ::onDraftCreated)
    }

    /**
     * The embedded draft's `onCreated(chatId, handoff)`
     * (`NewSessionViewController.swift:406-424`). A null handoff, or the
     * split collapsed meanwhile, is the plain open; otherwise the chat
     * replaces the draft in place and the handoff carries the composer and
     * message across — no completion (iOS passes none on the split).
     */
    internal fun onDraftCreated(chatId: String, handoff: DraftHandoff?) {
        if (handoff == null || shell.isCollapsed) return openSession(chatId)
        resolve(chatId) { route ->
            if (!accept(route) || route == null) return@resolve
            shell.currentChatId = chatId
            // Compose may draw the new route before a later callback, so
            // the route hides its content first (32's adaptation of
            // performWithoutAnimation; 77 makes it plain state writes).
            route.prepareArrival()
            shell.setDetailSession(route)
            // A focused BasicTextField that moves to the leaving list
            // stays composed for one more frame: hide the IME now, in the
            // same turn (95's glide expects it going down).
            shell.hideIme()
            hooks.handoffAnimator.run(handoff, route) { }
        }
    }

    override fun showSettings() {
        if (shell.isCollapsed) return phone.run { it.showSettings() }
        phone.clearPending()
        // UIKit refuses a second presentation ("already presenting").
        if (shell.isSettingsPresented) return
        shell.presentSettings()
    }

    override fun showSearch() {
        if (shell.isCollapsed) return phone.run { it.showSearch() }
        phone.clearPending()
        // `.secondaryOnly` → `show(.primary)` first; then focus at once —
        // the keyboard rises while the sidebar slides in (no settle wait).
        if (!shell.sidebarVisible) shell.showSidebar()
        shell.focusSidebarSearch()
    }

    override fun showSessionOpenError() {
        if (shell.isCollapsed) {
            invalidate()
            return phone.run { it.showSessionOpenError() }
        }
        // `:83-90`, verbatim order: instant dismissal, keyboard down, the
        // draft page (no focus), the sidebar back to its front page with
        // the search cleared, a hidden sidebar revealed, then the toast.
        // showDraft also bumps the generation, so a pending open dies here.
        shell.dismissPresented(animated = false)
        shell.hideIme()
        showDraft(prompt = null, focus = false)
        shell.sidebarShowSessions()
        shell.showSidebar()
        shell.toast(OPEN_ERROR_TOAST)
    }

    override fun popToFrontPage() {
        // The split has no front-page stack (iOS only has
        // `tabs.popToFrontPage()`); expanded = nothing.
        if (shell.isCollapsed) phone.run { it.popToFrontPage() }
    }

    /** `:143-146`: always records the id (the sidebar highlight follows). */
    override fun sessionDidAppear(chatId: String) {
        shell.currentChatId = chatId
    }

    /**
     * A **detail-column** session: iOS sessions there have
     * `tabBarController == nil` and never report a close
     * (`SessionViewController.swift:309-310`). Deliberately a no-op — a
     * collapsed-only guard would clear the carry-over when 108's `clear()`
     * releases the detail session; tab-shell closes arrive through
     * [tabPresence] instead.
     */
    override fun sessionDidClose(chatId: String) {
    }

    override fun leaveArchivedSession(chatId: String): Boolean {
        // `SessionViewController.swift:536-540`: beside the sidebar there is
        // nothing to pop back to — the column shows the draft; the phone
        // screen pops itself and gets `false`.
        if (shell.isCollapsed) return false
        showDraft(prompt = null, focus = false)
        return true
    }

    override fun leaveDeletedSession(chatId: String): Boolean {
        if (shell.isCollapsed || shell.currentChatId != chatId || !shell.detailShowsSession) return false
        showDraft(prompt = null, focus = false)
        return true
    }

    /**
     * Ticket 108's `SplitDetailColumn.showSession` (iOS `:180-183`): the
     * detail open stripped of `openSession`'s steps 1-5 — no
     * `dismissPresented`, no same-id/pending early return — plus the one
     * step the ticket keeps from its expand sketch: [phone.clearPending],
     * so a phone call queued while collapsed (stale by the time the
     * widen's detail open runs) is dropped. Otherwise just [resolve] with
     * the same result handling.
     */
    fun showSessionInDetail(chatId: String) {
        phone.clearPending()
        resolve(chatId) { route -> applyOpened(chatId, route) }
    }

    /**
     * Ticket 108's `SplitDetailColumn.clear` (iOS `:164`): pending opens
     * discarded, the column emptied, [TabletShell.currentChatId] untouched
     * (108 has already captured it for the carry-over).
     */
    fun clearDetail() {
        invalidate()
        if (shell.detailRoot != DetailRoot.Empty) shell.setDetailEmpty()
    }
}
