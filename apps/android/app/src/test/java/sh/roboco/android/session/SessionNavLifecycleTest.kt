package sh.roboco.android.session

import android.view.Choreographer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.ModelPipeline
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * [SessionNavLifecycle] (android-ui-parity #85) — the UIKit
 * appear/disappear split: reversible work at the start of every
 * disappearance (cancel the queued edit, then save the draft),
 * irreversible teardown only after a committed removal, idempotence
 * everywhere, and every no-op after close.
 *
 * Plain JUnit 4, no Robolectric: the controller is a real ticket-51
 * controller over 51's [FakeSessionCore] with an
 * [UnconfinedTestDispatcher] IO lane (its call log fills synchronously),
 * the transcript a real ticket-61 [TranscriptState] over a fake engine
 * (the model pipeline injected with a plain-JVM worker and a
 * run-in-place main hop — the defaults touch android), and the three
 * fakes all write into one shared ordered log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionNavLifecycleTest {

    /** The shared ordered log: the adapters' and the router's entries. */
    private val log = mutableListOf<String>()

    /** The recording edit canceller. */
    private val edits = QueuedEditCanceller { log += "cancelIfEditing" }

    /** The recording draft saver. */
    private val drafts = DraftSaver { log += "save" }

    /** The recording router: only the two presence callbacks are wired. */
    private val router = RecordingRouter(log)

    // ------------------------------------------------------------ fixtures

    /** A poster that never fires: no relay hop, so no frame is applied. */
    private object NoopPoster : FramePoster {
        override fun post(callback: Choreographer.FrameCallback) {}
        override fun remove(callback: Choreographer.FrameCallback) {}
    }

    /** The fake engine: no FFI, no frames, no-op shutdown/close. */
    private class FakeEngine : TranscriptView(NoHandle) {
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("the lifecycle test never asks the engine for a frame")
        override fun shutdown() {}
        override fun close() {}
    }

    /** A one-thread, daemon executor: no android call in its factory. */
    private fun plainWorker(): ThreadPoolExecutor =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
            Thread(runnable, "session-nav-lifecycle-test").apply { isDaemon = true }
        }

    /**
     * A real [TranscriptState] on the fake engine, with a plain-JVM model
     * pipeline (the defaults reference `Handler(Looper…)` and
     * `Process.setThreadPriority`, which throw outside Robolectric).
     */
    private fun fakeTranscript(): TranscriptState {
        val worker = plainWorker()
        return TranscriptState(
            text = TextSystem(NoHandle),
            poster = NoopPoster,
            makeModels = { onUpgradeBuilt ->
                ModelPipeline(worker = worker, postMain = { it.run() }, onUpgradeBuilt = onUpgradeBuilt)
            },
            makeEngine = { _, _ -> FakeEngine() },
        )
    }

    /** A lifecycle under test, over a fresh controller and transcript. */
    private fun newLifecycle(core: FakeSessionCore = FakeSessionCore(chatId = "chat-a")): SessionNavLifecycle {
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = UnconfinedTestDispatcher(),
            isMainThread = { false },
        )
        return SessionNavLifecycle(
            chatId = controller.chatId,
            controller = controller,
            transcript = fakeTranscript(),
            edits = edits,
            drafts = drafts,
        )
    }

    // ── onWillAppear ────────────────────────────────────────────────────

    /** iOS: the first appearance does not re-attach (75 attached already). */
    @Test
    fun onWillAppearWithTheFirstAppearanceLogsNothing() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.onWillAppear(hasAppeared = false)
        assertTrue(log.isEmpty())
        assertFalse(core.callLog.any { it.startsWith("setViewAttached") })
    }

    /** Every later appearance re-attaches (tab return, a cancelled pop). */
    @Test
    fun onWillAppearAfterTheFirstAppearanceReattaches() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.onWillAppear(hasAppeared = true)
        assertTrue(log.isEmpty())
        assertEquals(listOf("setViewAttached(true)"), core.callLog.filter { it.startsWith("setViewAttached") })
    }

    // ── onDidAppear ─────────────────────────────────────────────────────

    @Test
    fun onDidAppearForwardsToTheRouter() {
        val lifecycle = newLifecycle()
        lifecycle.onDidAppear(router)
        assertEquals(listOf("sessionDidAppear(chat-a)"), log)
    }

    // ── leave ───────────────────────────────────────────────────────────

    /** The order is the iOS one: cancel the edit FIRST, then save. */
    @Test
    fun leaveCancelsTheEditThenSavesAndMakesNoFfiCall() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        val ffiBefore = core.callLog.toList()
        lifecycle.leave()
        assertEquals(listOf("cancelIfEditing", "save"), log)
        assertEquals("no FFI during a reversible leave", ffiBefore, core.callLog.toList())
    }

    /** A second leave (onWillDisappear then onDispose) is harmless. */
    @Test
    fun aSecondLeaveAddsTheSameTwoEntriesOnly() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.leave()
        lifecycle.leave()
        assertEquals(listOf("cancelIfEditing", "save", "cancelIfEditing", "save"), log)
        assertFalse(core.callLog.any { it == "setViewAttached(false)" })
        assertFalse(core.callLog.any { it == "markSeen" })
    }

    /** A configuration-change dispose saves the draft and keeps the lease. */
    @Test
    fun onConfigurationDisposeSavesOnly() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.onConfigurationDispose()
        assertEquals(listOf("save"), log)
    }

    // ── closeAfterPop ───────────────────────────────────────────────────

    /**
     * The committed pop: `sessionDidClose`, then detach, then markSeen,
     * and the transcript closed — the irreversible work, only here.
     */
    @Test
    fun closeAfterPopLogsTheTeardownInOrder() {
        val core = FakeSessionCore(chatId = "chat-a")
        val transcript = fakeTranscript()
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = UnconfinedTestDispatcher(),
            isMainThread = { false },
        )
        val lifecycle = SessionNavLifecycle(
            chatId = controller.chatId,
            controller = controller,
            transcript = transcript,
            edits = edits,
            drafts = drafts,
        )
        val ffiBefore = core.callLog.toList()

        lifecycle.closeAfterPop(router)

        assertEquals(listOf("sessionDidClose(chat-a)"), log)
        // The controller's close, in order, after the router call (the
        // unconfined lane runs them synchronously).
        assertEquals(
            listOf("setViewAttached(false)", "markSeen"),
            core.callLog.drop(ffiBefore.size),
        )
        assertTrue("the transcript is closed", transcript.isClosed)
    }

    /** Idempotence: a second closeAfterPop and a release add nothing. */
    @Test
    fun aSecondCloseAfterPopAndAReleaseAddNothing() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.closeAfterPop(router)
        val logAfter = log.toList()
        val ffiAfter = core.callLog.toList()

        lifecycle.closeAfterPop(router)
        lifecycle.release()

        assertEquals(logAfter, log)
        assertEquals(ffiAfter, core.callLog.toList())
    }

    /** After close, every method is a no-op — no reattach of a closed controller. */
    @Test
    fun afterCloseEveryMethodIsANoop() {
        val core = FakeSessionCore(chatId = "chat-a")
        val lifecycle = newLifecycle(core)
        lifecycle.closeAfterPop(router)
        val logAfter = log.toList()
        val ffiAfter = core.callLog.toList()

        lifecycle.onWillAppear(hasAppeared = true)
        lifecycle.onDidAppear(router)
        lifecycle.leave()
        lifecycle.onConfigurationDispose()

        assertEquals(logAfter, log)
        assertEquals(ffiAfter, core.callLog.toList())
    }

    // ── release alone (76's safety net) ─────────────────────────────────

    /**
     * The NavEntry close safety net (a popToRoot intermediate, a
     * `setRoutes` removal): the controller and the transcript close, but
     * no `sessionDidClose` — the entry was never the delivered `from`.
     */
    @Test
    fun releaseAloneClosesWithoutSessionDidClose() {
        val core = FakeSessionCore(chatId = "chat-a")
        val transcript = fakeTranscript()
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = UnconfinedTestDispatcher(),
            isMainThread = { false },
        )
        val lifecycle = SessionNavLifecycle(
            chatId = controller.chatId,
            controller = controller,
            transcript = transcript,
            edits = edits,
            drafts = drafts,
        )
        val ffiBefore = core.callLog.toList()

        lifecycle.release()

        assertTrue("the log stays empty — no router call", log.isEmpty())
        assertEquals(
            listOf("setViewAttached(false)", "markSeen"),
            core.callLog.drop(ffiBefore.size),
        )
        assertTrue("the transcript is closed", transcript.isClosed)
        assertTrue(lifecycle.isClosed)
    }

    /** The recording router: every abstract member, only two wired. */
    private class RecordingRouter(private val log: MutableList<String>) : AppRouter {
        override fun openSession(chatId: String) {}
        override fun openSession(chatId: String, handoff: DraftHandoff?) {}
        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() {}
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
        override fun sessionDidAppear(chatId: String) {
            log += "sessionDidAppear($chatId)"
        }

        override fun sessionDidClose(chatId: String) {
            log += "sessionDidClose($chatId)"
        }
    }
}
