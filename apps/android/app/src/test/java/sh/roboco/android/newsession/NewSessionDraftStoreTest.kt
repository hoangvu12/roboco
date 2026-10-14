package sh.roboco.android.newsession

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.composer.DraftKey
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.foregroundAppScope
import sh.roboco.android.core.testPrefsStore

/**
 * [NewSessionDraftStore] (android-ui-parity #92) — the store's whole
 * contract, the ticket's Storage/Forget/Override tables:
 *
 * - the restart round trip over a real file (the ticket 71/84 pattern:
 *   the old DataStore's scope is cancelled and joined first, one active
 *   instance per file) and the `newSessionDraft` key on disk;
 * - the write mechanics: the mirror is synchronous, the disk write joins
 *   on the test scheduler, and each job writes the mirror's current JSON
 *   (order-insensitive, last state wins);
 * - the one-time launch harness override (applied only on the first read
 *   **after** the load, persisted like any assignment, never re-applied);
 * - an assignment before the load wins over the disk value;
 * - the text through ticket 84's `Drafts` (Swift-blank removes);
 * - the partial engine forget (ids stripped from the stored draft and
 *   from every later assignment, text and images kept) and the full
 *   reset (default draft, empty text/images, `resetEpoch` bump,
 *   `ModelCatalogCache` cleared).
 *
 * Robolectric (`org.json` for the codec and the disk assertions,
 * `@Config(sdk = [36])`); `StandardTestDispatcher(testScheduler)` serves
 * as `io`, [foregroundAppScope] as the app-scope stand-in (never
 * `backgroundScope`: `advanceUntilIdle()` does not run background-only
 * work) — the same shape as ticket 84's `DraftsTest`. A second store over a second **file**
 * backs the `Drafts` instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewSessionDraftStoreTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class Fixture(
        val store: NewSessionDraftStore,
        val dataStore: DataStore<Preferences>,
        val dataStoreScope: CoroutineScope,
        val drafts: Drafts,
    )

    @Before
    fun clearCatalogCache() {
        ModelCatalogCache.clear()
    }

    /** One store over a real file; the DataStore scope is cancellable for restarts. */
    private fun TestScope.newStore(launchHarness: () -> String? = { null }): Fixture {
        val drafts = Drafts(
            testPrefsStore(scope(), File(tmp.root, "drafts.preferences_pb")),
            foregroundAppScope(),
            StandardTestDispatcher(testScheduler),
        )
        val dataStoreScope = scope()
        val dataStore = testPrefsStore(dataStoreScope, File(tmp.root, "new_session.preferences_pb"))
        return Fixture(
            NewSessionDraftStore(
                dataStore,
                drafts,
                foregroundAppScope(),
                StandardTestDispatcher(testScheduler),
                launchHarness,
            ),
            dataStore,
            dataStoreScope,
            drafts,
        )
    }

    /** A cancellable scope on the test scheduler. */
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    /** A process restart: a new DataStore over the same file, in a new scope, sharing the Drafts. */
    private suspend fun TestScope.restart(f: Fixture, launchHarness: () -> String? = { null }): Fixture {
        f.dataStoreScope.cancel()
        f.dataStoreScope.coroutineContext.job.join()
        val dataStoreScope = scope()
        val dataStore = testPrefsStore(dataStoreScope, File(tmp.root, "new_session.preferences_pb"))
        return Fixture(
            NewSessionDraftStore(
                dataStore,
                f.drafts,
                foregroundAppScope(),
                StandardTestDispatcher(testScheduler),
                launchHarness,
            ),
            dataStore,
            dataStoreScope,
            f.drafts,
        )
    }

    /** The JSON currently on disk for the draft key. */
    private suspend fun diskJson(f: Fixture): String? = f.dataStore.data.first()[NewSessionDraftStore.KEY]

    private fun image(id: String): StagedImage = StagedImage(id, "$id.jpg", byteArrayOf(1), ImageBitmap(1, 1))

    // ── persistence and restart ─────────────────────────────────────────

    @Test
    fun assignmentSurvivesRestartAndFileHoldsTheKey() {
        runTest {
            val f = newStore()
            val d = NewSessionDraft(
                projectId = "engine:v1:abc",
                hostId = null,
                branch = "main",
                worktree = true,
                harness = "codex",
                model = "gpt-5.5",
                effort = "low",
            )
            f.store.lastDraft = d
            advanceUntilIdle()
            val f2 = restart(f)
            f2.store.warm()
            advanceUntilIdle()
            assertEquals(d, f2.store.lastDraft)
            val names = f2.dataStore.data.first().asMap().keys.map { it.name }
            assertTrue(names.contains("newSessionDraft"))
        }
    }

    @Test
    fun mirrorIsSynchronousAndDiskWriteJoins() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(harness = "codex")
            // Synchronous mirror: a read right after the assignment.
            assertEquals("codex", f.store.lastDraft.harness)
            advanceUntilIdle() // the write job joins
            assertEquals("codex", JSONObject(diskJson(f)!!).getString("harness"))
        }
    }

    @Test
    fun writesAreOrderInsensitiveLastStateWins() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "first")
            f.store.lastDraft = NewSessionDraft(projectId = "second")
            advanceUntilIdle()
            assertEquals("second", JSONObject(diskJson(f)!!).getString("projectId"))
            assertEquals(NewSessionDraft(projectId = "second"), f.store.lastDraft)
        }
    }

    // ── the launch override ─────────────────────────────────────────────

    @Test
    fun launchOverrideAppliesOnceAfterTheLoadAndPersists() {
        runTest {
            val f = newStore(launchHarness = { "mock" })
            f.store.warm()
            advanceUntilIdle()
            // The first read after the load returns the override…
            assertEquals("mock", f.store.lastDraft.harness)
            // …and persists it like any assignment.
            advanceUntilIdle()
            assertEquals("mock", JSONObject(diskJson(f)!!).getString("harness"))
            // A later assignment wins; the override is not re-applied.
            f.store.lastDraft = f.store.lastDraft.copy(harness = "codex")
            assertEquals("codex", f.store.lastDraft.harness)
            advanceUntilIdle()
            assertEquals("codex", f.store.lastDraft.harness)
            assertEquals("codex", JSONObject(diskJson(f)!!).getString("harness"))
        }
    }

    @Test
    fun preLoadReadReturnsTheDefaultMirrorWithoutTheOverride() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val data = FakePrefsStore(mapOf("newSessionDraft" to """{"projectId":"B"}"""), gate)
            val drafts = Drafts(FakePrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
            val store = NewSessionDraftStore(
                data,
                drafts,
                foregroundAppScope(),
                StandardTestDispatcher(testScheduler),
                launchHarness = { "mock" },
            )
            store.warm() // launched, gated: not loaded yet
            assertEquals(NewSessionDraft(), store.lastDraft)
            assertFalse(store.isLoaded)
            gate.complete(Unit)
            advanceUntilIdle()
            val first = store.lastDraft
            assertEquals("B", first.projectId) // the disk value
            assertEquals("mock", first.harness) // the override, now applied
        }
    }

    @Test
    fun assignmentBeforeTheLoadWinsOverTheDiskValue() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val data = FakePrefsStore(mapOf("newSessionDraft" to """{"projectId":"disk"}"""), gate)
            val drafts = Drafts(FakePrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
            val store = NewSessionDraftStore(data, drafts, foregroundAppScope(), StandardTestDispatcher(testScheduler))
            store.warm()
            store.lastDraft = NewSessionDraft(projectId = "user")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("user", store.lastDraft.projectId)
            advanceUntilIdle()
            assertEquals("user", JSONObject(data.data.first()[NewSessionDraftStore.KEY]!!).getString("projectId"))
        }
    }

    @Test
    fun unreadableStoredValueResetsTheDraft() {
        runTest {
            val data = FakePrefsStore(mapOf("newSessionDraft" to "garbage"))
            val drafts = Drafts(FakePrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
            val store = NewSessionDraftStore(data, drafts, foregroundAppScope(), StandardTestDispatcher(testScheduler))
            store.warm()
            advanceUntilIdle()
            assertEquals(NewSessionDraft(), store.lastDraft)
        }
    }

    // ── the text (ticket 84's Drafts) ───────────────────────────────────

    @Test
    fun textGoesToTheDraftsNewSessionKeyWithTheBlankRule() {
        runTest {
            val f = newStore()
            f.store.text = "Half-written idea"
            assertEquals("Half-written idea", f.drafts.peek(DraftKey.NewSession))
            advanceUntilIdle()
            assertEquals("Half-written idea", f.store.text)
            f.store.text = "  \n"
            assertEquals("", f.drafts.peek(DraftKey.NewSession)) // blank removes the entry
        }
    }

    // ── engine forget ───────────────────────────────────────────────────

    @Test
    fun partialForgetStripsTheStoredIdsAndKeepsTextAndImages() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "A", branch = "main", hostId = "H1")
            val images = listOf(image("i1"))
            f.store.images = images
            f.store.text = "keep me"
            advanceUntilIdle()
            ModelCatalogCache["H1"] = listOf(ModelChoice("claude-code", "Claude Code", "m", "M", listOf("low")))
            f.store.forgetEngine(setOf("A"), setOf("H1"), enginesLeft = 1)
            advanceUntilIdle()
            val stored = f.store.lastDraft
            assertNull(stored.projectId)
            assertNull(stored.branch)
            assertNull(stored.hostId)
            assertEquals("keep me", f.store.text)
            assertEquals(images, f.store.images)
            assertNull(ModelCatalogCache["H1"]) // the forgotten device's rows are dropped
            // The stored JSON is stripped too.
            val o = JSONObject(diskJson(f)!!)
            assertFalse(o.has("projectId"))
            assertFalse(o.has("branch"))
            assertFalse(o.has("hostId"))
            // A later assignment can never write a forgotten id back.
            f.store.lastDraft = NewSessionDraft(projectId = "A", hostId = "H1", model = "m")
            assertNull(f.store.lastDraft.projectId)
            assertNull(f.store.lastDraft.hostId)
            assertEquals("m", f.store.lastDraft.model)
        }
    }

    @Test
    fun fullResetDropsEverythingAndBumpsTheEpoch() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "A", hostId = "H1", model = "m", effort = "low")
            f.store.text = "draft text"
            f.store.images = listOf(image("i1"))
            advanceUntilIdle()
            ModelCatalogCache["H1"] = listOf(ModelChoice("claude-code", "Claude Code", "m", "M", listOf("low")))
            val epoch = f.store.resetEpoch
            f.store.forgetEngine(setOf("A"), setOf("H1"), enginesLeft = 0)
            assertEquals(epoch + 1, f.store.resetEpoch)
            assertEquals(NewSessionDraft(), f.store.lastDraft)
            assertEquals("", f.store.text)
            assertEquals(emptyList<StagedImage>(), f.store.images)
            assertNull(ModelCatalogCache["H1"])
            advanceUntilIdle()
            assertEquals(NewSessionDraft(), NewSessionDraftJson.decode(diskJson(f)))
        }
    }

    @Test
    fun fullResetReappliesTheLaunchHarness() {
        runTest {
            val f = newStore(launchHarness = { "mock" })
            f.store.warm()
            advanceUntilIdle()
            f.store.forgetEngine(emptySet(), emptySet(), enginesLeft = 0)
            assertEquals("mock", f.store.lastDraft.harness)
        }
    }

    // ── the engine-id convenience overload ──────────────────────────────

    @Test
    fun forgetEngineByEngineIdReadsThePreForgetSnapshot() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "A", hostId = "H1")
            advanceUntilIdle()
            val before = recordSnapshot(
                projects = listOf(recordProject("A", engineId = "E1", deviceId = "H1")),
                devices = listOf(recordDevice("H1", engineId = "E1", executionHost = true)),
            )
            f.store.forgetEngine("E1", before, enginesLeft = 1)
            advanceUntilIdle()
            val stored = f.store.lastDraft
            assertNull(stored.projectId)
            assertNull(stored.hostId)
        }
    }
}
