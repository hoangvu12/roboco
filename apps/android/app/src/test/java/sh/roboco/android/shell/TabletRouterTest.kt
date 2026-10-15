package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet router (android-ui-parity #106) against a recording fake
 * shell — every case of the ticket's Verification list. Plain JUnit: no
 * Robolectric, no compose UI, `android.util.Log` never touched (the router
 * gets a no-op `log`).
 *
 * Threading is half of it, as in ticket 32's [RouterTest]: the factory must
 * run on the open dispatcher, never on the router's main dispatcher;
 * results apply on the main dispatcher; the generation counter supersedes
 * pending opens (and the same-id tap, `showSessionInDetail` and
 * `clearDetail` bump it too); a result that lands after the split collapsed
 * is discarded with no error sequence; a cancelled router scope discards
 * its route through [RouterHooks.openSessionRoute].
 */
class TabletRouterTest {

    // ------------------------------------------------------------ the rig

    /**
     * The expanded error sequence (`:83-90` + `showSessions()` `:294-298`),
     * verbatim order and toast copy — without openSession's own animated
     * dismissal, which the caller adds first.
     */
    private val errorTail = listOf(
        "dismissPresented(false)",
        "hideIme",
        "current=null",
        "draft(null,false)",
        "sidebar.showSessions",
        "showSidebar",
        "toast($OPEN_ERROR_TOAST)",
    )

    private fun rig(scheduler: TestCoroutineScheduler, root: DetailRoot = DetailRoot.Draft(null, false)): Rig =
        Rig(scheduler, root)

    private class Rig(scheduler: TestCoroutineScheduler, root: DetailRoot) {
        val log = mutableListOf<String>()
        val shell = FakeTabletShell(log, root)
        val io = CountingDispatcher(StandardTestDispatcher(scheduler))
        val main = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(main)
        val phoneSlot = PhoneRouterSlot()
        val phone = FakePhoneRouter(log)

        /** The fake factory's call count (only the open dispatcher reaches it). */
        var opens = 0

        /** What the fake factory returns per chat id (after the missing/boom rules). */
        var openImpl: (String) -> SessionRoute? = { id -> FakeRoute(id, log) }

        val hooks = RouterHooks().apply {
            // The assertion behind "the factory runs on the open
            // dispatcher": io.inOpen is only true inside a block the
            // counting dispatcher dispatched, so a factory that ran on the
            // router's main dispatcher fails here.
            sessionRoutes = SessionRouteFactory { id ->
                check(io.inOpen) { "the factory must run on the open dispatcher, never on the router's main" }
                opens++
                when {
                    id == "boom" -> throw IllegalStateException("engine not paired")
                    id.startsWith("missing") -> null
                    else -> openImpl(id)
                }
            }
            handoffAnimator = FakeAnimator(log)
        }

        val router = TabletAppRouter(
            shell = shell,
            hooks = hooks,
            scope = scope,
            phone = phoneSlot,
            openDispatcher = io,
            log = { _, _ -> }, // plain JUnit: android.util.Log is not mocked
        )

        init {
            // MainTabShell's `onRouter`: the phone shell is up from the
            // start, so collapsed delegation runs at once (case 25 detaches).
            phoneSlot.attach(phone)
        }
    }

    /** The ticket's counting dispatcher: true only inside a block dispatched by io. */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var inOpen = false
            private set

        override fun isDispatchNeeded(context: CoroutineContext) = true

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            inner.dispatch(context, Runnable { inOpen = true; try { block.run() } finally { inOpen = false } })
    }

    /** The recording split: readable log strings, detail-root state, the stored onCreated. */
    private class FakeTabletShell(
        private val log: MutableList<String>,
        initialRoot: DetailRoot,
    ) : TabletShell {
        var collapsed = false
        var sidebar = true
        var settingsUp = false
        var root: DetailRoot = initialRoot
            private set
        var showsSession = false
        var onCreated: ((chatId: String, handoff: DraftHandoff?) -> Unit)? = null
            private set

        override val isCollapsed: Boolean get() = collapsed
        override val sidebarVisible: Boolean get() = sidebar
        override val isSettingsPresented: Boolean get() = settingsUp
        override val detailRoot: DetailRoot get() = root
        override val detailShowsSession: Boolean get() = showsSession

        override var currentChatId: String? = null
            set(value) {
                field = value
                log += "current=${value ?: "null"}"
            }

        override fun setDetailSession(route: SessionRoute) {
            log += "session(${route.chatId})"
            root = DetailRoot.Session(route.chatId)
            showsSession = true
        }

        override fun setDetailDraft(
            prompt: String?,
            focusOnAppear: Boolean,
            onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit,
        ) {
            log += "draft(${prompt ?: "null"},$focusOnAppear)"
            root = DetailRoot.Draft(prompt, focusOnAppear)
            showsSession = false
            this.onCreated = onCreated
        }

        override fun setDetailEmpty() {
            log += "empty"
            root = DetailRoot.Empty
            showsSession = false
        }

        override fun dismissPresented(animated: Boolean) {
            log += "dismissPresented($animated)"
        }

        override fun presentSettings() {
            log += "presentSettings"
            settingsUp = true
        }

        override fun showSidebar() {
            log += "showSidebar"
            sidebar = true
        }

        override fun sidebarShowSessions() {
            log += "sidebar.showSessions"
        }

        override fun focusSidebarSearch() {
            log += "sidebar.focusSearch"
        }

        override fun hideIme() {
            log += "hideIme"
        }

        override fun toast(text: String) {
            log += "toast($text)"
        }
    }

    /** A session route that logs arrival / reveal / discard into the shell's log. */
    private class FakeRoute(override val chatId: String, private val log: MutableList<String>) : SessionRoute {
        @Composable
        override fun Content() {}

        override fun prepareArrival() {
            log += "prepareArrival($chatId)"
        }

        override fun finishArrival() {
            log += "finishArrival($chatId)"
        }

        override fun discard() {
            log += "discard($chatId)"
        }
    }

    /** The split passes no completion — the animator logs and runs it at once. */
    private class FakeAnimator(private val log: MutableList<String>) : HandoffAnimator {
        override fun run(handoff: DraftHandoff, session: SessionRoute, onFinished: () -> Unit) {
            log += "animator.run(${session.chatId})"
            onFinished()
        }
    }

    /** The phone router while collapsed: every entry point logs `phone.*`. */
    private class FakePhoneRouter(private val log: MutableList<String>) : AppRouter {
        override fun openSession(chatId: String) {
            log += "phone.openSession($chatId)"
        }

        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            log += "phone.openSession($chatId,handoff=${handoff != null})"
        }

        override fun presentNewSession(prompt: String?) {
            log += "phone.presentNewSession(${prompt ?: "null"})"
        }

        override fun showSettings() {
            log += "phone.showSettings"
        }

        override fun showSearch() {
            log += "phone.showSearch"
        }

        override fun showSessionOpenError() {
            log += "phone.showSessionOpenError"
        }

        override fun popToFrontPage() {
            log += "phone.popToFrontPage"
        }
    }

    private fun handoff(): DraftHandoff = object : DraftHandoff {}

    // -------------------------------------------------------------- launch

    @Test
    fun launch_showsDraftWithoutFocus() = runTest {
        val r = rig(testScheduler)
        // `viewDidLoad` ends with showDraft(prompt: nil, focus: false)
        // (`:50`): the draft page, no keyboard, nothing else — the factory
        // is never called and nothing is open.
        assertEquals(listOf("draft(null,false)"), r.log)
        advanceUntilIdle()
        assertEquals(listOf("draft(null,false)"), r.log)
        assertNull(r.shell.currentChatId)
        assertEquals(0, r.opens)
    }

    @Test
    fun launch_restoredSessionRoot_reopens() = runTest {
        // Process-death restore (33 saves DetailRoot): the session reopens
        // through the normal open.
        val r = rig(testScheduler, DetailRoot.Session("c1"))
        advanceUntilIdle()
        assertEquals(listOf("current=c1", "session(c1)"), r.log)
        assertEquals(1, r.opens)
        assertEquals("c1", r.shell.currentChatId)

        // The missing variant: the error sequence (no leading animated
        // dismissal — the restore open has none of openSession's steps 1-5).
        val m = rig(testScheduler, DetailRoot.Session("missing1"))
        advanceUntilIdle()
        assertEquals(errorTail, m.log)
        assertEquals(1, m.opens)
    }

    // ---------------------------------------------------- openSession(id)

    @Test
    fun openSession_expanded_replacesDetail() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        advanceUntilIdle()
        // Fact 1 + 5: the animated dismissal first, then (after the off-main
        // open) the highlight and the unanimated detail replacement in one
        // main-thread turn. The Rig's factory checks io.inOpen.
        assertEquals(listOf("dismissPresented(true)", "current=c1", "session(c1)"), r.log)
        assertEquals(1, r.opens)
    }

    @Test
    fun openSession_withHandoff_isPlainOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1", handoff())
        advanceUntilIdle()
        // The only tablet handoff producer is the embedded draft's
        // onCreated; a handoff passed here is ignored.
        assertEquals(listOf("dismissPresented(true)", "current=c1", "session(c1)"), r.log)
    }

    @Test
    fun openSession_sameIdShowing_noop() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        advanceUntilIdle()
        r.log.clear()
        // Fact 3: same id and a session in the column — only the dismissal
        // happens; no reload, no re-highlight, no second factory call.
        r.router.openSession("c1")
        advanceUntilIdle()
        assertEquals(listOf("dismissPresented(true)"), r.log)
        assertEquals(1, r.opens)
    }

    @Test
    fun openSession_samePendingId_opensOnce() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.openSession("c1")
        advanceUntilIdle()
        // A second open for an id already pending is ignored (double taps
        // don't open twice) — both taps still dismiss animated.
        assertEquals(
            listOf("dismissPresented(true)", "dismissPresented(true)", "current=c1", "session(c1)"),
            r.log,
        )
        assertEquals(1, r.opens)
    }

    @Test
    fun openSession_sameIdTap_supersedesOtherPending() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        advanceUntilIdle()
        r.log.clear()
        // Showing c1, c2 pending, then a tap on c1 again: the same-id return
        // still invalidates the pending c2 — the latest intent wins.
        r.router.openSession("c2")
        r.router.openSession("c1")
        advanceUntilIdle()
        assertEquals(listOf("dismissPresented(true)", "dismissPresented(true)", "discard(c2)"), r.log)
        // c1's first open plus c2's: c2's route was built (off main) before
        // it was discarded — `discard(c2)` above is that route's — so the
        // factory ran twice; the same-id tap on c1 adds no third call.
        assertEquals(2, r.opens)
        assertTrue(r.log.none { it.startsWith("session(c2)") })
    }

    @Test
    fun openSession_unknown_errorRecovery() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("missing1")
        advanceUntilIdle()
        // The animated dismissal, then the whole `:83-90` recovery; no
        // session(…) entry.
        assertEquals(listOf("dismissPresented(true)") + errorTail, r.log)
        assertNull(r.shell.currentChatId)
    }

    @Test
    fun openSession_factoryThrows_sameAsUnknown() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("boom")
        advanceUntilIdle()
        // Factory errors are logged ("open session failed") and treated as
        // the unknown-chat path.
        assertEquals(listOf("dismissPresented(true)") + errorTail, r.log)
    }

    @Test
    fun openSession_collapsed_delegatesAfterDismiss() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.collapsed = true
        r.router.openSession("c1")
        advanceUntilIdle()
        // Fact 2, after fact 1: the tablet shell sees only the animated
        // dismissal; the phone router gets the open; nothing opens here.
        assertEquals(listOf("dismissPresented(true)", "phone.openSession(c1)"), r.log)
        assertEquals(0, r.opens)
    }

    @Test
    fun openSession_collapsedWhilePending_discardsOnly() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.shell.collapsed = true
        advanceUntilIdle()
        // 108's "stale results": discard and nothing else — no phone call,
        // no toast, no error sequence.
        assertEquals(listOf("dismissPresented(true)", "discard(c1)"), r.log)
        assertTrue(r.log.none { it.startsWith("phone.") || it.startsWith("toast(") })

        // The same with an id the core can't open: still no error sequence
        // (the collapse check runs before the null check).
        val m = rig(testScheduler)
        m.log.clear()
        m.router.openSession("missing1")
        m.shell.collapsed = true
        advanceUntilIdle()
        assertEquals(listOf("dismissPresented(true)"), m.log)
    }

    // --------------------------------------------------------- supersede

    @Test
    fun latestOpenWins_staleRouteDiscarded() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.openSession("c2")
        advanceUntilIdle()
        assertEquals(
            listOf("dismissPresented(true)", "dismissPresented(true)", "discard(c1)", "current=c2", "session(c2)"),
            r.log,
        )
        assertEquals(2, r.opens)
    }

    @Test
    fun showDraft_supersedesPendingOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.presentNewSession("hi")
        advanceUntilIdle()
        // presentNewSession → showDraft: the highlight clears first, the
        // draft replaces the column, and the pending open dies.
        assertEquals(listOf("dismissPresented(true)", "current=null", "draft(hi,true)", "discard(c1)"), r.log)
    }

    @Test
    fun showSettingsAndSearch_doNotSupersede() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.showSettings()
        r.router.showSearch()
        advanceUntilIdle()
        // Neither touches the detail column, so neither bumps the
        // generation: the pending open applies.
        assertEquals(
            listOf("dismissPresented(true)", "presentSettings", "sidebar.focusSearch", "current=c1", "session(c1)"),
            r.log,
        )
        assertEquals(1, r.opens)
        assertTrue(r.log.none { it.startsWith("discard(") })
    }

    @Test
    fun showSessionOpenError_supersedesPendingOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.showSessionOpenError()
        advanceUntilIdle()
        // The recovery runs synchronously; the pending open dies after it.
        assertEquals(listOf("dismissPresented(true)") + errorTail + listOf("discard(c1)"), r.log)
    }

    @Test
    fun showSessionInDetail_supersedesPendingOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1")
        r.router.showSessionInDetail("c2")
        advanceUntilIdle()
        assertEquals(listOf("dismissPresented(true)", "discard(c1)", "current=c2", "session(c2)"), r.log)
    }

    // ------------------------------------------------- presentNewSession

    @Test
    fun presentNewSession_expanded_focusedDraft() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.presentNewSession("fix the build")
        advanceUntilIdle()
        // Fact 7: no dismissal; a fresh focused draft (⌘N).
        assertEquals(listOf("current=null", "draft(fix the build,true)"), r.log)
        assertEquals(0, r.opens)
    }

    @Test
    fun presentNewSession_collapsed_delegates() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.collapsed = true
        r.router.presentNewSession("hi")
        advanceUntilIdle()
        assertEquals(listOf("phone.presentNewSession(hi)"), r.log)
    }

    // ------------------------------------------------ draft onCreated

    @Test
    fun draftCreated_handoff_inPlaceOrder() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.onCreated?.let { it("c9", handoff()) }
        advanceUntilIdle()
        // Fact 9, in one main-thread turn: the highlight, the arrival prep
        // (before the swap), the swap, the IME down, then the animator —
        // and no dismissal anywhere.
        assertEquals(
            listOf("current=c9", "prepareArrival(c9)", "session(c9)", "hideIme", "animator.run(c9)"),
            r.log,
        )
        assertEquals(1, r.opens)
    }

    @Test
    fun draftCreated_nullHandoff_plainOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.onCreated?.let { it("c9", null) }
        advanceUntilIdle()
        // No handoff → exactly the plain open path.
        assertEquals(listOf("dismissPresented(true)", "current=c9", "session(c9)"), r.log)
        assertTrue(r.log.none { it.startsWith("prepareArrival") || it.startsWith("animator.run") })
    }

    @Test
    fun draftCreated_failedOpen_errorRecovery() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.onCreated?.let { it("missing9", handoff()) }
        advanceUntilIdle()
        // The open failed: the error recovery, no arrival, no animator —
        // and no leading animated dismissal (only openSession has one).
        assertEquals(errorTail, r.log)
        assertTrue(r.log.none { it.startsWith("prepareArrival") || it.startsWith("animator.run") })
    }

    @Test
    fun draftCreated_collapsed_delegatesPlainOpen() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.collapsed = true
        r.shell.onCreated?.let { it("c9", handoff()) }
        advanceUntilIdle()
        // Collapsed: the handoff is dropped and the plain open runs —
        // which dismisses animated, then delegates.
        assertEquals(listOf("dismissPresented(true)", "phone.openSession(c9)"), r.log)
    }

    // ------------------------------------------- showSettings / showSearch

    @Test
    fun showSettings_presentsOnce_collapsedDelegates() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.showSettings()
        // UIKit refuses a second presentation while the card is up.
        r.router.showSettings()
        advanceUntilIdle()
        assertEquals(listOf("presentSettings"), r.log)
        r.shell.collapsed = true
        r.router.showSettings()
        advanceUntilIdle()
        assertEquals(listOf("presentSettings", "phone.showSettings"), r.log)
    }

    @Test
    fun showSearch_hiddenSidebar_revealsThenFocuses() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.sidebar = false
        r.router.showSearch()
        advanceUntilIdle()
        // `show(.primary)` then `focusSearch()`, at once (no settle wait).
        assertEquals(listOf("showSidebar", "sidebar.focusSearch"), r.log)
        assertTrue(r.shell.sidebar)
    }

    @Test
    fun showSearch_visibleSidebar_focusOnly() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.showSearch()
        advanceUntilIdle()
        assertEquals(listOf("sidebar.focusSearch"), r.log)
    }

    @Test
    fun showSearch_collapsed_delegates() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.collapsed = true
        r.router.showSearch()
        advanceUntilIdle()
        assertEquals(listOf("phone.showSearch"), r.log)
    }

    // ------------------------------------------------ open-error / pop

    @Test
    fun showSessionOpenError_collapsed_delegates() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.shell.collapsed = true
        r.router.showSessionOpenError()
        advanceUntilIdle()
        // Collapsed: invalidate + the phone router; the tablet shell sees
        // nothing at all.
        assertEquals(listOf("phone.showSessionOpenError"), r.log)
    }

    @Test
    fun popToFrontPage_collapsedOnly() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        // The split has no front-page stack: expanded does nothing.
        r.router.popToFrontPage()
        advanceUntilIdle()
        assertTrue(r.log.isEmpty())
        r.shell.collapsed = true
        r.router.popToFrontPage()
        advanceUntilIdle()
        assertEquals(listOf("phone.popToFrontPage"), r.log)
    }

    // -------------------------------------------------------- presence

    @Test
    fun presence_appearAndClose_rules() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        // Both paths of sessionDidAppear record the id (the highlight).
        r.router.sessionDidAppear("c3")
        r.router.tabPresence.sessionDidAppear("c3")
        assertEquals(listOf("current=c3", "current=c3"), r.log)
        assertEquals("c3", r.shell.currentChatId)

        // AppRouter.sessionDidClose is a no-op: a detail-column session
        // (iOS tabBarController == nil) never reports a close.
        r.router.sessionDidClose("c3")
        assertEquals("c3", r.shell.currentChatId)
        assertEquals(2, r.log.size)

        // tabPresence close, expanded: nothing.
        r.router.tabPresence.sessionDidClose("c3")
        assertEquals("c3", r.shell.currentChatId)
        assertEquals(2, r.log.size)

        // tabPresence close, collapsed + same id: cleared.
        r.shell.collapsed = true
        r.router.tabPresence.sessionDidClose("c3")
        assertNull(r.shell.currentChatId)

        // Collapsed + another id: nothing.
        r.shell.currentChatId = "c5"
        r.router.tabPresence.sessionDidClose("c9")
        assertEquals("c5", r.shell.currentChatId)
    }

    // --------------------------------------------- leaveArchivedSession

    @Test
    fun leaveArchivedSession_expandedShowsDraft_collapsedFalse() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.openSession("c1") // pending
        // Expanded: the column shows the draft (focus false) and the
        // pending open dies; the archived session has nothing to pop.
        assertTrue(r.router.leaveArchivedSession("c1"))
        advanceUntilIdle()
        assertEquals(listOf("dismissPresented(true)", "current=null", "draft(null,false)", "discard(c1)"), r.log)

        // Collapsed: false — the phone screen pops itself.
        r.shell.collapsed = true
        assertFalse(r.router.leaveArchivedSession("c1"))
        advanceUntilIdle()
        assertEquals(4, r.log.size)
    }

    // ------------------------------------------------------ phone slot

    @Test
    fun phoneSlot_keepsLatestUntilAttached_clearedByExpandedCall() = runTest {
        val r = rig(testScheduler)
        // The phone shell leaves: nothing attached.
        r.phoneSlot.detach(r.phone)
        r.log.clear()

        // A collapsed call with no phone router: kept, not dropped.
        r.shell.collapsed = true
        r.router.showSettings()
        assertTrue(r.log.isEmpty())

        // MainTabShell comes back and attaches: the kept call runs.
        r.phoneSlot.attach(r.phone)
        assertEquals(listOf("phone.showSettings"), r.log)

        // It leaves again; another call is kept…
        r.phoneSlot.detach(r.phone)
        r.router.popToFrontPage()
        assertEquals(listOf("phone.showSettings"), r.log)

        // …and an expanded router call (showSettings → phone.clearPending)
        // drops it: attaching again runs nothing.
        r.shell.collapsed = false
        r.router.showSettings()
        advanceUntilIdle()
        assertEquals(listOf("phone.showSettings", "presentSettings"), r.log)
        r.phoneSlot.attach(r.phone)
        assertEquals(listOf("phone.showSettings", "presentSettings"), r.log)
    }

    // ------------------------------------ the 108 seam: detail column

    @Test
    fun showSessionInDetail_noDismiss_noSameIdReturn() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        // Something is presented (the fake's Settings card flag — the
        // fake logs any dismissPresented call): 108's
        // SplitDetailColumn.showSession never dismisses it.
        r.shell.settingsUp = true
        r.router.showSessionInDetail("c4")
        advanceUntilIdle()
        assertEquals(listOf("current=c4", "session(c4)"), r.log)
        r.log.clear()

        // While c4 is already shown it opens again (no same-id return).
        r.router.showSessionInDetail("c4")
        advanceUntilIdle()
        assertEquals(listOf("current=c4", "session(c4)"), r.log)
        assertEquals(2, r.opens)
    }

    @Test
    fun showSessionInDetail_resolvesWhileCollapsed_discards() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.showSessionInDetail("c1")
        // Narrowed again before the open resolves: the late route is
        // discarded, nothing else — no session, no phone call, no toast,
        // no error sequence (108's "stale results" contract; the next
        // collapse owns the chat).
        r.shell.collapsed = true
        advanceUntilIdle()
        assertEquals(listOf("discard(c1)"), r.log)
        assertTrue(r.log.none { it.startsWith("phone.") || it.startsWith("toast(") })
        // The open did run (the route was built off main, then discarded);
        // nothing reached the column.
        assertEquals(1, r.opens)
    }

    @Test
    fun showSessionInDetail_null_showsOpenError() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        // An unknown chat on expand (the carried id was archived while
        // compact): the split's open-error recovery — and no leading
        // animated dismissal (only openSession has one).
        r.router.showSessionInDetail("missing1")
        advanceUntilIdle()
        assertEquals(errorTail, r.log)
        assertNull(r.shell.currentChatId)
    }

    @Test
    fun clearDetail_invalidatesPendingAndEmpties() = runTest {
        val r = rig(testScheduler)
        r.log.clear()
        r.router.sessionDidAppear("c3") // current=c3
        r.router.openSession("c1") // pending
        // clear(): the pending open dies, the column empties (once), and
        // currentChatId is untouched (108 has already captured it).
        r.router.clearDetail()
        advanceUntilIdle()
        assertEquals(listOf("current=c3", "dismissPresented(true)", "empty", "discard(c1)"), r.log)
        assertEquals("c3", r.shell.currentChatId)
        assertFalse(r.shell.showsSession)

        // Already empty: nothing, not even a second "empty".
        r.router.clearDetail()
        advanceUntilIdle()
        assertEquals(4, r.log.size)
    }

    // ------------------------------------------- scope cancelled mid-open

    @Test
    fun scopeCancelledMidOpen_routeDiscarded() = runTest {
        val r = rig(testScheduler)
        val job = Job()
        val scope = CoroutineScope(r.main + job)
        val router = TabletAppRouter(
            shell = r.shell,
            hooks = r.hooks,
            scope = scope,
            phone = r.phoneSlot,
            openDispatcher = r.io,
            log = { _, _ -> },
        )
        r.log.clear()
        r.openImpl = { id ->
            // Cancel the router's scope while the factory is running; the
            // route is still built and returned.
            job.cancel()
            FakeRoute(id, r.log)
        }
        router.openSession("c1")
        advanceUntilIdle()
        // withContext throws CancellationException on resume;
        // openSessionRoute discards the built route on the caller's
        // (main) dispatcher first: no session, no toast.
        assertEquals(listOf("dismissPresented(true)", "discard(c1)"), r.log)
    }
}
