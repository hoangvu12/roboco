package sh.roboco.android.sessions

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.roboco.android.core.Prefs
import sh.roboco.android.core.WorkspaceFeed
import sh.roboco.android.core.testPrefsStore
import sh.roboco.android.design.Haptic
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.FrontPage

/**
 * `FrontPageModel` and `WorkspaceFeed` (android-ui-parity #40): the load
 * gating, the revision-keyed rebuild (one value per flip, none for a
 * re-read at the same revision), the feed's burst coalescing, and the
 * no-FFI-on-the-main-thread rule.
 *
 * Plain JUnit 4: the FFI seam is [FakeWorkspaceCore] and DataStore runs
 * over temp files; the threading test swaps `Dispatchers.Main` and runs
 * the lanes on `Dispatchers.Default`, asserting every FFI call thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrontPageModelTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private var mainSwapped = false

    @Before
    fun rememberSwap() {
        mainSwapped = false
    }

    @After
    fun restoreMain() {
        if (mainSwapped) Dispatchers.resetMain()
    }

    private fun oneOff(): FrontPage = front(
        pinned = listOf(row("p1"), row("p2")),
        sections = listOf(
            section("e1:s1", name = "P0", rows = listOf(row("a"), row("b"))),
            section("e1:s2", name = "Mobile", rows = listOf(row("c"), row("d"))),
        ),
        recent = listOf(row("r1"), row("r2"), row("r3"), row("r4")),
    )

    // ---------------------------------------------------- the load gate

    @Test
    fun uiStaysNullUntilPrefsFoldAndASnapshotAreLoaded() = runTest {
        val fixture = newFrontFixture(tmpFolder.root, "gate", snapshot(1uL, oneOff()))
        // Nothing has loaded and no first read happened yet.
        assertNull(fixture.model.ui.value)
        advanceUntilIdle()
        // Prefs and fold loaded, but no first snapshot: still null.
        assertNull(fixture.model.ui.value)

        fixture.feed.start()
        advanceUntilIdle()
        assertNotNull(fixture.model.ui.value)
    }

    @Test
    fun uiCarriesTheSectionListAndTheCollapsedIds() = runTest {
        val fixture = newFrontFixture(
            tmpFolder.root, "content", snapshot(3uL, oneOff()),
            engineList = listOf(engine("A", name = "Studio")),
        )
        fixture.feed.start()
        advanceUntilIdle()

        val ui = fixture.model.ui.value!!
        assertEquals(3uL, ui.revision)
        assertTrue(ui.synced)
        assertEquals(
            listOf(FrontPageSections.PINNED, "e1:s1", "e1:s2", FrontPageSections.RECENT),
            ui.sections.map { it.id },
        )
        assertEquals(4, ui.sections[3].rows.size)
        assertTrue(ui.collapsedIds.isEmpty())
        assertTrue(ui.liveOverrides.isEmpty())

        // A phone-local fold through the model lands in collapsedIds.
        fixture.model.toggleSection(FrontPageSections.PINNED, RecordingFeedback())
        advanceUntilIdle()
        assertEquals(setOf(FrontPageSections.PINNED), fixture.model.ui.value!!.collapsedIds)
    }

    // --------------------------------------------------- the rebuild rule

    @Test
    fun theSameRevisionEmitsNoNewValue() = runTest {
        val fixture = newFrontFixture(tmpFolder.root, "same-rev", snapshot(1uL, oneOff()))
        fixture.feed.start()
        advanceUntilIdle()
        val emissions = mutableListOf<FrontPageUi?>()
        // Unconfined: `advanceUntilIdle` stops once no *foreground* task is
        // left, so a backgroundScope collector on the standard dispatcher
        // may never run (or run late and miss values). This one collects
        // the current value now and every later value as it is set.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.ui.toList(emissions) }
        advanceUntilIdle()
        val before = emissions.size
        assertEquals(1, before)

        // A new snapshot object at the same revision, plus five events…
        fixture.core.ws = snapshot(1uL, oneOff())
        repeat(5) { fixture.feed.onEvent(ClientEvent.WorkspaceChanged(revision = 1uL)) }
        advanceUntilIdle()
        // …the event gate blocks them all (still one read)…
        assertEquals(1, fixture.core.workspaceCalls.get())

        // …and a forced read re-emits nothing either (the drain's own
        // revision gate).
        fixture.feed.start()
        advanceUntilIdle()
        assertEquals(2, fixture.core.workspaceCalls.get())
        assertEquals(before, emissions.size)
    }

    @Test
    fun flippingGroupByProjectEmitsExactlyOneNewValue() = runTest {
        val fixture = newFrontFixture(tmpFolder.root, "group-flip", snapshot(1uL, oneOff()))
        fixture.feed.start()
        advanceUntilIdle()
        val emissions = mutableListOf<FrontPageUi?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.ui.toList(emissions) }
        advanceUntilIdle()
        val before = emissions.size

        fixture.prefs.setGroupByProject(true)
        advanceUntilIdle()
        assertEquals(before + 1, emissions.size)
        // Recent is gone while grouping is on; only the front's sections
        // (Pinned + the user sections) remain.
        assertTrue(emissions.last()!!.sections.none { it.id == FrontPageSections.RECENT })

        fixture.prefs.setGroupByProject(true) // same value: no emission
        advanceUntilIdle()
        assertEquals(before + 1, emissions.size)
        fixture.prefs.setGroupByProject(false)
        advanceUntilIdle()
        assertEquals(before + 2, emissions.size)
    }

    @Test
    fun aFoldFlipEmitsExactlyOneNewValue() = runTest {
        val fixture = newFrontFixture(tmpFolder.root, "fold-flip", snapshot(1uL, oneOff()))
        fixture.feed.start()
        advanceUntilIdle()
        val emissions = mutableListOf<FrontPageUi?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.ui.toList(emissions) }
        advanceUntilIdle()
        val before = emissions.size

        // A user-section fold: pending flips the value, the engine write
        // confirms it — but the derived ui is equal, so one value only.
        fixture.model.toggleSection("e1:s1", RecordingFeedback())
        advanceUntilIdle()
        assertEquals(before + 1, emissions.size)
        assertEquals(setOf("e1:s1"), emissions.last()!!.collapsedIds)
        assertEquals(listOf("e1:s1" to true), fixture.core.setCalls.toList())
    }

    // ------------------------------------------------------ the feed

    @Test
    fun aBurstOfEventsCostsAtMostTwoReads() = runTest {
        val core = FakeWorkspaceCore(snapshot(1uL, oneOff()))
        core.engineList = listOf(engine("A"))
        val feed = WorkspaceFeed(
            core = { core },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            io = StandardTestDispatcher(testScheduler),
        )
        repeat(5) { feed.onEvent(ClientEvent.WorkspaceChanged(revision = 1uL)) }
        advanceUntilIdle()
        assertEquals(1, core.workspaceCalls.get())
        assertEquals(1uL, feed.snapshot.value?.revision)

        // Same revision five more times: no read, no new snapshot.
        val first = feed.snapshot.value
        repeat(5) { feed.onEvent(ClientEvent.WorkspaceChanged(revision = 1uL)) }
        advanceUntilIdle()
        assertEquals(1, core.workspaceCalls.get())
        assertSame(first, feed.snapshot.value)

        // A session event is not a workspace event.
        feed.onEvent(ClientEvent.SessionChanged(chatId = "c", revision = 1uL))
        advanceUntilIdle()
        assertEquals(1, core.workspaceCalls.get())

        // Connectivity refreshes the engines list.
        feed.onEvent(ClientEvent.ConnectivityChanged(connectivity()))
        advanceUntilIdle()
        assertEquals(listOf("A"), feed.engines.value.map { it.engineId })

        // A mid-read event costs exactly one extra read: the channel
        // conflates whatever arrives while a read is in flight. So this
        // burst is the triggered read plus the one extra — two reads (the
        // ticket's "at most twice"), on top of the first read above.
        core.duringWorkspace = { feed.onEvent(ClientEvent.WorkspaceChanged(revision = 2uL)) }
        core.ws = snapshot(2uL, oneOff())
        feed.onEvent(ClientEvent.WorkspaceChanged(revision = 2uL))
        advanceUntilIdle()
        core.duringWorkspace = null
        assertEquals(1 + 2, core.workspaceCalls.get())
        assertEquals(2uL, feed.snapshot.value?.revision)
    }

    // ------------------------------------------------- main-thread safety

    @Test
    fun noFfiCallEverRunsOnTheMainThread() {
        val main = Thread.currentThread()
        Dispatchers.setMain(StandardTestDispatcher())
        mainSwapped = true
        runBlocking {
            val core = FakeWorkspaceCore(snapshot(1uL, oneOff()))
            core.engineList = listOf(engine("A"))
            val io = Dispatchers.Default.limitedParallelism(1)
            // The app-shaped scope: Main (a dispatcher nothing advances,
            // like Robolectric's paused looper) with real serial lanes.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val feed = WorkspaceFeed(core = { core }, scope = scope, io = io)
            feed.start()
            feed.onEvent(ClientEvent.EnginesChanged)
            withTimeout(5_000) {
                feed.snapshot.first { it != null }
                feed.engines.first { it.isNotEmpty() }
            }

            val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                runThreadingChecks(core, feed, scope, prefsScope, io, main)
            } finally {
                // No real-thread lane may outlive the test: FoldState's
                // background persist (collapseAll's local write) used to
                // land after TemporaryFolder deleted the dir, and its
                // FileNotFoundException on `threads.preferences_pb.tmp` was
                // reported by kotlinx-coroutines-test to whichever runTest
                // ran next (uiStaysNullUntilPrefsFoldAndASnapshotAreLoaded,
                // the slower Windows runner hitting it every run).
                prefsScope.coroutineContext[Job]!!.cancelAndJoin()
                // Main never advances here: cancel only (a join would wait
                // for a dispatch that never comes).
                scope.cancel()
            }
        }
    }

    private suspend fun runThreadingChecks(
        core: FakeWorkspaceCore,
        feed: WorkspaceFeed,
        scope: CoroutineScope,
        prefsScope: CoroutineScope,
        io: kotlinx.coroutines.CoroutineDispatcher,
        main: Thread,
    ) {
        run {
            val store = testPrefsStore(prefsScope, File(tmpFolder.root, "threads.preferences_pb"))
            val prefs = Prefs(store, prefsScope, io = Dispatchers.IO.limitedParallelism(1))
            val fold = FoldState(store, core, prefsScope, io = io)
            // Ticket 50: the health model rides the same Main-imposter
            // scope — its StateFlow starts at Healthy and combine reads
            // the value without needing the (never-advanced) collector.
            val health = EngineHealthModel(feed.engines, feed.connectivity, scope)
            val model = FrontPageModel(feed, prefs, fold, health, scope, compute = Dispatchers.Default)
            withTimeout(5_000) {
                prefs.awaitLoaded()
                fold.awaitLoaded()
            }

            // A user-section toggle driven from the main thread: the state
            // flips synchronously, the engine write runs off main.
            val feedback = RecordingFeedback()
            model.toggleSection("e1:s1", feedback)
            assertTrue(fold.isCollapsed("e1:s1", section("e1:s1", collapsed = false)))
            withTimeout(5_000) {
                while (core.setCalls.isEmpty()) delay(10)
            }
            assertEquals(listOf(Haptic.Select), feedback.haptics)

            // Collapse All from the main thread: the still-open pinned
            // folds (a local write) and the still-open "Mobile" (e1:s2) gets
            // its engine write; the pending e1:s1 is skipped. One Select per
            // toggled id.
            val collapseFeedback = RecordingFeedback()
            model.collapseAll(collapseFeedback)
            assertEquals(listOf(Haptic.Select, Haptic.Select), collapseFeedback.haptics)
            assertTrue(fold.isCollapsed(FrontPageSections.PINNED, null))
            withTimeout(5_000) {
                while (core.setCalls.size < 2) delay(10)
            }
            assertEquals(listOf("e1:s1" to true, "e1:s2" to true), core.setCalls.toList())
            assertTrue(core.callThreads.isNotEmpty())
            assertTrue(core.callThreads.none { it === main })
        }
    }
}
