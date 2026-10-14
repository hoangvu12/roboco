package sh.roboco.android.shell

/**
 * The collapse / expand carry-over across the 840 dp breakpoint
 * (android-ui-parity #108) — the Android port of
 * `ios:Shell/SplitRootController.swift:156-191`. Pure Kotlin + coroutines:
 * no Compose UI, no `android.*` — production wires it from
 * [ShellStateScope] in `shell/ShellStates.kt`.
 *
 * The carry-over owns **what happens to the open session** when the window
 * crosses the split breakpoint (ticket 35's [RootKind] switch, which is
 * instant):
 *
 * - **Narrowing** (Split → Phone) captures [CarryOverSelection.currentChatId]
 *   synchronously (iOS `:161`), then, on the next frame, empties the detail
 *   column (only when its root is a session; a draft stays) and pushes the
 *   captured chat on the phone Sessions tab — through the fence, so the
 *   outgoing copy detaches before the incoming one attaches.
 * - **Widening** (Phone → Split) pops the phone Sessions stack (and the
 *   Search stack, the Roboco adaptation) and brings the last open session
 *   into the detail column, or shows the draft when none was open and the
 *   column is empty.
 *
 * [CarryOverPresence] is the single implementation of the presence rules
 * (iOS `:141-154`); the routers forward both shell's `sessionDidAppear` /
 * `sessionDidClose` into it.
 */

/** The selection the carry-over reads and writes. Production: ticket 33's [TabletShellState] (see [carryOverSelection]). Main thread. */
interface CarryOverSelection {

    /** The one "open session" id (iOS `currentChatId`). */
    var currentChatId: String?

    /** True while the window is below the split breakpoint (ticket 33's `TabletShellState.isCollapsed`). */
    val isCollapsed: Boolean
}

/**
 * Ticket 106's detail column as the carry-over needs it (the
 * [TabletAppRouter] surface). Main thread. Must work while `SplitRoot` is
 * **not** composed — the split leaves composition one frame before the
 * carry-over touches the column, which is exactly what makes the outgoing
 * entry close synchronously (ticket 24's hostless ops).
 */
interface SplitDetailColumn {

    /** The chat the detail column shows ([DetailRoot.Session]), or null (Draft / Empty). */
    val shownChatId: String?

    /** `DetailRoot.Empty` (iOS `detail.viewControllers.isEmpty`). */
    val isEmpty: Boolean

    /**
     * iOS `detail.setViewControllers([], animated: false)` (`:164`): root →
     * Empty, the outgoing Session entry closed synchronously, pending
     * detail opens discarded. Does not touch `currentChatId`.
     */
    fun clear()

    /**
     * iOS `:180-183`: open off main, then make it the detail root without
     * animation; null → the split's `showSessionOpenError()`. Dismisses
     * nothing. Returns at once (the open completes later).
     */
    fun showSession(chatId: String)

    /** iOS `showDraft(prompt: nil, focus: false)` (`:186-188`). */
    fun showDraft()
}

/**
 * The phone tab shell as the carry-over needs it. Main thread. Works while
 * `MainTabShell` is not composed — the pops and the open go through the
 * hoisted state (ticket 26) and ticket 106's [PhoneRouterSlot], never
 * through a router that already left composition.
 */
interface TabsColumn {

    /** Ticket 32's `PhoneAppRouter.openSession(chatId)`, through 106's `PhoneRouterSlot.run { }` (runs now, or when the phone router attaches). */
    fun openSession(chatId: String)

    /** iOS `MainTabController.popToFrontPage()` (`:193-198`): Sessions stack → root, not animated. */
    fun popToFrontPage()

    /** ADAPT: Search stack → root, not animated, only if it holds a Session entry. */
    fun popSearchSessions()

    /** Every tab stack → root, not animated (shell teardown). */
    fun popAllToRoot()
}

/** One step of a carry-over run, in order. Pure: [CarryOverPlan] builds them, [CarryOver] runs them. */
sealed interface CarryOverStep {

    /** iOS `:163-165`: the detail's root is a Session → empty the column. */
    data object ClearDetail : CarryOverStep

    /** iOS `:166`: `tabs.openSession(chatId)` — the phone open (animated push). */
    data class OpenInTabs(val chatId: String) : CarryOverStep

    /** iOS `:176`: `tabs.popToFrontPage()`. */
    data object PopTabsToFrontPage : CarryOverStep

    /** The Roboco adaptation: pop a session off the hidden Search stack too. */
    data object PopSearchSessions : CarryOverStep

    /** iOS `:178-183`: the detail open (no dismissal, no animation). */
    data class ShowInDetail(val chatId: String) : CarryOverStep

    /** iOS `:186-188`: the embedded draft, no focus. */
    data object ShowDraft : CarryOverStep
}

/** The two transition plans, as pure functions over the state the run reads (facts 1-9). */
object CarryOverPlan {

    /** iOS `:163-166`: clear a Session detail, then push the captured id in the tabs. */
    fun collapse(captured: String?, shownChatId: String?): List<CarryOverStep> = buildList {
        if (shownChatId != null) add(CarryOverStep.ClearDetail)
        if (captured != null) add(CarryOverStep.OpenInTabs(captured))
    }

    /** iOS `:175-188` (+ the Search adaptation): pop the tabs first, then the detail work. */
    fun expand(current: String?, shownChatId: String?, detailIsEmpty: Boolean): List<CarryOverStep> = buildList {
        add(CarryOverStep.PopTabsToFrontPage)
        add(CarryOverStep.PopSearchSessions)
        if (current != null) {
            if (shownChatId != current) add(CarryOverStep.ShowInDetail(current))
        } else if (detailIsEmpty) {
            add(CarryOverStep.ShowDraft)
        }
    }
}

/**
 * iOS `sessionDidAppear` / `sessionDidClose` (`:141-154`). Both routers
 * forward to it: the phone router through its `presence` parameter (32),
 * the tablet router through [TabletAppRouter.tabPresence] (106). The
 * detail-column sessions' **own** close never reaches here — ticket 106
 * keeps its `sessionDidClose` a no-op on purpose (Spec §Presence).
 */
class CarryOverPresence(private val selection: CarryOverSelection) : SessionPresenceListener {

    /** iOS `:143-146`: always records the id, either shell. */
    override fun sessionDidAppear(chatId: String) {
        selection.currentChatId = chatId
    }

    /** iOS `:150-154`: only a close **in the tab shell while collapsed** of the current id clears it. */
    override fun sessionDidClose(chatId: String) {
        if (selection.isCollapsed && selection.currentChatId == chatId) selection.currentChatId = null
    }
}

/**
 * The transition runner. Cancellable and idempotent: a new transition
 * before the previous one finished restarts `LaunchedEffect(kind)` in
 * [ShellStateScope], which cancels the previous run — every step either
 * already ran (and the plan of the new run re-derives from the current
 * state) or never will.
 *
 * @param selection the id and collapse flag (ticket 33's state).
 * @param detail the detail column (ticket 106's router).
 * @param tabs the phone tab shell (tickets 26/32/106).
 * @param awaitFrame production: `{ withFrameNanos { } }` — one frame after
 *   the composition that switched shells has been applied (iOS
 *   `DispatchQueue.main.async`).
 * @param awaitDetached production: the detach fence join (iOS runs both
 *   sides on the main thread; Android's per-controller IO lanes need the
 *   fence so the old `setViewAttached(false)` cannot land after the new
 *   `setViewAttached(true)` on the shared warm handle).
 */
class CarryOver(
    private val selection: CarryOverSelection,
    private val detail: SplitDetailColumn,
    private val tabs: TabsColumn,
    private val awaitFrame: suspend () -> Unit,
    private val awaitDetached: suspend (chatId: String) -> Unit,
) {

    /**
     * Called on every shell-kind change (`from = null` on first
     * composition; Pairing transitions never reach here — the shell scope
     * itself is released then). Cancellable; every step is idempotent.
     */
    suspend fun onShellKindChanged(from: RootKind?, to: RootKind) {
        when {
            from == RootKind.Split && to == RootKind.Phone -> {
                // iOS `:161`: captured synchronously, before the deferral —
                // an appear landing in between changes nothing.
                val captured = selection.currentChatId
                awaitFrame()
                run(CarryOverPlan.collapse(captured, detail.shownChatId))
            }

            from == RootKind.Phone && to == RootKind.Split -> {
                // iOS `:174-175`: the id is read INSIDE the deferred block —
                // an appear landing before the frame is the one carried.
                awaitFrame()
                run(CarryOverPlan.expand(selection.currentChatId, detail.shownChatId, detail.isEmpty))
            }

            else -> Unit
        }
    }

    /** Shell teardown: close every hoisted Session entry. Main thread, synchronous. */
    fun releaseAll() {
        tabs.popAllToRoot()
        detail.clear()
    }

    private suspend fun run(steps: List<CarryOverStep>) {
        for (step in steps) {
            when (step) {
                CarryOverStep.ClearDetail -> detail.clear()
                is CarryOverStep.OpenInTabs -> {
                    awaitDetached(step.chatId)
                    tabs.openSession(step.chatId)
                }

                CarryOverStep.PopTabsToFrontPage -> tabs.popToFrontPage()
                CarryOverStep.PopSearchSessions -> tabs.popSearchSessions()
                is CarryOverStep.ShowInDetail -> {
                    awaitDetached(step.chatId)
                    detail.showSession(step.chatId)
                }

                CarryOverStep.ShowDraft -> detail.showDraft()
            }
        }
    }
}
