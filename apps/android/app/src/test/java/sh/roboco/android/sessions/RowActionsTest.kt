package sh.roboco.android.sessions

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.SectionView

/**
 * `CoreRowActions` (android-ui-parity #42): every row of the ticket's
 * failure table, the trim/skip rules, the "Archived · Undo" pair, the
 * serial-queue ordering, the notice collector, `sectionsFor`, and the
 * no-FFI-on-the-main-thread rule.
 *
 * Plain JUnit 4, no Robolectric: the FFI seam is [FakeWrites] (a plain
 * [SessionWrites] fake), the toaster is [RecordingToaster], `main` and
 * `io` are two [StandardTestDispatcher]s over one scheduler, and
 * `log = {}` keeps `android.util.Log` off the plain JVM. The generated
 * `CoreException` variants and `ClientEvent` records are plain Kotlin
 * classes whose initialisation touches no JNA (the property the
 * #40 fixtures rely on).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RowActionsTest {

    // ------------------------------------------------------------ the fakes

    /** The [Toaster] fake: records every `show`, with the thread it ran on. */
    private class RecordingToaster : Toaster {
        data class Shown(val text: String, val action: String?, val onAction: (() -> Unit)?)
        val shown = CopyOnWriteArrayList<Shown>()
        val threads = CopyOnWriteArrayList<Thread>()
        val texts: List<String> get() = shown.map { it.text }
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            shown += Shown(text, action, onAction)
            threads += Thread.currentThread()
        }
    }

    /**
     * The [SessionWrites] fake: records every call and the thread each
     * ran on, sleeps [sleepMs] before returning (the ordering test), and
     * throws one scripted [errors] entry per call (empty = success).
     */
    private class FakeWrites : SessionWrites {
        val calls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val errors = ArrayDeque<Throwable>()
        var sleepMs = 0L
        var nextSectionId = "scoped:s1"

        private fun go(call: String) {
            calls += call
            threads += Thread.currentThread()
            if (sleepMs > 0) Thread.sleep(sleepMs)
            errors.removeFirstOrNull()?.let { throw it }
        }

        override fun pinSession(chatId: String) = go("pin $chatId")
        override fun unpinSession(chatId: String) = go("unpin $chatId")
        override fun archiveSession(chatId: String) = go("archive $chatId")
        override fun unarchiveSession(chatId: String) = go("unarchive $chatId")
        override fun deleteSession(chatId: String) = go("delete $chatId")
        override fun pinSessionAt(chatId: String, after: String?, before: String?) =
            go("pinAt $chatId after=${after ?: "none"} before=${before ?: "none"}")
        override fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?) =
            go("place $chatId=${sectionId ?: "none"} after=${after ?: "none"} before=${before ?: "none"}")
        override fun assignSection(chatId: String, sectionId: String?) =
            go("assign $chatId=${sectionId ?: "none"}")
        override fun movePin(chatId: String, after: String?, before: String?) =
            go("movePin $chatId after=${after ?: "none"} before=${before ?: "none"}")
        override fun renameSession(chatId: String, title: String) = go("rename $chatId=$title")
        override fun createSection(name: String, engineId: String?): String {
            go("createSection $name engine=${engineId ?: "none"}")
            return nextSectionId
        }
        override fun renameSection(sectionId: String, name: String) = go("renameSection $sectionId=$name")
        override fun deleteSection(sectionId: String) = go("deleteSection $sectionId")
    }

    /** Everything one deterministic test needs, over one shared scheduler. */
    private class Harness(
        val fake: FakeWrites,
        val toaster: RecordingToaster,
        val actions: CoreRowActions,
        val scope: CoroutineScope,
    )

    /**
     * The deterministic harness: `main` and `io` are two
     * [StandardTestDispatcher]s over the test's scheduler, so
     * [advanceUntilIdle] runs every hop in submission order and the
     * serial-lane FIFO is observable. The client is [FakeWrites] unless
     * [clientAvailable] is false (the no-client rows).
     */
    private fun TestScope.newHarness(
        clientAvailable: Boolean = true,
        sections: List<SectionView> = emptyList(),
        log: (String) -> Unit = {},
    ): Harness {
        val fake = FakeWrites()
        val toaster = RecordingToaster()
        val main = StandardTestDispatcher(testScheduler)
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + main)
        val actions = CoreRowActions(
            writes = { if (clientAvailable) fake else null },
            sections = { sections },
            toaster = toaster,
            scope = scope,
            io = io,
            main = main,
            log = log,
        )
        return Harness(fake, toaster, actions, scope)
    }

    // ------------------------------------------------------------ the write surface

    @Test
    fun archiveOkToastsArchivedUndoAndTheUndoUnarchivesWithoutAToast() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf("archive a"), h.fake.calls)
        assertEquals(listOf(ActionOutcome.Ok), outcomes)
        assertEquals(1, h.toaster.shown.size)
        val toast = h.toaster.shown[0]
        assertEquals("Archived", toast.text)
        assertEquals("Undo", toast.action)
        val undo = toast.onAction
        assertNotNull(undo)
        undo!!.invoke()
        advanceUntilIdle()
        assertEquals(listOf("archive a", "unarchive a"), h.fake.calls)
        assertEquals(1, h.toaster.shown.size) // the Undo's success adds no toast
        assertEquals(listOf(ActionOutcome.Ok), outcomes) // the Undo's own onResult defaults to {}
    }

    @Test
    fun undoWhileTheEngineIsOfflineToastsEngineNotConnected() = runTest {
        val h = newHarness()
        h.actions.archive("a")
        advanceUntilIdle()
        h.fake.errors += CoreException.HostUnavailable("Engine is offline; reconnecting")
        val undo = h.toaster.shown.single().onAction
        assertNotNull(undo)
        undo!!.invoke()
        advanceUntilIdle()
        assertEquals(listOf("archive a", "unarchive a"), h.fake.calls)
        assertEquals(listOf("Archived", "Engine not connected"), h.toaster.texts)
    }

    @Test
    fun archiveWithTheEngineOfflineShowsExactlyOneToastAndReportsEngineOffline() = runTest {
        val h = newHarness()
        h.fake.errors += CoreException.HostUnavailable("Engine is offline; reconnecting")
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf(ActionOutcome.EngineOffline), outcomes)
        assertEquals(listOf("Engine not connected"), h.toaster.texts)
        assertEquals(1, h.toaster.shown.size)
        assertNull(h.toaster.shown[0].action)
        assertNull(h.toaster.shown[0].onAction)
        assertEquals(listOf("archive a"), h.fake.calls)
    }

    @Test
    fun deleteRoutesToDeleteSessionWithNoToastAndOfflineToastsOnce() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.delete("a", outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf("delete a"), h.fake.calls)
        assertEquals(listOf(ActionOutcome.Ok), outcomes)
        assertTrue("a confirmed delete has no toast and no Undo", h.toaster.shown.isEmpty())

        h.fake.errors += CoreException.HostUnavailable("Engine is offline; reconnecting")
        h.actions.delete("b", outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf(ActionOutcome.Ok, ActionOutcome.EngineOffline), outcomes)
        assertEquals(listOf("Engine not connected"), h.toaster.texts)
    }

    @Test
    fun dropsRouteToPinSessionAtAndPlaceSession() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.applyDrop("a", SessionDropWrite.PinAt("p1", null), outcomes::add)
        h.actions.applyDrop("a", SessionDropWrite.Place("s1", null, "b"), outcomes::add)
        h.actions.applyDrop("a", SessionDropWrite.LeaveSection, outcomes::add)
        h.actions.applyDrop("a", SessionDropWrite.NoMove, outcomes::add)
        advanceUntilIdle()
        assertEquals(
            listOf("pinAt a after=p1 before=none", "place a=s1 after=none before=b", "assign a=none"),
            h.fake.calls,
        )
        assertEquals(
            listOf(ActionOutcome.Skipped, ActionOutcome.Ok, ActionOutcome.Ok, ActionOutcome.Ok),
            outcomes,
        )
    }

    @Test
    fun setPinnedRoutesToPinSessionAndUnpinSession() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.setPinned("a", true, outcomes::add)
        h.actions.setPinned("a", false, outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf("pin a", "unpin a"), h.fake.calls)
        assertEquals(listOf(ActionOutcome.Ok, ActionOutcome.Ok), outcomes)
    }

    @Test
    fun pinAndSectionWritesWithUnsupportedToastThePinsCopyAndReportPinsNotSynced() = runTest {
        val h = newHarness()
        repeat(3) { h.fake.errors += CoreException.Unsupported("pins are not synced yet") }
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.setPinned("a", true, outcomes::add)
        h.actions.setPinned("a", false, outcomes::add)
        h.actions.move("a", "s", outcomes::add)
        advanceUntilIdle()
        assertEquals(
            listOf(ActionOutcome.PinsNotSynced, ActionOutcome.PinsNotSynced, ActionOutcome.PinsNotSynced),
            outcomes,
        )
        assertEquals(listOf(PINS_NOT_SYNCED_TOAST, PINS_NOT_SYNCED_TOAST, PINS_NOT_SYNCED_TOAST), h.toaster.texts)
    }

    @Test
    fun chatWritesWithUnsupportedToastTheReasonNeverThePinsCopy() = runTest {
        val h = newHarness()
        repeat(3) { h.fake.errors += CoreException.Unsupported("engine unknown method") }
        h.fake.errors += CoreException.Unsupported("")
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        h.actions.unarchive("a", outcomes::add)
        h.actions.rename("a", "New", outcomes::add)
        h.actions.rename("b", "New", outcomes::add)
        advanceUntilIdle()
        assertEquals(List(4) { ActionOutcome.Failed }, outcomes)
        assertEquals(
            listOf("engine unknown method", "engine unknown method", "engine unknown method", GENERIC_FAILURE),
            h.toaster.texts,
        )
    }

    @Test
    fun notFoundAndClosedReportWithoutToasts() = runTest {
        val h = newHarness()
        h.fake.errors += CoreException.NotFound("chat")
        h.fake.errors += CoreException.Closed()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        h.actions.unarchive("a", outcomes::add)
        advanceUntilIdle()
        assertEquals(listOf(ActionOutcome.Gone, ActionOutcome.NoClient), outcomes)
        assertTrue(h.toaster.shown.isEmpty())
    }

    @Test
    fun genericFailuresToastTheReasonOrTheGenericText() = runTest {
        val h = newHarness()
        h.fake.errors += CoreException.InvalidArgument("x")
        h.fake.errors += CoreException.InvalidArgument("")
        h.fake.errors += IllegalStateException("boom")
        h.fake.errors += CoreException.HostException("bad request")
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.setPinned("a", true, outcomes::add)
        h.actions.rename("a", "T", outcomes::add)
        h.actions.unarchive("a", outcomes::add)
        h.actions.move("a", "s", outcomes::add)
        advanceUntilIdle()
        assertEquals(List(4) { ActionOutcome.Failed }, outcomes) // HostException included
        assertEquals(
            listOf("x", GENERIC_FAILURE, GENERIC_FAILURE, "bad request"),
            h.toaster.texts,
        )
    }

    @Test
    fun moveAssignsNullAsNoSectionAndTheScopedSectionId() = runTest {
        val h = newHarness()
        h.actions.move("a", null)
        h.actions.move("a", "s")
        advanceUntilIdle()
        assertEquals(listOf("assign a=none", "assign a=s"), h.fake.calls)
    }

    @Test
    fun renameAndSectionWritesTrimAndSkipBlankText() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        val created = mutableListOf<String>()
        h.actions.rename("a", "  New  ", outcomes::add)
        h.actions.rename("b", "   ", outcomes::add)
        h.actions.createSection("  X  ", "e1", outcomes::add, onCreated = { created += it })
        h.actions.createSection("   ", "e1", outcomes::add, onCreated = { created += it })
        h.actions.renameSection("s", " N ", outcomes::add)
        h.actions.renameSection("s", " ", outcomes::add)
        advanceUntilIdle()
        assertEquals(
            listOf("rename a=New", "createSection X engine=e1", "renameSection s=N"),
            h.fake.calls,
        )
        assertEquals(1, created.size) // the skipped create never reports an id
        // A skip reports synchronously on the caller (the ticket's
        // `if (t.isEmpty()) { onResult(Skipped); return }`), before any
        // queued write comes back from the serial lane: the three skips
        // land first, then the three Oks in call order.
        assertEquals(
            listOf(
                ActionOutcome.Skipped, ActionOutcome.Skipped, ActionOutcome.Skipped,
                ActionOutcome.Ok, ActionOutcome.Ok, ActionOutcome.Ok,
            ),
            outcomes,
        )
        assertTrue(h.toaster.shown.isEmpty())
    }

    @Test
    fun createSectionPassesTheEngineAndReportsTheReturnedId() = runTest {
        val h = newHarness()
        h.fake.nextSectionId = "e1:s9"
        val created = mutableListOf<String>()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.createSection("A", "e1", outcomes::add, onCreated = { created += it })
        advanceUntilIdle()
        assertEquals(listOf("createSection A engine=e1"), h.fake.calls)
        assertEquals(listOf("e1:s9"), created)
        assertEquals(listOf(ActionOutcome.Ok), outcomes)
    }

    // ------------------------------------------------------------ order and threads

    @Test
    fun writesReachTheFakeInCallOrderEvenWhenEachSleeps50Ms() = runTest {
        val h = newHarness()
        h.fake.sleepMs = 50
        h.actions.archive("a")
        h.actions.unarchive("a")
        h.actions.movePin("p1", after = null, before = "p2")
        h.actions.movePin("p2", after = "p1", before = null)
        h.actions.movePin("p3", after = null, before = null)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "archive a",
                "unarchive a",
                "movePin p1 after=none before=p2",
                "movePin p2 after=p1 before=none",
                "movePin p3 after=none before=none",
            ),
            h.fake.calls,
        )
    }

    @Test
    fun ffiNeverRunsOnMainAndToastsAndResultsArriveOnMain() = runBlocking {
        val testThread = Thread.currentThread()
        // Two real single-thread lanes, so thread identity is observable
        // (the #40 threading test's property): `main` for the dispatch
        // coroutine, `io` for the FFI call.
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "rowactions-main") }
            .asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rowactions-io") }
            .asCoroutineDispatcher()
        try {
            val fake = FakeWrites()
            val toaster = RecordingToaster()
            val outcomes = CopyOnWriteArrayList<Pair<ActionOutcome, Thread>>()
            val scope = CoroutineScope(SupervisorJob() + main)
            val actions = CoreRowActions(
                writes = { fake },
                sections = { emptyList() },
                toaster = toaster,
                scope = scope,
                io = io,
                main = main,
                log = {},
            )
            // A probe coroutine tells us which thread "main" is here.
            val mainThreads = CopyOnWriteArrayList<Thread>()
            scope.launch(main) { mainThreads += Thread.currentThread() }
            actions.archive("a") { outcomes += it to Thread.currentThread() }
            actions.setPinned("b", true) { outcomes += it to Thread.currentThread() }
            // `onCreated` runs on the same main hop as the toast and
            // onResult; record its thread too (the ticket's AC).
            val createdThreads = CopyOnWriteArrayList<Thread>()
            actions.createSection(
                "N",
                null,
                { outcomes += it to Thread.currentThread() },
                onCreated = { createdThreads += Thread.currentThread() },
            )
            withTimeout(5_000) {
                while (outcomes.size < 3 || mainThreads.isEmpty() || createdThreads.isEmpty()) delay(10)
            }
            assertEquals(listOf("archive a", "pin b", "createSection N engine=none"), fake.calls)
            assertEquals(1, toaster.shown.size)
            assertEquals("Archived", toaster.shown[0].text)
            val mainThread = mainThreads[0]
            // The FFI ran, never on the caller's thread and never on main.
            assertTrue(fake.threads.isNotEmpty())
            assertTrue(fake.threads.none { it === testThread })
            assertTrue(fake.threads.none { it === mainThread })
            // Toasts, onResults and onCreated all arrived on main.
            assertTrue(toaster.threads.all { it === mainThread })
            assertTrue(outcomes.all { it.second === mainThread })
            assertTrue(createdThreads.all { it === mainThread })
        } finally {
            main.close()
            io.close()
        }
    }

    @Test
    fun aNullClientMakesEveryWriteReportNoClientWithoutToasts() = runTest {
        val logged = mutableListOf<String>()
        val h = newHarness(clientAvailable = false, log = { logged += it })
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.setPinned("a", true, outcomes::add)
        h.actions.archive("a", outcomes::add)
        h.actions.unarchive("a", outcomes::add)
        h.actions.move("a", null, outcomes::add)
        h.actions.movePin("a", null, null, outcomes::add)
        h.actions.rename("a", "T", outcomes::add)
        h.actions.createSection("S", null, outcomes::add, onCreated = { fail("onCreated must not run") })
        h.actions.renameSection("s", "N", outcomes::add)
        h.actions.deleteSection("s", outcomes::add)
        advanceUntilIdle()
        assertEquals(List(9) { ActionOutcome.NoClient }, outcomes)
        assertTrue(h.fake.calls.isEmpty())
        assertTrue(h.toaster.shown.isEmpty())
        assertEquals(
            listOf(
                "pin skipped: no client",
                "archive skipped: no client",
                "unarchive skipped: no client",
                "assign skipped: no client",
                "move pin skipped: no client",
                "rename skipped: no client",
                "section skipped: no client",
                "rename section skipped: no client",
                "delete section skipped: no client",
            ),
            logged,
        )
    }

    // ------------------------------------------------------------ sectionsFor

    @Test
    fun sectionsForReturnsTheEngineSectionsInSnapshotOrderEmptyOnesIncluded() = runTest {
        val a = section("e1:s1", name = "A", engineId = "e1", rows = listOf(row("r1")))
        val b = section("e2:s1", name = "B", engineId = "e2")
        val c = section("e1:s2", name = "C", engineId = "e1")
        val h = newHarness(sections = listOf(a, b, c))
        assertEquals(listOf(a, c), h.actions.sectionsFor("e1"))
        assertEquals(listOf(b), h.actions.sectionsFor("e2"))
        assertEquals(emptyList<SectionView>(), h.actions.sectionsFor("e9"))
    }

    @Test
    fun sectionsForIsEmptyWithoutASnapshot() = runTest {
        val h = newHarness(sections = emptyList())
        assertEquals(emptyList<SectionView>(), h.actions.sectionsFor("e1"))
    }

    // ------------------------------------------------------------ notices

    @Test
    fun noticeToastsShowEachMessageOnceAndSkipBlankOnes() = runTest {
        val h = newHarness()
        val notices = MutableSharedFlow<String>(extraBufferCapacity = 64)
        h.actions.startNoticeToasts(notices)
        advanceUntilIdle() // the collector subscribes
        h.actions.startNoticeToasts(notices) // ignored: a collector is already active
        notices.tryEmit("Couldn't save pins: boom")
        notices.tryEmit("  ")
        advanceUntilIdle()
        assertEquals(listOf("Couldn't save pins: boom"), h.toaster.texts)
    }

    @Test
    fun noticeToastsConsumeAFlowOfOnce() = runTest {
        val h = newHarness()
        h.actions.startNoticeToasts(flowOf("Couldn't save pins: boom", "  "))
        advanceUntilIdle()
        assertEquals(listOf("Couldn't save pins: boom"), h.toaster.texts)
    }

    @Test
    fun noticesExtractsTrimmedNonBlankNoticeMessagesOnly() = runTest {
        val events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
        val collected = mutableListOf<String>()
        val job = launch { events.notices().toList(collected) }
        advanceUntilIdle()
        events.tryEmit(ClientEvent.Notice(message = "Couldn't save pins: boom"))
        events.tryEmit(ClientEvent.WorkspaceChanged(revision = 1uL))
        events.tryEmit(ClientEvent.Notice(message = "  "))
        events.tryEmit(ClientEvent.Notice(message = " padded "))
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf("Couldn't save pins: boom", "padded"), collected)
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun cancellingTheScopeMidCallShowsNoToastAndNoCrash() = runTest {
        val h = newHarness()
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        h.scope.cancel()
        advanceUntilIdle()
        assertTrue(h.toaster.shown.isEmpty())
        assertTrue(outcomes.isEmpty())
        assertTrue(h.fake.calls.isEmpty())
    }

    @Test
    fun aCancellationExceptionThrownByTheCallIsRethrownNeverToasted() = runTest {
        val h = newHarness()
        h.fake.errors += CancellationException("stop")
        val outcomes = mutableListOf<ActionOutcome>()
        h.actions.archive("a", outcomes::add)
        advanceUntilIdle()
        assertTrue(h.toaster.shown.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    // ------------------------------------------------------------ report

    @Test
    fun reportMapsErrorsThroughTheSidebarTableWithoutCallingTheFfi() = runTest {
        val logged = mutableListOf<String>()
        val h = newHarness(log = { logged += it })
        h.actions.report(CoreException.HostUnavailable("x"))
        h.actions.report(CoreException.Unsupported("pins are not synced yet"))
        h.actions.report(CoreException.NotFound("x"))
        h.actions.report(CancellationException())
        assertEquals(
            listOf("Engine not connected", PINS_NOT_SYNCED_TOAST),
            h.toaster.texts,
        )
        assertTrue(h.fake.calls.isEmpty())
        // HostUnavailable, Unsupported and NotFound are all logged (the
        // failure table's Log column says "yes" for NotFound too — only
        // the toast is suppressed); CancellationException returns silently.
        assertEquals(3, logged.size)
        assertTrue(logged.all { it.startsWith("write failed: ") })
    }

    // ------------------------------------------------------------ Noop

    @Test
    fun noopDropsEveryWriteAndListsNothing() {
        val outcomes = mutableListOf<ActionOutcome>()
        val created = mutableListOf<String>()
        val noop = RowActions.Noop
        noop.setPinned("a", true, outcomes::add)
        noop.archive("a", outcomes::add)
        noop.unarchive("a", outcomes::add)
        noop.move("a", null, outcomes::add)
        noop.movePin("a", null, null, outcomes::add)
        noop.rename("a", "T", outcomes::add)
        noop.createSection("S", null, outcomes::add, onCreated = { created += it })
        noop.renameSection("s", "N", outcomes::add)
        noop.deleteSection("s", outcomes::add)
        noop.report(IllegalStateException("x"))
        assertTrue(outcomes.isEmpty())
        assertTrue(created.isEmpty())
        assertTrue(noop.sectionsFor("e1").isEmpty())
    }
}
