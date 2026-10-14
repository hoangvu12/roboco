package sh.roboco.android.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.EngineState

/** Fills nothing; distinguishes filler types for [SessionController.filler]. */
private class NoopFiller : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput) = chrome
}

/** A filler type no test ever registers (the null branch of [SessionController.filler]). */
private class UnregisteredFiller : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput) = chrome
}

/** Records every bind/fill and appends `/<tag>` to the subtitle. */
private class RecordingFiller(private val tag: String) : ChromeFiller {
    val fills = mutableListOf<SessionChrome>()
    val inputs = mutableListOf<ChromeInput>()
    var binds = 0
    var invalidate: (() -> Unit)? = null

    override fun bind(invalidate: () -> Unit) {
        binds++
        this.invalidate = invalidate
    }

    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome {
        fills += chrome
        inputs += input
        return chrome.copy(subtitle = chrome.subtitle + "/" + tag)
    }
}

/**
 * [SessionController] (android-ui-parity #51): the synchronous first
 * refresh, the main-thread refusal, the event routing and coalescing,
 * the StateFlow equality behaviour, the lifecycle call log (and its
 * ordering on the io lane), close/dispose semantics, the FFI failure
 * path and the filler contract.
 *
 * Plain JUnit 4, no Robolectric: the FFI seam is [FakeSessionCore],
 * the hub posts synchronously, the io lane is a
 * `StandardTestDispatcher` (so "nothing ran until the dispatcher
 * advanced" is observable), and `android.util.Log` is swapped out via
 * [sessionLogW].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {

    private var savedLog: ((String, Throwable?) -> Unit)? = null
    private val loggedFailures = mutableListOf<Pair<String, Throwable?>>()

    @Before
    fun swapLog() {
        savedLog = sessionLogW
        sessionLogW = { message, error -> loggedFailures += message to error }
    }

    @After
    fun restoreLog() {
        sessionLogW = savedLog!!
    }

    private fun syncHub() = SessionEventHub(post = { it.run() })

    /** The controller under test: sync-posted hub + `StandardTestDispatcher` io lane, never "main". */
    private fun TestScope.newController(
        core: FakeSessionCore,
        fillers: List<ChromeFiller> = emptyList(),
        hub: SessionEventHub = syncHub(),
    ): SessionController = SessionController.create(
        core = core,
        hub = hub,
        fillers = fillers,
        io = StandardTestDispatcher(testScheduler),
        isMainThread = { false },
    )

    // ── create ──────────────────────────────────────────────────────────

    @Test
    fun createPublishesDerivedChromeBeforeTheIoLaneRuns() = runTest {
        val core = FakeSessionCore()
        core.composerResult = composerState(title = "T", hostName = "Vu-PC")
        core.sessionRowResult = sessionRow(projectName = "roboco", harnessLabel = "Claude Code")
        val controller = newController(core)

        // Nothing on the io lane has run, yet the first frame's chrome is
        // already published (iOS: the initial refresh inside init).
        assertEquals("T", controller.chrome.value.title)
        assertEquals("roboco @ Vu-PC", controller.chrome.value.subtitle)
        assertEquals("Message Claude Code", controller.chrome.value.placeholder)
        assertEquals("dev", controller.hostDeviceId)
        assertEquals(1, core.composerCalls)
        assertEquals(false, controller.pinned)

        advanceUntilIdle()
        // The drain loop started and found nothing pending: no extra pull.
        assertEquals(1, core.composerCalls)
    }

    @Test
    fun createRefusesTheMainThreadAndMakesNoFfiCall() = runTest {
        val core = FakeSessionCore()
        val failure = try {
            SessionController.create(
                core = core,
                hub = syncHub(),
                io = StandardTestDispatcher(testScheduler),
                isMainThread = { true },
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("create must throw on main", failure)
        assertEquals("SessionController.create on main", failure!!.message)
        assertTrue("no FFI call may happen", core.callLog.isEmpty())
        assertEquals(0, core.composerCalls)
    }

    // ── routing ─────────────────────────────────────────────────────────

    @Test
    fun sessionAndComposerChangedRefreshOnlyTheMatchingChat() = runTest {
        val hub = syncHub()
        val coreA = FakeSessionCore("chat-a")
        val coreB = FakeSessionCore("chat-b")
        newController(coreA, hub = hub)
        newController(coreB, hub = hub)

        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        advanceUntilIdle()
        assertEquals(2, coreA.composerCalls)
        assertEquals(1, coreB.composerCalls)

        hub.onClientEvent(ClientEvent.SessionChanged("chat-a", 3uL))
        advanceUntilIdle()
        assertEquals(3, coreA.composerCalls)
        assertEquals(1, coreB.composerCalls)
    }

    @Test
    fun workspaceConnectivityAndEnginesChangedRefreshEveryController() = runTest {
        val hub = syncHub()
        val coreA = FakeSessionCore("chat-a")
        val coreB = FakeSessionCore("chat-b")
        newController(coreA, hub = hub)
        newController(coreB, hub = hub)
        assertEquals(1, coreA.composerCalls)
        assertEquals(1, coreB.composerCalls)

        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        advanceUntilIdle()
        assertEquals(2, coreA.composerCalls)
        assertEquals(2, coreB.composerCalls)

        hub.onClientEvent(ClientEvent.ConnectivityChanged(connectivity()))
        advanceUntilIdle()
        assertEquals(3, coreA.composerCalls)
        assertEquals(3, coreB.composerCalls)

        hub.onClientEvent(ClientEvent.EnginesChanged)
        advanceUntilIdle()
        assertEquals(4, coreA.composerCalls)
        assertEquals(4, coreB.composerCalls)
    }

    @Test
    fun noticeAndEngineIdentityPinnedRefreshNothing() = runTest {
        val hub = syncHub()
        val coreA = FakeSessionCore("chat-a")
        val coreB = FakeSessionCore("chat-b")
        newController(coreA, hub = hub)
        newController(coreB, hub = hub)

        hub.onClientEvent(ClientEvent.Notice("reverted an overlay write"))
        hub.onClientEvent(ClientEvent.EngineIdentityPinned(engineId = "e", deviceId = "d", name = "n"))
        advanceUntilIdle()

        assertEquals(1, coreA.composerCalls)
        assertEquals(1, coreB.composerCalls)
    }

    @Test
    fun aBurstOfComposerChangedEventsCoalescesToOneRefresh() = runTest {
        val hub = syncHub()
        val core = FakeSessionCore()
        newController(core, hub = hub)
        assertEquals(1, core.composerCalls)

        val event = ClientEvent.ComposerChanged("chat-a", 2uL)
        repeat(10) { hub.onClientEvent(event) }
        advanceUntilIdle()

        // One synchronous pull at create plus exactly one coalesced refresh.
        assertEquals(2, core.composerCalls)
    }

    // ── publication ─────────────────────────────────────────────────────

    @Test
    fun anEqualRefreshEmitsNothingAndAChangedHostNameEmitsTheNewSubtitle() = runTest {
        val core = FakeSessionCore()
        core.composerResult = composerState(hostName = "Vu-PC")
        core.sessionRowResult = sessionRow(projectName = "roboco", harnessLabel = "Claude Code")
        val hub = syncHub()
        val controller = newController(core, hub = hub)
        assertEquals("roboco @ Vu-PC", controller.chrome.value.subtitle)

        val seen = mutableListOf<SessionChrome>()
        val collector = launch { controller.chrome.collect { seen += it } }
        advanceUntilIdle() // the collector starts with the current value
        assertEquals(1, seen.size)

        // An identical composer state: the equal value is skipped.
        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        advanceUntilIdle()
        assertEquals(1, seen.size)

        // A changed host name: the new subtitle is emitted.
        core.composerResult = composerState(hostName = "Vu-Tablet")
        hub.onClientEvent(ClientEvent.WorkspaceChanged(3uL))
        advanceUntilIdle()
        assertEquals(2, seen.size)
        assertEquals("roboco @ Vu-Tablet", seen[1].subtitle)

        collector.cancel()
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    @Test
    fun lifecycleCallsRunOnTheIoLaneInOrder() = runTest {
        val core = FakeSessionCore()
        val controller = newController(core)
        val view = fakeTranscriptView()

        controller.attach(view)
        controller.markSeen()
        controller.reattach()
        controller.attach(view) // same instance: a no-op
        controller.detach()

        // Only the create-time refresh ran: the lifecycle calls are queued
        // on the io lane, not executed on the caller's thread.
        assertEquals(listOf("composer", "sessionRow", "engine", "sessionConfig"), core.callLog.toList())

        advanceUntilIdle()
        assertEquals(
            listOf(
                "composer", "sessionRow", "engine", "sessionConfig",
                "attachTranscript", "setViewAttached(true)",
                "markSeen",
                "setViewAttached(true)",
                "setViewAttached(false)",
            ),
            core.callLog.toList(),
        )
    }

    @Test
    fun attachingADifferentViewAttachesAgain() = runTest {
        val core = FakeSessionCore()
        val controller = newController(core)

        controller.attach(fakeTranscriptView())
        controller.attach(fakeTranscriptView())
        advanceUntilIdle()

        assertEquals(2, core.callLog.count { it == "attachTranscript" })
        assertEquals(2, core.callLog.count { it == "setViewAttached(true)" })
    }

    @Test
    fun anAttachThatReturnsFalseIsLoggedAndStillAttachesTheView() = runTest {
        val core = FakeSessionCore()
        core.attachTranscriptResult = false
        val controller = newController(core)

        controller.attach(fakeTranscriptView())
        advanceUntilIdle()

        // iOS ignores the Bool (`CoreSessionSource.swift:26-29`); Roboco
        // logs it and still marks the view attached.
        assertEquals(
            listOf("attachTranscript", "setViewAttached(true)"),
            core.callLog.drop(4),
        )
        assertEquals(1, loggedFailures.size)
        assertTrue(loggedFailures.single().first.contains("returned false"))
    }

    @Test
    fun aFailedAttachForgetsTheViewSoTheSameViewCanRetry() = runTest {
        val core = FakeSessionCore()
        core.attachTranscriptFailures = 1
        val controller = newController(core)
        val view = fakeTranscriptView()

        controller.attach(view)
        advanceUntilIdle()
        assertEquals(listOf("attachTranscript"), core.callLog.drop(4))
        assertEquals("attach failed", loggedFailures.single().first)

        // The bind failed: the same view instance is not masked out —
        // the second attach retries the FFI instead of no-op'ing.
        controller.attach(view)
        advanceUntilIdle()
        assertEquals(
            listOf("attachTranscript", "attachTranscript", "setViewAttached(true)"),
            core.callLog.drop(4),
        )
    }

    @Test
    fun closeDetachesMarksSeenAndStopsEverything() = runTest {
        val core = FakeSessionCore()
        val hub = syncHub()
        val controller = newController(core, hub = hub)
        advanceUntilIdle()

        controller.close()
        advanceUntilIdle()
        assertEquals(listOf("setViewAttached(false)", "markSeen"), core.callLog.drop(4))

        // Events after close: no refresh (the controller unregistered).
        val callsAfterClose = core.composerCalls
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        advanceUntilIdle()
        assertEquals(callsAfterClose, core.composerCalls)

        // Every lifecycle call after close adds nothing; a second close is a no-op.
        val logAfterClose = core.callLog.toList()
        controller.attach(fakeTranscriptView())
        controller.markSeen()
        controller.reattach()
        controller.detach()
        controller.close()
        advanceUntilIdle()
        assertEquals(logAfterClose, core.callLog.toList())
    }

    // ── offline read-only (79) ───────────────────────────────────────────

    /**
     * The engine is not `Connected`: `markSeen()` makes no `markSeen`
     * FFI call and `close()` makes only `setViewAttached(false)` — the
     * skip is silent (no failure logged) and the notification cancel
     * still happens (it is local, never engine state).
     */
    @Test
    fun markSeenAndCloseMakeNoMarkSeenCallWhileNotWritable() = runTest {
        val core = FakeSessionCore()
        core.engineResult = engineView(state = EngineState.Reconnecting)
        val controller = newController(core, fillers = listOf(CanWriteFiller))

        // The first frame already carries the derived flag: the initial
        // refresh ran synchronously at create (a Reconnecting engine
        // gives canWrite == false on the first frame).
        assertEquals(false, controller.chrome.value.canWrite)

        controller.markSeen()
        advanceUntilIdle()
        // No markSeen FFI call: only the create-time refresh pulls ran.
        assertTrue(core.callLog.none { it == "markSeen" })

        controller.close()
        advanceUntilIdle()
        // Only the detach ran on the way out; setViewAttached is never
        // gated (a local subscription switch, 85's rule).
        assertEquals(listOf("setViewAttached(false)"), core.callLog.drop(4))

        // Silent: no toast, no banner and no Log.w — the skip is not an
        // error, so the failures list stays empty.
        assertTrue(loggedFailures.isEmpty())
    }

    /** With the engine `Connected`, both behave exactly as in ticket 51. */
    @Test
    fun markSeenAndCloseMarkAsBeforeWhileConnected() = runTest {
        val core = FakeSessionCore()
        core.engineResult = engineView(state = EngineState.Connected)
        val controller = newController(core, fillers = listOf(CanWriteFiller))
        assertEquals(true, controller.chrome.value.canWrite)

        controller.markSeen()
        advanceUntilIdle()
        assertEquals(listOf("markSeen"), core.callLog.drop(4))

        controller.close()
        advanceUntilIdle()
        // Exactly ticket 51's order on the way out.
        assertEquals(
            listOf("markSeen", "setViewAttached(false)", "markSeen"),
            core.callLog.drop(4),
        )
    }

    /**
     * `EnginesChanged` after a state flip updates `chrome.canWrite`
     * within one refresh, both ways — no grace, no debounce — and the
     * gate sees the latest refresh at execution time.
     */
    @Test
    fun enginesChangedAfterAStateFlipUpdatesCanWriteBothWays() = runTest {
        val hub = syncHub()
        val core = FakeSessionCore()
        core.engineResult = engineView(state = EngineState.Reconnecting)
        val controller = newController(core, hub = hub, fillers = listOf(CanWriteFiller))
        assertEquals(false, controller.chrome.value.canWrite)

        // The engine comes back: one refresh (one event) is enough.
        core.engineResult = engineView(state = EngineState.Connected)
        hub.onClientEvent(ClientEvent.EnginesChanged)
        advanceUntilIdle()
        assertEquals(true, controller.chrome.value.canWrite)

        // A following markSeen goes through again.
        controller.markSeen()
        advanceUntilIdle()
        assertTrue(core.callLog.contains("markSeen"))

        // And back the other way: offline again skips it.
        core.engineResult = engineView(state = EngineState.Offline)
        hub.onClientEvent(ClientEvent.EnginesChanged)
        advanceUntilIdle()
        assertEquals(false, controller.chrome.value.canWrite)
        val marksSoFar = core.callLog.count { it == "markSeen" }
        controller.markSeen()
        advanceUntilIdle()
        assertEquals(marksSoFar, core.callLog.count { it == "markSeen" })
    }

    @Test
    fun disposeMakesNoFfiCallAndUnregisters() = runTest {
        val core = FakeSessionCore()
        val hub = syncHub()
        val controller = newController(core, hub = hub)

        controller.dispose()
        advanceUntilIdle()

        // Only the create-time synchronous refresh ran: dispose itself
        // never touches FFI.
        assertEquals(listOf("composer", "sessionRow", "engine", "sessionConfig"), core.callLog.toList())
        assertEquals(1, core.composerCalls)

        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        advanceUntilIdle()
        assertEquals(1, core.composerCalls)
    }

    // ── failures ────────────────────────────────────────────────────────

    @Test
    fun aFailingComposerKeepsTheChromeAndRecovers() = runTest {
        val core = FakeSessionCore()
        core.composerResult = composerState(title = "T")
        val hub = syncHub()
        val controller = newController(core, hub = hub)
        assertEquals("T", controller.chrome.value.title)

        core.composerFailures = 1
        hub.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
        advanceUntilIdle()

        // The failure was logged, the chrome kept its previous value,
        // and nothing crashed.
        assertEquals(2, core.composerCalls)
        assertEquals(1, loggedFailures.size)
        assertEquals("refresh failed", loggedFailures.single().first)
        assertEquals("T", controller.chrome.value.title)

        // The next event refreshes normally.
        core.composerResult = composerState(title = "T2")
        hub.onClientEvent(ClientEvent.WorkspaceChanged(3uL))
        advanceUntilIdle()
        assertEquals(3, core.composerCalls)
        assertEquals("T2", controller.chrome.value.title)
    }

    // ── fillers ─────────────────────────────────────────────────────────

    @Test
    fun fillersRunInListOrderAfterOneBindEach() = runTest {
        val core = FakeSessionCore()
        core.composerResult = composerState(hostName = "Vu-PC")
        core.sessionRowResult = sessionRow(projectName = "roboco", harnessLabel = "Claude Code")
        core.engineResult = engineView()
        val first = RecordingFiller("a")
        val second = RecordingFiller("b")
        val controller = newController(core, fillers = listOf(first, second))

        assertEquals("roboco @ Vu-PC/a/b", controller.chrome.value.subtitle)
        assertEquals(1, first.binds)
        assertEquals(1, second.binds)

        // The first filler saw the base fill; the second saw the first's output.
        assertEquals("roboco @ Vu-PC", first.fills.single().subtitle)
        assertEquals("roboco @ Vu-PC/a", second.fills.single().subtitle)

        // The input carries the pulled values — a filler makes no FFI call of its own.
        val input = first.inputs.single()
        assertEquals("chat-a", input.chatId)
        assertEquals("roboco", input.row?.project?.name)
        assertEquals("Vu-PC", input.composer.host.name)
        assertEquals("engine:v1:alpha", input.engine?.engineId)
    }

    @Test
    fun aFillerInvalidatingTriggersExactlyOneRefresh() = runTest {
        val core = FakeSessionCore()
        val hub = syncHub()
        val filler = RecordingFiller("a")
        newController(core, hub = hub, fillers = listOf(filler))
        assertEquals(1, core.composerCalls)

        filler.invalidate!!()
        advanceUntilIdle()

        assertEquals(2, core.composerCalls)
        assertEquals(2, filler.fills.size)
    }

    @Test
    fun fillerReturnsTheRegisteredInstance() = runTest {
        val filler = NoopFiller()
        val controller = newController(FakeSessionCore(), fillers = listOf(filler, RecordingFiller("x")))

        assertSame(filler, controller.filler<NoopFiller>())
        assertNull(controller.filler<UnregisteredFiller>())
    }
}
