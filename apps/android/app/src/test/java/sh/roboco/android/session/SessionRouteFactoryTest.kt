package sh.roboco.android.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.QueueChromeFiller
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRouteFactory
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.ParkReason
import uniffi.roboco_core.QueueGate
import uniffi.roboco_core.TextSystem

/** Fills nothing; distinguishes filler types for the slot-order test. */
private class TagFiller(private val tag: String) : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(error = chrome.error?.let { "$it/$tag" } ?: tag)
}

/** A [uniffi.roboco_core.QueueItem] with only the queue filler's fields apart (82's test). */
private fun queueItem(
    id: String,
    text: String,
    visibleText: String = text,
    gate: QueueGate? = null,
) = uniffi.roboco_core.QueueItem(
    id = id,
    text = text,
    visibleText = visibleText,
    attachments = emptyList(),
    holdForTurnEnd = true,
    issuedBy = "device",
    fromThisDevice = true,
    issuedAtMs = 0L,
    editedAtMs = null,
    gate = gate,
    actionPending = false,
)

/**
 * [CoreSessionRouteFactory] (android-ui-parity #76) — the failable open:
 * no client or a refusing core → `null` plus the iOS `open session
 * failed` log, never a screen; a main-thread call refuses before any
 * FFI; `openedAtNanos` is stamped before the open; a success yields a
 * ready route (first refresh already synchronous, chrome title on the
 * first frame); offline engines still open; `discard()` makes no FFI
 * call and unregisters; two opens of one chat are two routes; fillers
 * run in slot order, fresh per route; [installSessionRoutes] replaces
 * the `Unavailable` default; android-app#28's cancel-on-open seam is
 * bound through `onSeen`; ticket 82's QUEUE-slot registration fills
 * `chrome.queue` (gate label, `visibleText`) and the route's
 * `queueActions` needs the live handle (a fake core gets none).
 *
 * Plain JUnit 4, no Robolectric: the opener seam is a fake over
 * [FakeSessionCore], the hub posts synchronously, the factory's `newIo`
 * lanes are `StandardTestDispatcher`s (one per controller, sharing the
 * test scheduler, so "nothing ran until the scheduler advanced" is
 * observable), `logW` is a list, `now` is fixed at 42, and `text` builds
 * the UniFFI test-only `TextSystem(NoHandle)` — the route only stores
 * it, and these tests never read `state.transcript` (61's production
 * constructor is main-thread only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRouteFactoryTest {

    private val logged = mutableListOf<Pair<String, Throwable?>>()

    /** A hub whose posted runnables run synchronously on the calling thread. */
    private fun syncHub() = SessionEventHub(post = { it.run() })

    /** The factory under test; never "main", fixed clock, no real FFI or engine. */
    private fun TestScope.factory(
        opener: SessionOpener,
        hub: SessionEventHub = syncHub(),
        fillers: ChromeFillerRegistry = ChromeFillerRegistry(),
        isMainThread: () -> Boolean = { false },
        now: () -> Long = { 42L },
        onSeen: ((String) -> Unit)? = null,
    ): CoreSessionRouteFactory = CoreSessionRouteFactory(
        opener = opener,
        hub = hub,
        fillers = fillers,
        isMainThread = isMainThread,
        logW = { message, error -> logged += message to error },
        newIo = { StandardTestDispatcher(testScheduler) },
        now = now,
        text = { TextSystem(NoHandle) },
        onSeen = onSeen,
    )

    // ── the failable open ───────────────────────────────────────────────

    @Test
    fun noClientReturnsNull() = runTest {
        // A core exists but the opener reports no client (before the
        // startup load, or after a core failure): null, nothing logged,
        // and no controller registered — a global event refreshes nobody.
        val hub = syncHub()
        val core = FakeSessionCore()
        val route = factory(SessionOpener { null }, hub = hub).open("chat-a")
        assertNull(route)
        assertTrue(logged.isEmpty())
        hub.onClientEvent(ClientEvent.WorkspaceChanged(1uL))
        advanceUntilIdle()
        assertEquals("no controller was created: no FFI pull", 0, core.composerCalls)
    }

    @Test
    fun coreRefusesReturnsNullAndLogs() = runTest {
        val boom = IllegalStateException("not found: x")
        val route = factory(SessionOpener { throw boom }).open("chat-a")
        assertNull(route)
        assertEquals(1, logged.size)
        // iOS NSLog text, with the exception's toString — exactly.
        assertEquals("open session failed: java.lang.IllegalStateException: not found: x", logged[0].first)
        assertSame(boom, logged[0].second)
    }

    @Test
    fun onMainThrowsBeforeOpening() = runTest {
        val calls = mutableListOf<String>()
        val core = FakeSessionCore()
        val factory = factory(
            SessionOpener { _ -> calls += "open"; core },
            isMainThread = { true },
            now = { calls += "now"; 42L },
        )
        val error = assertThrows(IllegalStateException::class.java) { factory.open("chat-a") }
        assertEquals("SessionRouteFactory.open on main", error.message)
        assertEquals("neither the clock nor the opener ran", emptyList<String>(), calls)
    }

    // ── the successful open ─────────────────────────────────────────────

    @Test
    fun successRouteIsReadyBeforePush() = runTest {
        val core = FakeSessionCore() // title "T", chatId "chat-a"
        val route = requireNotNull(factory(SessionOpener { core }).open("chat-a"))
        assertEquals("chat-a", route.chatId)
        assertTrue(route.hidesBottomChrome)
        // The first refresh ran synchronously in create: the title is
        // there before the io lane advanced (no empty-title flash).
        assertEquals("T", route.controller.chrome.value.title)
        assertEquals("T", route.paneTitle)
        assertEquals(route.chatId, route.state.chatId)
        assertEquals(42L, route.state.openedAtNanos)
    }

    @Test
    fun openedAtIsStampedBeforeOpen() = runTest {
        val calls = mutableListOf<String>()
        val core = FakeSessionCore()
        val route = requireNotNull(
            factory(
                SessionOpener { _ -> calls += "open"; core },
                now = { calls += "now"; 42L },
            ).open("chat-a"),
        )
        assertEquals(42L, route.state.openedAtNanos)
        assertEquals(listOf("now", "open"), calls)
    }

    @Test
    fun emptyTitleGivesNoPaneTitle() = runTest {
        val core = FakeSessionCore()
        core.composerResult = composerState(title = "")
        val route = requireNotNull(factory(SessionOpener { core }).open("chat-a"))
        assertNull(route.paneTitle)
    }

    @Test
    fun offlineEngineStillOpens() = runTest {
        // iOS has one cloud workspace; Roboco has N engines. An offline,
        // reconnecting or parked engine's chat opens from cache (read-only
        // via 79) — only NotFound, Closed or no client fail. And the
        // factory pre-checks nothing: the open makes exactly the first
        // refresh's three pulls, no engine() probe of its own.
        val states = listOf(
            EngineState.Offline,
            EngineState.Reconnecting,
            EngineState.Parked(ParkReason.REVOKED),
        )
        for (state in states) {
            val core = FakeSessionCore()
            core.engineResult = engineView().copy(state = state)
            val route = requireNotNull(factory(SessionOpener { core }).open("chat-a"))
            assertEquals(listOf("composer", "sessionRow", "engine", "sessionConfig"), core.callLog)
            route.discard()
        }
    }

    // ── discard and the hub registration ───────────────────────────────

    @Test
    fun discardDisposesWithoutFfi() = runTest {
        val hub = syncHub()
        val core = FakeSessionCore()
        val route = requireNotNull(factory(SessionOpener { core }, hub = hub).open("chat-a"))
        val before = core.callLog.toList()
        route.discard() // transcript never accessed
        assertEquals("discard adds no FFI call", before, core.callLog)
        // The controller is unregistered: a ComposerChanged event for its
        // chat triggers no refresh (no extra composer pull).
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        advanceUntilIdle()
        assertEquals(1, core.composerCalls)
    }

    @Test
    fun sameChatTwiceDistinctRoutes() = runTest {
        val hub = syncHub()
        val core = FakeSessionCore()
        val factory = factory(SessionOpener { core }, hub = hub)
        val r1 = requireNotNull(factory.open("chat-a"))
        val r2 = requireNotNull(factory.open("chat-a"))
        assertTrue("identity equality on purpose", r1 !== r2)
        assertEquals("each open refreshed once", 2, core.composerCalls)
        // Two hub registrations: one event refreshes both controllers once.
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        advanceUntilIdle()
        assertEquals(4, core.composerCalls)
        r1.discard()
        r2.discard()
    }

    // ── fillers ─────────────────────────────────────────────────────────

    @Test
    fun fillersRunInSlotOrderAndAreFreshPerRoute() = runTest {
        val registry = ChromeFillerRegistry()
        val instances = mutableListOf<TagFiller>()
        fun at(slot: Int, tag: String) =
            registry.register(slot) { _ -> TagFiller(tag).also { instances += it } }
        // Deliberately registered out of slot order.
        at(ChromeFillerRegistry.QUEUE, "queue")
        at(ChromeFillerRegistry.CAN_WRITE, "canWrite")
        at(ChromeFillerRegistry.CHIPS, "chips")
        val core = FakeSessionCore()
        val factory = factory(SessionOpener { core }, fillers = registry)
        val first = requireNotNull(factory.open("chat-a"))
        assertEquals("canWrite/chips/queue", first.controller.chrome.value.error)
        val second = requireNotNull(factory.open("chat-a"))
        // Each registration's factory ran once per open: two distinct
        // filler instances, never shared between two routes.
        assertEquals(6, instances.size)
        assertFalse(instances[0] === instances[3])
        assertFalse(instances[1] === instances[4])
        assertFalse(instances[2] === instances[5])
        first.discard()
        second.discard()
    }

    // ── ticket 82: the queue filler and the actions seam ────────────────

    @Test
    fun queueFillerFillsTheChromeAndFakeCoresGetNoQueueActions() = runTest {
        // The QUEUE-slot registration maps `composer.queue` into
        // `SessionChrome.queue` (gate label, `visibleText`, order) for
        // every opened route. The route's `queueActions` needs the live
        // handle: a FakeSessionCore route carries none — only a
        // CoreSessionCore open gets actions.
        val registry = ChromeFillerRegistry()
        registry.register(ChromeFillerRegistry.QUEUE) { _ -> QueueChromeFiller() }
        val core = FakeSessionCore()
        core.composerResult = composerState().copy(
            queue = listOf(
                queueItem("q1", text = "raw one", visibleText = "one"),
                queueItem(
                    "q2",
                    text = "raw two",
                    gate = QueueGate.Editing(ownerDeviceId = "dev", expiresAtMs = 0L, mine = true),
                ),
            ),
        )
        val route = requireNotNull(factory(SessionOpener { core }, fillers = registry).open("chat-a"))
        assertEquals(
            listOf(
                SessionChrome.QueuedItem(id = "q1", text = "one", gate = null),
                SessionChrome.QueuedItem(id = "q2", text = "raw two", gate = "Editing"),
            ),
            route.controller.chrome.value.queue,
        )
        assertNull("a fake core has no handle: no queue actions", route.queueActions)
        route.discard()
    }

    // ── registration and the cancel-on-open seam ──────────────────────────────

    @Test
    fun installSessionRoutesReplacesUnavailable() {
        val hooks = RouterHooks()
        assertSame(SessionRouteFactory.Unavailable, hooks.sessionRoutes)
        installSessionRoutes(hooks, SessionOpener { null }, syncHub(), ChromeFillerRegistry())
        assertTrue(hooks.sessionRoutes is CoreSessionRouteFactory)
    }

    @Test
    fun markSeenInvokesTheCancelOnOpenSeam() = runTest {
        val seen = mutableListOf<String>()
        val core = FakeSessionCore()
        val route = requireNotNull(
            factory(SessionOpener { core }, onSeen = { seen += it }).open("chat-a"),
        )
        // The session screen's first composition (75) calls markSeen():
        // the notification goes on the same beat (android-app#28).
        route.controller.markSeen()
        assertEquals(listOf("chat-a"), seen)
        route.release()
        // The pop also counts as "seen" (51's close): a second cancel.
        assertEquals(listOf("chat-a", "chat-a"), seen)
        // The FFI pair on the io lane: markSeen()'s markSeen, then the
        // close pair (setViewAttached(false) + markSeen) — once the
        // scheduler advanced. A second release is a no-op (the close
        // guard) — nothing runs twice.
        advanceUntilIdle()
        assertEquals(2, core.callLog.count { it == "markSeen" })
        assertEquals(1, core.callLog.count { it == "setViewAttached(false)" })
        route.release()
        assertEquals(2, core.callLog.count { it == "markSeen" })
    }
}
