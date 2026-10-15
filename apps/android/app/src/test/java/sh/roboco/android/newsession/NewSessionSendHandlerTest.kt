package sh.roboco.android.newsession

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.composer.SendResult
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.shell.DraftHandoff
import uniffi.roboco_core.NewSession
import uniffi.roboco_core.SendRequest

/**
 * [NewSessionSendHandler] (android-ui-parity #94) — the Send sequence
 * table over fakes: the core (the create/open/send FFI seam), the page
 * (92's persist/mark), the alerts, the notification ask and the capture.
 * Every path runs through the real [NewSessionCreator] with an injected
 * IO dispatcher, so the tests also pin "the core calls run on IO, never
 * on main" (the #609 trap). The fakes share one `events` timeline, so
 * the success test asserts the exact call order.
 *
 * `runTest` with a [StandardTestDispatcher] as main (the handler's
 * caller) and a second one as IO, both on the test scheduler. Robolectric
 * because the fakes construct `uniffi.roboco_core` records
 * (android-app#13's rule).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewSessionSendHandlerTest {

    /** The FFI fake: records the call order, the lane each ran on; can throw or hold the create. */
    private class FakeCore(private val events: MutableList<String>) : NewSessionCore {
        val calls = mutableListOf<String>()
        val lanes = mutableListOf<CoroutineDispatcher?>()
        var createError: Throwable? = null
        var sendError: Throwable? = null
        var holdCreate: CompletableDeferred<Unit>? = null

        override suspend fun createSession(newSession: NewSession): String {
            calls += "createSession"
            events += "createSession"
            lanes += currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            holdCreate?.await()
            createError?.let { throw it }
            return "chat-1"
        }

        override suspend fun sendFirst(chatId: String, request: SendRequest) {
            calls += "sendFirst"
            events += "sendFirst"
            lanes += currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            sendError?.let { throw it }
        }
    }

    /** 92's page fake: records both calls onto the shared timeline. */
    private class RecordingPage(
        private val initial: NewSessionDraft,
        private val events: MutableList<String>,
    ) : NewSessionPage {
        override val draft: NewSessionDraft get() = initial
        override val projects: List<ProjectOption> get() = emptyList()
        override fun persistBeforeCreate() {
            events += "persistBeforeCreate"
        }

        override fun markCreated() {
            events += "markCreated"
        }
    }

    /** 18's alerts fake: records every shown alert. */
    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }

        override fun updateCurrent(alert: RobocoAlert) {}

        override fun dismissAll() {}
    }

    /** 32's marker fake — the handoff the capture seam hands back. */
    private object FakeHandoff : DraftHandoff

    // ── the shared fixture ───────────────────────────────────────────────

    private val events = mutableListOf<String>()
    private val core = FakeCore(events)
    private val alerts = RecordingAlerts()
    private var askCount = 0
    private var askError: Throwable? = null
    private val captureTexts = mutableListOf<String>()
    private var captureResult: DraftHandoff? = FakeHandoff
    private val created = mutableListOf<Pair<String, DraftHandoff?>>()
    private val logs = mutableListOf<Pair<String, Throwable?>>()

    private var page: NewSessionPage = RecordingPage(NewSessionDraft(projectId = "p1"), events)

    /** A handler over the shared fakes (and a creator with the injected IO lane). */
    // A TestScope extension: a default argument resolves where the function is
    // declared, so a plain member could not see runTest's testScheduler.
    private fun TestScope.handler(io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)): NewSessionSendHandler =
        NewSessionSendHandler(
            page = page,
            creator = NewSessionCreator(
                core = core,
                io = io,
                logW = { message, error -> logs += message to error },
            ),
            alerts = alerts,
            askAfterFirstSession = {
                askCount++
                events += "ask"
                askError?.let { throw it }
            },
            capture = { text ->
                captureTexts += text
                events += "capture"
                captureResult
            },
            onCreated = { chatId, handoff ->
                events += "onCreated"
                created += chatId to handoff
            },
        )

    // ── the Send sequence ────────────────────────────────────────────────

    @Test
    fun successRunsTheSequenceInOrderOnIoAndReturnsSent() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val io = StandardTestDispatcher(testScheduler)
        val h = handler(io)
        var outcome: SendResult? = null
        launch(main) { outcome = h.send("body", emptyList()) }
        advanceUntilIdle()

        assertEquals(SendResult.Sent, outcome)
        assertEquals(
            "persist → createSession → sendFirst → markCreated → ask → capture → onCreated",
            listOf(
                "persistBeforeCreate",
                "createSession",
                "sendFirst",
                "markCreated",
                "ask",
                "capture",
                "onCreated",
            ),
            events,
        )
        // The core calls ran on the injected IO lane, never on main.
        assertTrue(core.lanes.all { it === io })
        assertFalse(core.lanes.any { it === main })
        assertEquals(listOf("chat-1"), created.map { it.first })
        assertSame(FakeHandoff, created.single().second)
        assertEquals(listOf("body"), captureTexts)
        assertEquals(1, askCount)
        assertTrue(alerts.shown.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun noTargetMakesNoCoreCallShowsTheAlertAndKeeps() = runTest {
        // A page with no project and no host: target == null.
        page = RecordingPage(NewSessionDraft(projectId = null, hostId = null), events)
        val result = handler().send("body", emptyList())

        assertEquals(SendResult.Kept, result)
        assertTrue(core.calls.isEmpty()) // no target: no core call at all
        assertEquals(listOf("persistBeforeCreate"), events) // sticky either way; nothing else ran
        assertEquals(0, askCount)
        assertTrue(created.isEmpty())

        val alert = alerts.shown.single()
        assertEquals("Couldn't start the session", alert.title)
        assertEquals("Choose a project or a host that can run it.", alert.message)
        assertNull(alert.field)
        assertEquals(1, alert.buttons.size)
        assertEquals("OK", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Default, alert.buttons[0].role)
        assertTrue(logs.isEmpty()) // a missing target is not a failure to log
    }

    @Test
    fun aThrowingAskStillCapturesCallsOnCreatedAndReturnsSent() = runTest {
        askError = IllegalStateException("prefs gone")
        val result = handler().send("body", emptyList())

        assertEquals(SendResult.Sent, result)
        assertEquals(1, askCount)
        assertEquals(
            listOf("persistBeforeCreate", "createSession", "sendFirst", "markCreated", "ask", "capture", "onCreated"),
            events,
        )
        assertEquals(listOf("chat-1" to FakeHandoff), created)
        assertTrue(alerts.shown.isEmpty())
    }

    @Test
    fun aThrowingCreateShowsTheAlertAndKeepsWithoutSending() = runTest {
        core.createError = IllegalStateException("HostUnavailable")
        val result = handler().send("body", emptyList())

        assertEquals(SendResult.Kept, result)
        assertEquals(listOf("createSession"), core.calls) // no sendFirst
        assertEquals(listOf("persistBeforeCreate", "createSession"), events) // markCreated never ran
        assertEquals(1, alerts.shown.size)
        assertEquals("Couldn't start the session", alerts.shown[0].title)
        assertEquals("Choose a project or a host that can run it.", alerts.shown[0].message)
        assertEquals(listOf("create session failed" to core.createError), logs)
        assertEquals(0, askCount)
        assertTrue(created.isEmpty())
    }

    @Test
    fun aThrowingSendShowsTheAlertAndKeeps() = runTest {
        core.sendError = IllegalStateException("RPC error")
        val result = handler().send("body", emptyList())

        assertEquals(SendResult.Kept, result)
        assertEquals(listOf("createSession", "sendFirst"), core.calls)
        assertEquals(1, alerts.shown.size)
        assertEquals("Couldn't start the session", alerts.shown[0].title)
        assertEquals(listOf("create session failed" to core.sendError), logs)
        assertEquals(0, askCount)
        assertTrue(created.isEmpty())
        assertTrue(events.none { it == "markCreated" })
    }

    @Test
    fun aNullCaptureStillCallsOnCreatedWithANullHandoff() = runTest {
        captureResult = null
        val result = handler().send("body", emptyList())

        assertEquals(SendResult.Sent, result)
        assertEquals(listOf("chat-1" to null), created)
        assertTrue(events.contains("capture"))
    }

    @Test
    fun cancellingTheCallerMidCreateStillCreatesAndOpens() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val io = StandardTestDispatcher(testScheduler)
        core.holdCreate = CompletableDeferred()
        val h = handler(io)
        var outcome: SendResult? = null
        val job = launch(main) { outcome = h.send("body", emptyList()) }
        advanceUntilIdle() // the create is now suspended on the gate

        // The page (and its sheet) left while the core call was in
        // flight: the send still completes — the chat is created, the
        // page is marked, the chat is opened (without a handoff, the
        // capture sees a detached page in production).
        job.cancel()
        core.holdCreate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("createSession", "sendFirst"), core.calls)
        assertTrue(events.contains("markCreated"))
        assertTrue(events.contains("onCreated"))
        assertEquals(listOf("chat-1"), created.map { it.first })
    }
}
