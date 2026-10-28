package sh.roboco.android.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Choreographer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassMenuHostState
import sh.roboco.android.design.glass.LocalGlassMenuHost
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.sessions.ActionOutcome
import sh.roboco.android.sessions.LocalRowActions
import sh.roboco.android.sessions.RowActions
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The ⋯ menu's content and actions (android-ui-parity #80): the pure
 * [sessionMenu] builder, [copyTranscript] (the off-main `plainText`, the
 * platform clipboard, the API 31–32 "Copied" and the failure toast),
 * [leaveAfterArchive] (router first, else the guarded pop), the
 * controller's cached pin state — and the [rememberSessionMenu] wiring
 * through a real [GlassMenuHost] and [SessionHeader] (Robolectric +
 * compose-ui-test, the clock frozen and advanced like ticket 15's
 * behaviour tests).
 *
 * The Binder-limit failure row lives in [SessionMenuClipboardFailureTest]
 * (a custom shadow, class-scoped). The goldens live in
 * [SessionMenuScreenshotTest]; the end-to-end copy on a paired engine in
 * the instrumented `SessionFlowTest` (CI-only). The transcript fakes
 * follow [TranscriptState]'s test conventions (`NoHandle` engines whose
 * overridden members touch no JNA).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ the fakes

    /**
     * The [RowActions] fake: records pin/archive calls, reports the
     * configured [outcome], and captures the menu's close state at the
     * moment `archive` ran (the "close first, then act" order).
     */
    private class MenuActions(
        private val menuIsClosing: () -> Boolean,
        var outcome: ActionOutcome = ActionOutcome.Ok,
    ) : RowActions {
        val pins = mutableListOf<Pair<String, Boolean>>()
        val archives = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var isClosingAtArchive: Boolean? = null
            private set

        override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
            pins += chatId to pinned
        }

        override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            archives += chatId
            isClosingAtArchive = menuIsClosing()
            onResult(outcome)
        }

        override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
        override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
            deletes += chatId
            onResult(outcome)
        }
        override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {}
        override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {}
        override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {}
        override fun createSection(
            name: String,
            engineId: String?,
            onResult: (ActionOutcome) -> Unit,
            onCreated: (String) -> Unit,
        ) {}
        override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {}
        override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {}
        override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
        override fun report(error: Throwable) {}
    }

    /** The [Alerts] fake: records every alert instead of presenting it. */
    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }
        override fun updateCurrent(alert: RobocoAlert) {}
        override fun dismissAll() {}
    }

    /** The [AppRouter] fake: counts the leave calls and reports [leave]. */
    private class RecordingRouter(private val leave: Boolean = false) : AppRouter {
        val leaves = mutableListOf<String>()
        override fun openSession(chatId: String) {}
        override fun openSession(chatId: String, handoff: DraftHandoff?) {}
        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() {}
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
        override fun leaveArchivedSession(chatId: String): Boolean {
            leaves += chatId
            return leave
        }
    }

    /** Fills `canWrite` from a mutable field (79's seam, test-shaped). */
    private class MenuCanWriteFiller : ChromeFiller {
        @Volatile
        var value: Boolean = true
        override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
            chrome.copy(canWrite = value)
    }

    private object MenuRootRoute : NavRoute
    private object MenuSessionRoute : NavRoute

    /** The composition's handle on the menu host (for is-closing probes). */
    private class HostRef {
        var host: GlassMenuHostState? = null
        var opens = 0
    }

    // ------------------------------------------------------------ helpers

    private fun syncHub() = SessionEventHub(post = { it.run() })

    private fun newController(
        core: FakeSessionCore,
        hub: SessionEventHub = syncHub(),
        filler: ChromeFiller? = null,
    ): SessionController = SessionController.create(
        core = core,
        hub = hub,
        fillers = if (filler != null) listOf(filler) else emptyList(),
        io = Dispatchers.Unconfined,
        isMainThread = { false },
    )

    /** Delivers pending writes and runs the frame that recomposes them. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settleOpen() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        frame()
    }

    private fun finishClose() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(250)
        frame()
    }

    /**
     * The real wiring under test: [SessionHeader] inside a [GlassMenuHost],
     * with the ambient router/actions/toaster/stack/entry provided and
     * [rememberSessionMenu] as the header's `onMore`. [ref] captures the
     * host for close-state probes and counts the opens.
     */
    private fun host(
        controller: SessionController,
        transcript: TranscriptState,
        actions: RowActions,
        router: AppRouter,
        stack: NavStack,
        entry: NavEntry,
        ref: HostRef,
        alerts: Alerts = Alerts.Noop,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost {
                    ref.host = LocalGlassMenuHost.current
                    CompositionLocalProvider(
                        LocalRowActions provides actions,
                        LocalAppRouter provides router,
                        LocalToaster provides RecordingToaster(),
                        LocalNavStack provides stack,
                        LocalNavEntry provides entry,
                        LocalAlerts provides alerts,
                    ) {
                        val wired = rememberSessionMenu(controller, transcript)
                        SessionHeader(
                            title = "Fix the deploy script",
                            subtitle = "roboco @ studio",
                            edgeEngaged = false,
                            onMore = { anchor ->
                                ref.opens++
                                wired(anchor)
                            },
                            showBack = true,
                            onBack = {},
                            statusBarTop = 24.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithTag("session-more").performClick()
        frame()
        settleOpen()
        compose.onNodeWithTag("glass-menu").assertExists()
    }

    // ------------------------------------------------- the pure builder

    @Test
    fun unpinnedWritableMenuIsFourRowsInOrderWithNoTitle() {
        val menu = sessionMenu(
            pinned = false,
            canWrite = true,
            onSetPinned = {},
            onCopyTranscript = {},
            onShowPreviews = {},
            onShowAccounts = {},
            onArchive = {},
        )
        assertNull("no menu title, as UIMenu(children:)", menu.title)
        assertEquals(5, menu.children.size)
        val pin = menu.children[0] as MenuItem
        val copy = menu.children[1] as MenuItem
        val previews = menu.children[2] as MenuItem
        val accounts = menu.children[3] as MenuItem
        val archive = menu.children[4] as MenuItem

        assertEquals("Pin", pin.title)
        assertEquals(RobocoIcons.Pin, (pin.icon as MenuIcon.Symbol).icon)
        assertFalse(pin.destructive)
        assertFalse(pin.disabled)
        assertNull(pin.subtitle)
        assertFalse(pin.checked)

        assertEquals("Copy Transcript", copy.title)
        assertEquals(RobocoIcons.DocOnDoc, (copy.icon as MenuIcon.Symbol).icon)
        assertFalse(copy.destructive)
        assertFalse(copy.disabled)

        assertEquals("Previews", previews.title)
        assertEquals(RobocoIcons.Safari, (previews.icon as MenuIcon.Symbol).icon)
        assertFalse(previews.destructive)
        assertFalse("the panel owns its own honest states", previews.disabled)

        assertEquals("Account & Usage", accounts.title)
        assertEquals(RobocoIcons.Gauge, (accounts.icon as MenuIcon.Symbol).icon)
        assertFalse(accounts.destructive)
        assertFalse("cached reads only", accounts.disabled)

        assertEquals("Archive", archive.title)
        assertEquals(RobocoIcons.Archivebox, (archive.icon as MenuIcon.Symbol).icon)
        assertFalse("upstream #967: Archive is no longer destructive", archive.destructive)
        assertFalse(archive.disabled)

        assertEquals(
            listOf(
                "session-menu-pin",
                "session-menu-copy",
                "session-menu-previews",
                "session-menu-accounts",
                "session-menu-archive",
            ),
            listOf(pin.testTag, copy.testTag, previews.testTag, accounts.testTag, archive.testTag),
        )
    }

    @Test
    fun deleteTrailsArchiveDestructiveAndTheTitleHeadsTheMenu() {
        var deletes = 0
        val menu = sessionMenu(
            pinned = false,
            canWrite = true,
            onSetPinned = {},
            onCopyTranscript = {},
            onShowPreviews = {},
            onShowAccounts = {},
            onArchive = {},
            onDelete = { deletes++ },
            title = "Fix the deploy script",
            subtitle = "roboco @ studio",
        )
        assertEquals("Fix the deploy script\nroboco @ studio", menu.title)
        assertEquals(6, menu.children.size)
        val delete = menu.children[5] as MenuItem
        assertEquals("Delete", delete.title)
        assertEquals(RobocoIcons.Trash, (delete.icon as MenuIcon.Symbol).icon)
        assertTrue(delete.destructive)
        assertFalse(delete.disabled)
        assertEquals("session-menu-delete", delete.testTag)
        delete.action()
        assertEquals(1, deletes)

        val readOnly = sessionMenu(false, false, {}, {}, {}, {}, {}, onDelete = {})
        assertTrue("Delete disabled with Archive while read-only", (readOnly.children[5] as MenuItem).disabled)
    }

    @Test
    fun theHeaderIsTheTitleOverWhereItRuns() {
        assertNull(sessionMenuHeader("", ""))
        assertEquals("Fix", sessionMenuHeader("Fix", ""))
        assertEquals("roboco @ studio", sessionMenuHeader("", "roboco @ studio"))
        assertEquals("Fix\nroboco @ studio", sessionMenuHeader("Fix", "roboco @ studio"))
    }

    @Test
    fun pinnedMenuTitlesUnpinWithTheSlashIcon() {
        val menu = sessionMenu(
            pinned = true,
            canWrite = true,
            onSetPinned = {},
            onCopyTranscript = {},
            onShowPreviews = {},
            onShowAccounts = {},
            onArchive = {},
        )
        val unpin = menu.children[0] as MenuItem
        assertEquals("Unpin", unpin.title)
        assertEquals(RobocoIcons.PinSlash, (unpin.icon as MenuIcon.Symbol).icon)
    }

    @Test
    fun readOnlyDisablesPinAndArchiveButNeverCopy() {
        val menu = sessionMenu(
            pinned = false,
            canWrite = false,
            onSetPinned = {},
            onCopyTranscript = {},
            onShowPreviews = {},
            onShowAccounts = {},
            onArchive = {},
        )
        assertTrue("Pin disabled while read-only", (menu.children[0] as MenuItem).disabled)
        assertFalse("Copy Transcript reads local state only", (menu.children[1] as MenuItem).disabled)
        assertFalse("Previews is never disabled", (menu.children[2] as MenuItem).disabled)
        assertFalse("Account & Usage is never disabled", (menu.children[3] as MenuItem).disabled)
        assertTrue("Archive disabled while read-only", (menu.children[4] as MenuItem).disabled)
    }

    @Test
    fun theAccountsRowRunsItsHandler() {
        var shown = 0
        val menu = sessionMenu(false, true, {}, {}, {}, { shown++ }, {})
        (menu.children[3] as MenuItem).action()
        assertEquals(1, shown)
    }

    @Test
    fun thePinActionInvertsThePinnedState() {
        val pins = mutableListOf<Boolean>()
        var copies = 0
        var previews = 0
        var accounts = 0
        var archives = 0
        val unpinned = sessionMenu(false, true, { pins += it }, { copies++ }, { previews++ }, { accounts++ }, { archives++ })
        (unpinned.children[0] as MenuItem).action()
        assertEquals(listOf(true), pins)
        assertEquals(0, copies)
        assertEquals(0, archives)

        pins.clear()
        val pinned = sessionMenu(true, true, { pins += it }, { copies++ }, { previews++ }, { accounts++ }, { archives++ })
        (pinned.children[0] as MenuItem).action()
        assertEquals(listOf(false), pins)

        (pinned.children[1] as MenuItem).action()
        assertEquals(1, copies)
        (pinned.children[2] as MenuItem).action()
        assertEquals(1, previews)
        (pinned.children[3] as MenuItem).action()
        assertEquals(1, accounts)
        (pinned.children[4] as MenuItem).action()
        assertEquals(1, archives)
    }

    // --------------------------------------------- the controller's pin cache

    @Test
    fun controllerPinnedFollowsTheRefreshedRowAndFallsBackToFalse() {
        val core = FakeSessionCore("c1")
        val hub = syncHub()
        val controller = newController(core, hub)
        assertEquals("an unknown row reads false, like app.session(id)?.pinned", false, controller.pinned)

        core.sessionRowResult = sessionRow(pinned = true)
        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        assertEquals(true, controller.pinned)

        core.sessionRowResult = null
        hub.onClientEvent(ClientEvent.WorkspaceChanged(3uL))
        assertEquals(false, controller.pinned)
    }

    // ------------------------------------------------ leaveAfterArchive

    @Test
    fun okArchiveAsksTheRouterThenPopsWhenTopAndIdle() {
        val router = RecordingRouter(leave = false)
        var topAndIdle = false
        var pops = 0
        leaveAfterArchive("c1", router, isTopAndIdle = { topAndIdle }, pop = { pops++ })
        assertEquals("the router is always asked", listOf("c1"), router.leaves)
        assertEquals("not top and idle: no pop", 0, pops)

        topAndIdle = true
        leaveAfterArchive("c1", router, isTopAndIdle = { topAndIdle }, pop = { pops++ })
        assertEquals(2, router.leaves.size)
        assertEquals("top and idle: one pop", 1, pops)
    }

    @Test
    fun aRouterHandedLeaveSuppressesThePop() {
        val router = RecordingRouter(leave = true)
        var pops = 0
        leaveAfterArchive("c1", router, isTopAndIdle = { true }, pop = { pops++ })
        assertEquals(listOf("c1"), router.leaves)
        assertEquals("the tablet branch moved away itself", 0, pops)
    }

    @Test
    fun goneArchiveLeavesExactlyLikeOk() {
        val router = RecordingRouter(leave = false)
        var pops = 0
        // The Gone branch is the same leave; only the toast differs (42).
        val leave: (ActionOutcome) -> Unit = { outcome ->
            if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) {
                leaveAfterArchive("c1", router, isTopAndIdle = { true }, pop = { pops++ })
            }
        }
        leave(ActionOutcome.Gone)
        assertEquals(listOf("c1"), router.leaves)
        assertEquals(1, pops)
    }

    @Test
    fun refusedOutcomesNeverLeave() {
        val router = RecordingRouter(leave = false)
        var pops = 0
        val leave: (ActionOutcome) -> Unit = { outcome ->
            if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) {
                leaveAfterArchive("c1", router, isTopAndIdle = { true }, pop = { pops++ })
            }
        }
        listOf(
            ActionOutcome.EngineOffline,
            ActionOutcome.Failed,
            ActionOutcome.NoClient,
            ActionOutcome.PinsNotSynced,
            ActionOutcome.Skipped,
        ).forEach { leave(it) }
        assertTrue("refused writes record neither a router call nor a pop", router.leaves.isEmpty())
        assertEquals(0, pops)
    }

    @Test
    fun aLateOkPopsOnlyWhileTheEntryIsTopAndIdleOnARealStack() {
        val router = RecordingRouter(leave = false)
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        val isTopAndIdle = { stack.top == entry && stack.transition == null }

        // Top and idle: the pop commits.
        leaveAfterArchive("c1", router, isTopAndIdle, pop = { stack.pop(animated = true) })
        assertEquals(1, stack.entries.size)
        assertFalse(stack.entries.contains(entry))

        // A late Ok after the user pushed another screen: our entry is no
        // longer top — nothing happens.
        val second = stack.push(MenuRootRoute, animated = false)
        leaveAfterArchive("c1", router, isTopAndIdle, pop = { stack.pop(animated = true) })
        assertEquals(2, stack.entries.size)
        assertTrue(stack.entries.contains(second))

        // A running transition is not idle either.
        stack.holdHostlessOps()
        val third = stack.push(MenuRootRoute, animated = false)
        assertTrue(stack.transition != null)
        leaveAfterArchive("c1", router, isTopAndIdle, pop = { stack.pop(animated = true) })
        assertEquals(3, stack.entries.size)
        assertTrue(stack.entries.contains(third))
    }

    // ------------------------------------------------------ copyTranscript

    @Test
    fun copyBuildsTheTextOffMainClosesTheFreshWrapperAndSetsTheClip() = runBlocking {
        val engine = MenuEngine()
        val staged = MenuFrame("a\n\n---\n\nb")
        engine.next = staged
        val state = newTranscript(engine)
        val toaster = RecordingToaster()
        val work = copyWork()
        try {
            copyTranscript(state, appClipboard(), toaster, sdkInt = 33, work = work)
        } finally {
            work.close()
        }

        assertEquals("the fresh wrapper came from the engine", 1, engine.frameCalls)
        assertEquals("plainText ran once", 1, staged.plainTextCalls)
        assertEquals(
            "plainText ran on the work dispatcher's thread, not main",
            "session-menu-copy",
            staged.plainTextThread?.name,
        )
        assertFalse(staged.plainTextThread === Thread.currentThread())
        assertEquals("the fresh wrapper is closed exactly once", 1, staged.closeCalls)

        val clip = appClipboard().primaryClip
        assertEquals("Transcript", clip?.description?.label?.toString())
        assertEquals("a\n\n---\n\nb", clip?.getItemAt(0)?.text?.toString())
        assertTrue("API 33+: no toast of our own", toaster.shown.isEmpty())
    }

    @Test
    fun copyToastsCopiedOnceWithNoActionOnApi32Only() = runBlocking {
        val engine = MenuEngine()
        engine.next = MenuFrame("hi")
        val state = newTranscript(engine)
        val toaster = RecordingToaster()
        val work = copyWork()
        try {
            copyTranscript(state, appClipboard(), toaster, sdkInt = 32, work = work)
        } finally {
            work.close()
        }
        assertEquals(listOf("Copied"), toaster.texts)
        assertNull("the toast carries no action", toaster.shown[0].action)
    }

    @Test
    fun aClosedTranscriptCopiesTheHeldFrameWithoutTouchingTheEngineOrClosingIt() = runBlocking {
        val engine = MenuEngine()
        val state = newTranscript(engine)
        val held = MenuFrame("kept", rev = 1uL)
        state.apply(held)
        state.close()
        val toaster = RecordingToaster()
        val work = copyWork()
        try {
            copyTranscript(state, appClipboard(), toaster, sdkInt = 33, work = work)
        } finally {
            work.close()
        }

        assertEquals("after close, every engine call would throw — none was made", 0, engine.frameCalls)
        assertEquals(1, held.plainTextCalls)
        assertEquals("the held frame belongs to the state: never closed", 0, held.closeCalls)
        assertEquals("kept", appClipboard().primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(toaster.shown.isEmpty())
    }

    @Test
    fun aClosedTranscriptWithNoFrameCopiesTheEmptyString() = runBlocking {
        val state = newTranscript(MenuEngine())
        state.close()
        val toaster = RecordingToaster()
        val work = copyWork()
        try {
            copyTranscript(state, appClipboard(), toaster, sdkInt = 33, work = work)
        } finally {
            work.close()
        }
        assertEquals("", appClipboard().primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(toaster.shown.isEmpty())
    }

    // -------------------------------------------- the wiring (compose + host)

    @Test
    fun openingTheMenuThreeTimesBuildsPerOpenAndFollowsThePinState() {
        val core = FakeSessionCore("c1")
        val hub = syncHub()
        val controller = newController(core, hub)
        val transcript = newTranscript(MenuEngine())
        val actions = MenuActions({ true })
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        val ref = HostRef()
        compose.mainClock.autoAdvance = true
        host(controller, transcript, actions, RecordingRouter(), stack, entry, ref)

        fun openAndSettle() {
            compose.onNodeWithTag("session-more").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("glass-menu").assertExists()
        }

        fun closeViaBack() {
            Espresso.pressBack()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        }

        openAndSettle()
        compose.onNodeWithText("Pin").assertExists()
        compose.onNodeWithText("Copy Transcript").assertExists()
        compose.onNodeWithText("Archive").assertExists()
        compose.onNodeWithTag("session-menu-pin").assertExists()
        compose.onNodeWithTag("session-menu-copy").assertExists()
        compose.onNodeWithTag("session-menu-archive").assertExists()

        // The enabled Pin row routes the scoped chat id to RowActions
        // (the pick closes the menu first, then acts on the next frame).
        compose.onNodeWithTag("session-menu-pin").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(listOf("c1" to true), actions.pins)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()

        // A refresh flips the pin state while the menu is closed; the
        // next open reads it from the click lambda — no recomposition.
        core.sessionRowResult = sessionRow(pinned = true)
        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        assertTrue(controller.pinned)

        openAndSettle()
        compose.onNodeWithText("Unpin").assertExists()
        closeViaBack()

        openAndSettle()
        compose.onNodeWithText("Unpin").assertExists()
        closeViaBack()

        assertEquals("the menu was rebuilt on every open", 3, ref.opens)
    }

    @Test
    fun tappingArchiveClosesTheMenuBeforeTheCallThenLeavesOnOk() {
        val core = FakeSessionCore("c1")
        val controller = newController(core)
        val transcript = newTranscript(MenuEngine())
        val ref = HostRef()
        val actions = MenuActions({ ref.host?.isClosing == true })
        val router = RecordingRouter(leave = false)
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        host(controller, transcript, actions, router, stack, entry, ref)
        openMenu()

        compose.onNodeWithTag("session-menu-archive").performClick()
        assertTrue("the close starts first", ref.host!!.isClosing)
        assertTrue("the action has not run yet", actions.archives.isEmpty())

        frame()
        assertEquals(listOf("c1"), actions.archives)
        assertEquals("the menu was already closing when archive ran", true, actions.isClosingAtArchive)
        assertEquals("the router was asked once", listOf("c1"), router.leaves)
        assertEquals("the pop removed our entry from the stack", 1, stack.entries.size)
        assertFalse("the pop removed our entry", stack.entries.contains(entry))

        finishClose()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun deleteAsksFirstThenDeletesAndLeavesOnOk() {
        val core = FakeSessionCore("c1")
        val controller = newController(core)
        val transcript = newTranscript(MenuEngine())
        val ref = HostRef()
        val actions = MenuActions({ ref.host?.isClosing == true })
        val router = RecordingRouter(leave = false)
        val alerts = RecordingAlerts()
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        host(controller, transcript, actions, router, stack, entry, ref, alerts)
        openMenu()

        compose.onNodeWithTag("session-menu-delete").performClick()
        frame()
        assertTrue("nothing is deleted before the confirm", actions.deletes.isEmpty())
        val confirm = alerts.shown.single()
        assertEquals("Delete session?", confirm.title)
        assertEquals(listOf(AlertButtonRole.Cancel, AlertButtonRole.Destructive), confirm.buttons.map { it.role })

        compose.runOnUiThread { confirm.buttons[1].onClick("") }
        compose.waitForIdle()
        assertEquals(listOf("c1"), actions.deletes)
        assertEquals("the router was asked once", listOf("c1"), router.leaves)
        assertFalse("off the deleted session", stack.entries.contains(entry))

        finishClose()
    }

    @Test
    fun aRefusedArchiveStaysOnTheScreen() {
        val core = FakeSessionCore("c1")
        val controller = newController(core)
        val transcript = newTranscript(MenuEngine())
        val ref = HostRef()
        val actions = MenuActions({ ref.host?.isClosing == true }, outcome = ActionOutcome.EngineOffline)
        val router = RecordingRouter(leave = false)
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        host(controller, transcript, actions, router, stack, entry, ref)
        openMenu()

        compose.onNodeWithTag("session-menu-archive").performClick()
        frame()
        assertEquals(listOf("c1"), actions.archives)
        assertEquals("no router call for a refused write", 0, router.leaves.size)
        assertEquals("the screen stays", 2, stack.entries.size)
        assertTrue(stack.entries.contains(entry))

        finishClose()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun readOnlyRowsAreInertAndKeepTheMenuOpen() {
        val core = FakeSessionCore("c1")
        val hub = syncHub()
        val filler = MenuCanWriteFiller()
        val controller = newController(core, hub, filler)
        val transcript = newTranscript(MenuEngine())
        val ref = HostRef()
        val actions = MenuActions({ ref.host?.isClosing == true })
        val stack = NavStack(MenuRootRoute)
        val entry = stack.push(MenuSessionRoute, animated = false)
        host(controller, transcript, actions, RecordingRouter(), stack, entry, ref)

        filler.value = false
        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        openMenu()

        compose.onNodeWithTag("session-menu-pin").assertIsNotEnabled()
        compose.onNodeWithTag("session-menu-archive").assertIsNotEnabled()
        compose.onNodeWithTag("session-menu-copy").assertIsEnabled()

        compose.onNodeWithTag("session-menu-pin").performClick()
        compose.waitForIdle()
        assertTrue("a disabled row does nothing", actions.pins.isEmpty())
        assertFalse("the menu stays open (ticket 15 swallows the tap)", ref.host!!.isClosing)
        compose.onNodeWithTag("glass-menu").assertExists()
    }
}

// ---------------------------------------------------------------------------
// File-level fakes, shared by [SessionMenuTest] and
// [SessionMenuClipboardFailureTest].
// ---------------------------------------------------------------------------

/** The [Toaster] fake: records every `show`. */
private class RecordingToaster : Toaster {
    data class Shown(val text: String, val action: String?)
    val shown = mutableListOf<Shown>()
    val texts: List<String> get() = shown.map { it.text }
    override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
        shown += Shown(text, action)
    }
}

/** A frame with a fixed text; counts `plainText`/`close` and records the thread. */
private class MenuFrame(private val text: String, private val rev: ULong = 1uL) : LayoutFrame(NoHandle) {
    var plainTextCalls = 0
        private set
    var plainTextThread: Thread? = null
        private set
    var closeCalls = 0
        private set

    override fun revision() = rev
    override fun styleCount() = 0u
    override fun styles(): List<StyleDesc> = emptyList()
    override fun rowCount() = 0u
    override fun totalHeight() = 0f

    override fun plainText(): String {
        plainTextCalls++
        plainTextThread = Thread.currentThread()
        return text
    }

    override fun close() {
        closeCalls++
    }
}

/** Serves [next] from `frame()`; nothing else is called. */
private class MenuEngine : TranscriptView(NoHandle) {
    var next: LayoutFrame? = null
    var frameCalls = 0
        private set

    override fun setViewport(width: Float, textScale: Float) {}

    override fun frame(): LayoutFrame {
        frameCalls++
        return next ?: error("no frame staged")
    }

    override fun shutdown() {}
    override fun close() {}
}

/** A poster that never fires: no relay hop, so no frame is ever applied. */
private object MenuNoopPoster : FramePoster {
    override fun post(callback: Choreographer.FrameCallback) {}
    override fun remove(callback: Choreographer.FrameCallback) {}
}

private fun newTranscript(engine: MenuEngine): TranscriptState =
    TranscriptState(TextSystem(NoHandle), MenuNoopPoster) { _, _ -> engine }

/** One real `copyTranscript` lane, on its own thread (thread identity is observable). */
private fun copyWork() = Executors.newSingleThreadExecutor { r -> Thread(r, "session-menu-copy") }
    .asCoroutineDispatcher()

private fun appClipboard(): ClipboardManager =
    ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)!!

// ---------------------------------------------------------------------------
// The Binder-limit failure, as its own class: the shadow that throws is
// scoped per test class, so the success rows keep Robolectric's working
// clipboard. `ClipboardManager` cannot be faked by subclassing — the SDK
// jar exposes no public constructor — a custom shadow is the seam.
// ---------------------------------------------------------------------------

/**
 * The failure class's shadow: every `setPrimaryClip` throws Binder's
 * transaction limit. Not `private`: Robolectric instantiates shadows
 * reflectively and needs a public class (`internal` is public bytecode).
 */
@Implements(ClipboardManager::class)
internal class ThrowingClipboardShadow : ShadowClipboardManager() {
    @Implementation
    override fun setPrimaryClip(clip: ClipData) {
        throw RuntimeException("TransactionTooLargeException: data parcel size 1124320 bytes")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ThrowingClipboardShadow::class])
class SessionMenuClipboardFailureTest {

    @Test
    fun aFailedClipboardWriteToastsCouldNotCopyWithoutCrashing() = runBlocking {
        val engine = MenuEngine()
        engine.next = MenuFrame("huge transcript")
        val state = newTranscript(engine)
        val toaster = RecordingToaster()
        val work = copyWork()
        try {
            copyTranscript(state, appClipboard(), toaster, sdkInt = 33, work = work)
        } finally {
            work.close()
        }
        assertEquals(listOf("Could not copy the transcript."), toaster.texts)
        assertEquals("the fresh wrapper was still closed", 1, (engine.next as MenuFrame).closeCalls)
    }
}
