package sh.roboco.android.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.roboco.android.core.testPrefsStore
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.NoFeedback
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState

/**
 * `FoldState` (android-ui-parity #40): the phone-local folds, the
 * engine-synced user-section folds, the offline overrides and the
 * reconciler — every row of the ticket's fold-state table.
 *
 * Plain JUnit 4, no Robolectric: the FFI seam is [FakeWorkspaceCore], the
 * DataStore runs over a temp file on the test scheduler, and
 * `android.util.Log` is swapped out via [foldLogW].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoldStateTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private var savedLog: ((String, Throwable?) -> Unit)? = null
    private val logged = mutableListOf<Pair<String, Throwable?>>()

    /** One live FoldState at a time; [restart] closes it and reads the same file back. */
    private var fixture: Fixture? = null

    private class Fixture(
        val fold: FoldState,
        val store: DataStore<Preferences>,
        val scope: CoroutineScope,
    )

    @Before
    fun swapLog() {
        savedLog = foldLogW
        foldLogW = { message, error -> logged += message to error }
    }

    @After
    fun restoreLog() {
        foldLogW = savedLog!!
        fixture?.scope?.cancel()
    }

    private fun TestScope.newFold(
        core: FakeWorkspaceCore,
        fileName: String = "fold",
    ): FoldState {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        // OkioStorage-backed: the File storage cannot overwrite on Windows (see testPrefsStore).
        val store = testPrefsStore(scope, File(tmpFolder.root, "$fileName.preferences_pb"))
        val fold = FoldState(store, core, scope, io = StandardTestDispatcher(testScheduler))
        fixture = Fixture(fold, store, scope)
        return fold
    }

    /** A process restart: the old scope releases the file, a new FoldState reads it back. */
    private suspend fun TestScope.restart(core: FakeWorkspaceCore, fileName: String = "fold"): FoldState {
        val old = fixture!!
        old.scope.cancel()
        old.scope.coroutineContext.job.join()
        return newFold(core, fileName)
    }

    /** Reads a string-set key through the live store (no second DataStore). */
    private suspend fun stored(key: Preferences.Key<Set<String>>): Set<String> =
        fixture!!.store.data.first()[key] ?: emptySet()

    // -------------------------------------------------------- local folds

    @Test
    fun aLocalToggleFlipsImmediatelyPersistsAndHaptics() = runTest {
        val fold = newFold(FakeWorkspaceCore())
        val feedback = RecordingFeedback()
        advanceUntilIdle()

        fold.toggle(FrontPageSections.PINNED, section = null, feedback = feedback)
        // Synchronous, before any IO: the chevron flips in the tap's frame.
        assertTrue(fold.isCollapsed(FrontPageSections.PINNED, null))
        assertEquals(listOf(Haptic.Select), feedback.haptics)
        advanceUntilIdle()

        // Persisted under the iOS key, and it survives a restart.
        assertEquals(setOf("pinned"), stored(stringSetPreferencesKey("collapsedSections")))
        val restarted = restart(FakeWorkspaceCore())
        advanceUntilIdle()
        assertTrue(restarted.isCollapsed(FrontPageSections.PINNED, null))

        // A second tap unfolds, again with exactly one haptic.
        val secondFeedback = RecordingFeedback()
        restarted.toggle(FrontPageSections.PINNED, null, secondFeedback)
        advanceUntilIdle()
        assertFalse(restarted.isCollapsed(FrontPageSections.PINNED, null))
        assertEquals(listOf(Haptic.Select), secondFeedback.haptics)
    }

    // ------------------------------------------------- user-section folds

    @Test
    fun aConnectedUserSectionIsWrittenToTheEngineOnce() = runTest {
        val core = FakeWorkspaceCore()
        val fold = newFold(core)
        val feedback = RecordingFeedback()
        advanceUntilIdle()
        val open = section("e1:s1", name = "P0", engineId = "A", collapsed = false)

        fold.toggle("e1:s1", open, feedback)
        // The effective state flips immediately (pending), one haptic.
        assertTrue(fold.isCollapsed("e1:s1", open))
        assertEquals(listOf(Haptic.Select), feedback.haptics)
        advanceUntilIdle()
        assertEquals(listOf("e1:s1" to true), core.setCalls.toList())
        assertNotNull(fold.state.value.pending["e1:s1"])
        assertEquals(0uL, fold.state.value.pending["e1:s1"]!!.sinceRevision)

        // A snapshot that shows the new value clears the pending entry.
        core.ws = snapshot(1uL, front(sections = listOf(section("e1:s1", collapsed = true))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        assertNull(fold.state.value.pending["e1:s1"])
        assertTrue(fold.isCollapsed("e1:s1", section("e1:s1", collapsed = true)))
    }

    @Test
    fun pendingClearsWhenARevisionSupersedesTheWrite() = runTest {
        val core = FakeWorkspaceCore()
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", collapsed = false), NoFeedback)
        advanceUntilIdle()
        assertNotNull(fold.state.value.pending["e1:s1"])

        // The RPC failed and the overlay reverted: the revision moved past
        // sinceRevision, so the pending entry is dropped.
        core.ws = snapshot(2uL, front(sections = listOf(section("e1:s1", collapsed = false))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        assertNull(fold.state.value.pending["e1:s1"])
    }

    @Test
    fun pendingClearsWhenTheSectionDisappears() = runTest {
        val core = FakeWorkspaceCore()
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", collapsed = false), NoFeedback)
        advanceUntilIdle()

        fold.reconcile(snapshot(1uL, front()), listOf(engine("A")))
        advanceUntilIdle()
        assertNull(fold.state.value.pending["e1:s1"])
    }

    @Test
    fun twoQuickTapsSendTrueThenFalseInOrder() = runTest {
        val core = FakeWorkspaceCore()
        val fold = newFold(core)
        advanceUntilIdle()
        val open = section("e1:s1", collapsed = false)

        fold.toggle("e1:s1", open, NoFeedback)
        fold.toggle("e1:s1", open, NoFeedback)
        advanceUntilIdle()

        assertEquals(listOf("e1:s1" to true, "e1:s1" to false), core.setCalls.toList())
        // The last tap owns the pending entry; the first success must not
        // reclaim it.
        assertEquals(false, fold.state.value.pending["e1:s1"]!!.desired)
    }

    @Test
    fun notFoundRemovesPendingOnly() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.NotFound("section gone"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", collapsed = false), NoFeedback)
        advanceUntilIdle()

        assertEquals(1, core.setCalls.size)
        assertNull(fold.state.value.pending["e1:s1"])
        assertTrue(fold.state.value.offline.isEmpty())
        assertTrue(fold.state.value.local.isEmpty())
    }

    @Test
    fun otherCoreErrorsRemovePendingAndLog() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.Network("engine error"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", collapsed = false), NoFeedback)
        advanceUntilIdle()

        assertNull(fold.state.value.pending["e1:s1"])
        assertEquals(1, logged.size)
    }

    // ------------------------------------------------- offline overrides

    @Test
    fun aHostUnavailableWriteBecomesAnOfflineOverride() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        val open = section("e1:s1", name = "P0", engineId = "A", collapsed = false)

        fold.toggle("e1:s1", open, NoFeedback)
        // The phone keeps the tapped value (the override), synchronously.
        assertTrue(fold.isCollapsed("e1:s1", open))
        advanceUntilIdle()
        assertEquals(listOf("e1:s1" to true), core.setCalls.toList())

        // Stored as "<1|0>|<engineId>|<sectionId>" and it survives a restart.
        assertEquals(setOf("1|A|e1:s1"), stored(stringSetPreferencesKey("sectionFoldOverrides")))
        val restarted = restart(FakeWorkspaceCore())
        advanceUntilIdle()
        assertEquals(
            SectionFoldOverride(sectionId = "e1:s1", engineId = "A", collapsed = true),
            restarted.state.value.offline["e1:s1"],
        )
        assertTrue(restarted.isCollapsed("e1:s1", open))

        // Tapping back to the engine's own value removes the override.
        restarted.toggle("e1:s1", open, NoFeedback)
        advanceUntilIdle()
        assertTrue(restarted.state.value.offline.isEmpty())
        assertEquals(setOf<String>(), stored(stringSetPreferencesKey("sectionFoldOverrides")))
    }

    @Test
    fun anUnsupportedWriteBehavesLikeHostUnavailable() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.Unsupported("pins are not synced yet"))
        val fold = newFold(core)
        advanceUntilIdle()
        val folded = section("e1:s1", engineId = "A", collapsed = true)

        // Unfolding an engine-folded section while the engine refuses:
        // the override keeps the phone's value.
        fold.toggle("e1:s1", folded, NoFeedback)
        assertFalse(fold.isCollapsed("e1:s1", folded))
        advanceUntilIdle()
        assertEquals(setOf("0|A|e1:s1"), stored(stringSetPreferencesKey("sectionFoldOverrides")))
    }

    // ------------------------------------------------------- the reconciler

    @Test
    fun reconcilePushesOverridesOnceTheEngineIsConnected() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", engineId = "A", collapsed = false), NoFeedback)
        advanceUntilIdle()
        assertEquals(1, core.setCalls.size)

        // The engine comes back: the override is pushed once and removed.
        core.setErrors.clear()
        core.ws = snapshot(1uL, front(sections = listOf(section("e1:s1", collapsed = false))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        assertEquals(listOf("e1:s1" to true, "e1:s1" to true), core.setCalls.toList())
        assertTrue(fold.state.value.offline.isEmpty())
        assertEquals(setOf<String>(), stored(stringSetPreferencesKey("sectionFoldOverrides")))
        assertEquals(true, fold.state.value.pending["e1:s1"]!!.desired)
    }

    @Test
    fun reconcileDropsAnOverrideTheEngineAlreadyAgreesWith() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", engineId = "A", collapsed = false), NoFeedback)
        advanceUntilIdle()
        assertEquals(1, core.setCalls.size)

        core.setErrors.clear()
        core.ws = snapshot(1uL, front(sections = listOf(section("e1:s1", collapsed = true))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        // No second call; the override is gone.
        assertEquals(1, core.setCalls.size)
        assertTrue(fold.state.value.offline.isEmpty())
    }

    @Test
    fun reconcileDropsOverridesOfForgottenEngines() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", engineId = "A", collapsed = false), NoFeedback)
        advanceUntilIdle()

        // Engine A is no longer paired; engine B cannot take its override.
        fold.reconcile(core.ws, listOf(engine("B")))
        advanceUntilIdle()
        assertTrue(fold.state.value.offline.isEmpty())
        assertEquals(1, core.setCalls.size)
    }

    @Test
    fun reconcileKeepsAnOverrideWhileTheEngineStaysUnreachable() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", engineId = "A", collapsed = false), NoFeedback)
        advanceUntilIdle()
        assertEquals(1, core.setCalls.size)

        // Not Connected (still connecting): keep the override, no call.
        fold.reconcile(core.ws, listOf(engine("A", state = EngineState.Connecting)))
        advanceUntilIdle()
        assertEquals(1, core.setCalls.size)
        assertNotNull(fold.state.value.offline["e1:s1"])

        // Connected, but the write is refused again: keep it for the next
        // trigger. (`setErrors` is consumed one per call — the tap's call
        // used the first refusal, so script the second one here.)
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        core.ws = snapshot(1uL, front(sections = listOf(section("e1:s1", collapsed = false))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        assertEquals(2, core.setCalls.size)
        assertNotNull(fold.state.value.offline["e1:s1"])
    }

    @Test
    fun reconcileDropsAnOverrideTheEngineReportedMissing() = runTest {
        val core = FakeWorkspaceCore()
        core.setErrors.add(CoreException.HostUnavailable("engine offline"))
        val fold = newFold(core)
        advanceUntilIdle()
        fold.toggle("e1:s1", section("e1:s1", engineId = "A", collapsed = false), NoFeedback)
        advanceUntilIdle()

        core.setErrors.clear()
        core.setErrors.add(CoreException.NotFound("section gone"))
        core.ws = snapshot(1uL, front(sections = listOf(section("e1:s1", collapsed = false))))
        fold.reconcile(core.ws, listOf(engine("A")))
        advanceUntilIdle()
        assertTrue(fold.state.value.offline.isEmpty())
    }

    // -------------------------------------------------------- the encoding

    @Test
    fun overrideEncodingRoundTrips() {
        val override = SectionFoldOverride(sectionId = "e1:s1", engineId = "A", collapsed = true)
        assertEquals("1|A|e1:s1", override.encode())
        assertEquals(override, SectionFoldOverride.decode(override.encode()))
        assertEquals(
            SectionFoldOverride(sectionId = "e2:s9", engineId = "B", collapsed = false),
            SectionFoldOverride.decode("0|B|e2:s9"),
        )
        // A section id with its own '|' separators survives the limit-3 split.
        assertEquals(
            SectionFoldOverride(sectionId = "e1|weird", engineId = "B", collapsed = true),
            SectionFoldOverride.decode("1|B|e1|weird"),
        )
        assertNull(SectionFoldOverride.decode("A|s"))
        assertNull(SectionFoldOverride.decode("2|A|s"))
        assertNull(SectionFoldOverride.decode("1|A|"))
        assertNull(SectionFoldOverride.decode("|A|s"))
        assertNull(SectionFoldOverride.decode(""))
    }
}
