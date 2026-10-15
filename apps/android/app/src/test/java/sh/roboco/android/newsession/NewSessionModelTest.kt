package sh.roboco.android.newsession

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.foregroundAppScope

/**
 * [NewSessionModel] + [normalize] (android-ui-parity #92) — the page
 * state rules, on the plain JVM with fakes (the ticket's Verification
 * section): a fake [NewSessionDraftCodec] (org.json is a stub off
 * Robolectric), a fake [ModelCatalogSource] whose `fetch` suspends on a
 * gate the test completes by hand, and [FakePrefsStore] as the DataStore.
 *
 * Covered: the defaults and the stale-id rule (also directly on
 * [normalize]), the "No Project…" case, `checkouts` sorting, the
 * `selectedModel` lookup, `initialText`'s prompt rule, the persist-per-
 * change contract, the late store load (defaults first, then the stored
 * draft; a user pick freezes it), the model-loading table (built-in at
 * once, fetch apply/cache rules, device switch, empty results, empty
 * device, disposal), and the engine-forget rules for an open page.
 *
 * `runTest` with `StandardTestDispatcher(testScheduler)` as `io` and
 * `main`; [foregroundAppScope] as the app-scope stand-in; the page scope is
 * a child of it, so a collector left pending at the test's end cannot
 * fail it. [ModelCatalogCache] is cleared before every test
 * (process-static).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionModelTest {

    @Before
    fun clearCatalogCache() {
        ModelCatalogCache.clear()
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /** Tokens ↔ drafts, so the "disk" can hold a fake-encoded draft without org.json. */
    private class FakeCodec : NewSessionDraftCodec {
        private val byToken = HashMap<String, NewSessionDraft>()
        private var next = 0

        fun tokenOf(d: NewSessionDraft): String {
            val token = "d${next++}"
            byToken[token] = d
            return token
        }

        override fun encode(d: NewSessionDraft): String = tokenOf(d)

        override fun decode(json: String?): NewSessionDraft = json?.let { byToken[it] } ?: NewSessionDraft()
    }

    /** `fetch` suspends until the test completes its device's gate. */
    private class FakeCatalog(private val builtInModels: List<ModelChoice>) : ModelCatalogSource {
        private val gates = HashMap<String, CompletableDeferred<List<ModelChoice>>>()
        val fetches = mutableListOf<String>()

        override fun builtIn(): List<ModelChoice> = builtInModels

        override suspend fun fetch(deviceId: String): List<ModelChoice> {
            fetches += deviceId
            val gate = synchronized(gates) { gates.getOrPut(deviceId) { CompletableDeferred() } }
            return gate.await()
        }

        fun complete(deviceId: String, models: List<ModelChoice>) {
            // getOrPut so a completion can also arrive before the fetch started.
            synchronized(gates) { gates.getOrPut(deviceId) { CompletableDeferred() } }.complete(models)
        }
    }

    /** A plain-JVM [ImageBitmap]: no `android.graphics.Bitmap` on the classpath. */
    private class TestBitmap : ImageBitmap {
        override val width: Int get() = 1
        override val height: Int get() = 1
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override val hasAlpha: Boolean get() = true
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            offset: Int,
            stride: Int,
            width: Int,
            height: Int,
        ) = Unit

        override fun prepareToDraw() = Unit
    }

    private fun image(id: String): StagedImage = StagedImage(id, "$id.png", byteArrayOf(1), TestBitmap())

    private class StoreFixture(
        val store: NewSessionDraftStore,
        val data: FakePrefsStore,
        val drafts: Drafts,
        val codec: FakeCodec,
    )

    private class Page(
        val model: NewSessionModel,
        val store: NewSessionDraftStore,
        val data: FakePrefsStore,
        val drafts: Drafts,
        val codec: FakeCodec,
        val catalog: FakeCatalog,
        val options: MutableStateFlow<NewSessionOptions?>,
    )

    private val builtIn = listOf(
        ModelChoice("claude-code", "Claude Code", "c1", "Claude 1", listOf("low", "medium", "high")),
        ModelChoice("codex", "Codex", "x1", "Codex 1", listOf("low", "medium")),
        ModelChoice("codex", "Codex", "x2", "Codex 2", listOf("low")),
    )

    private fun project(
        id: String,
        device: String,
        deviceName: String = device,
        groupKey: String = "repo:$id",
        path: String = "/$id",
    ) = ProjectOption(
        id = id,
        name = id,
        path = path,
        device = device,
        deviceName = deviceName,
        online = true,
        git = true,
        groupKey = groupKey,
        groupName = id,
        colorIndex = 0,
    )

    private fun host(id: String, online: Boolean) = HostOption(id, id, online)

    private fun options(
        projects: List<ProjectOption> = emptyList(),
        hosts: List<HostOption> = emptyList(),
    ) = NewSessionOptions(projects, hosts)

    /** The two-project fixture of the ticket's acceptance rows. */
    private val abOptions = options(
        projects = listOf(project("A", "dev1"), project("B", "dev2")),
        hosts = listOf(host("H1", online = false), host("H2", online = true)),
    )

    /** One store over an in-memory "disk" (token-encoded by [FakeCodec]). */
    private fun TestScope.newStore(
        disk: String? = null,
        gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) },
        codec: FakeCodec = FakeCodec(),
    ): StoreFixture {
        val data = FakePrefsStore(disk?.let { mapOf("newSessionDraft" to it) } ?: emptyMap(), gate)
        val drafts = Drafts(FakePrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
        val store = NewSessionDraftStore(
            data,
            drafts,
            foregroundAppScope(),
            StandardTestDispatcher(testScheduler),
            launchHarness = { null },
            codec = codec,
        )
        return StoreFixture(store, data, drafts, codec)
    }

    /** A page over [f]; `warm` loads the store first (the normal open-after-start case). */
    private fun TestScope.newPage(
        f: StoreFixture,
        options: MutableStateFlow<NewSessionOptions?>,
        prompt: String? = null,
        warm: Boolean = true,
    ): Page {
        if (warm) {
            f.store.warm()
            advanceUntilIdle()
        }
        val catalog = FakeCatalog(builtIn)
        // A foreground app scope, not `backgroundScope`: advanceUntilIdle()
        // never runs background-only work (see foregroundAppScope).
        val appScope = foregroundAppScope()
        val pageScope = CoroutineScope(
            appScope.coroutineContext + SupervisorJob(appScope.coroutineContext.job),
        )
        val model = NewSessionModel(
            prompt = prompt,
            store = f.store,
            options = options,
            catalog = catalog,
            pageScope = pageScope,
            appScope = appScope,
            io = StandardTestDispatcher(testScheduler),
            main = StandardTestDispatcher(testScheduler),
        )
        return Page(model, f.store, f.data, f.drafts, f.codec, catalog, options)
    }

    // ── normalize, directly ─────────────────────────────────────────────

    @Test
    fun normalizePicksTheFirstProjectThenTheFirstOnlineHost() {
        assertEquals(
            NewSessionDraft(projectId = "A"),
            normalize(NewSessionDraft(), abOptions),
        )
        assertEquals(
            NewSessionDraft(hostId = "H2"),
            normalize(NewSessionDraft(), options(hosts = abOptions.hosts)),
        )
        assertEquals(
            NewSessionDraft(hostId = "H1"),
            normalize(NewSessionDraft(), options(hosts = listOf(host("H1", online = false), host("H2", online = false)))),
        )
        // With nothing at all: both null (deviceId is then "").
        assertEquals(NewSessionDraft(), normalize(NewSessionDraft(), options()))
    }

    @Test
    fun normalizeKeepsExplicitPicksAndOnlyNullsStaleIds() {
        // "No Project…" picked: untouched.
        assertEquals(
            NewSessionDraft(hostId = "H1"),
            normalize(NewSessionDraft(hostId = "H1"), abOptions),
        )
        // A valid project pick: untouched, branch kept.
        assertEquals(
            NewSessionDraft(projectId = "B", branch = "b"),
            normalize(NewSessionDraft(projectId = "B", branch = "b"), abOptions),
        )
        // Stale project: dropped (with its branch), then the default project.
        assertEquals(
            NewSessionDraft(projectId = "A"),
            normalize(NewSessionDraft(projectId = "gone", branch = "main"), abOptions),
        )
        // Stale host with projects: hostId nulled, then the first project (step 3).
        assertEquals(
            NewSessionDraft(projectId = "A"),
            normalize(NewSessionDraft(hostId = "gone"), abOptions),
        )
        // Stale host without projects: the first online host.
        assertEquals(
            NewSessionDraft(hostId = "H2"),
            normalize(NewSessionDraft(hostId = "gone"), options(hosts = abOptions.hosts)),
        )
    }

    // ── the model's defaults and stale rule ─────────────────────────────

    @Test
    fun openAppliesTheDefaultsInPlaceWithoutACoroutine() {
        runTest {
            val page = newPage(newStore(), MutableStateFlow(abOptions))
            assertEquals("A", page.model.draft.projectId)
            assertEquals("dev1", page.model.deviceId)
            assertEquals(project("A", "dev1"), page.model.project)
        }
    }

    @Test
    fun openWithNoProjectsRunsOnTheFirstReachableHost() {
        runTest {
            val page = newPage(newStore(), MutableStateFlow(options(hosts = abOptions.hosts)))
            assertEquals("H2", page.model.draft.hostId)
            assertEquals("H2", page.model.deviceId)
        }
    }

    @Test
    fun openWithNothingPickedLeavesBothNullAndDeviceIdEmpty() {
        runTest {
            val page = newPage(newStore(), MutableStateFlow(options()))
            assertNull(page.model.draft.projectId)
            assertNull(page.model.draft.hostId)
            assertEquals("", page.model.deviceId)
        }
    }

    @Test
    fun openKeepsAnExplicitNoProjectPick() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(hostId = "H1")
            val page = newPage(f, MutableStateFlow(abOptions))
            assertEquals("H1", page.model.draft.hostId)
            assertNull(page.model.draft.projectId)
            assertEquals("H1", page.model.deviceId)
        }
    }

    @Test
    fun openNullsStaleIdsAndReappliesTheDefaults() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "gone", branch = "main")
            val page = newPage(f, MutableStateFlow(abOptions))
            assertEquals("A", page.model.draft.projectId)
            assertNull(page.model.draft.branch)
        }
    }

    @Test
    fun nullOptionsKeepTheDraftUntilTheFirstOptionsArrive() {
        runTest {
            val codec = FakeCodec()
            val stored = NewSessionDraft(projectId = "gone", effort = "low")
            val f = newStore(disk = codec.tokenOf(stored), codec = codec)
            val options = MutableStateFlow<NewSessionOptions?>(null)
            val page = newPage(f, options)
            // Options null: no stale rule, no defaults, deviceId = hostId ?: "".
            assertEquals("gone", page.model.draft.projectId)
            assertEquals("low", page.model.draft.effort)
            assertNull(page.model.project)
            assertEquals("", page.model.deviceId)
            options.value = abOptions
            advanceUntilIdle()
            assertEquals("A", page.model.draft.projectId) // stale gone → default A
            assertNull(page.model.draft.branch)
        }
    }

    // ── derived values ──────────────────────────────────────────────────

    @Test
    fun checkoutsSortByLowercasedDeviceNameThenPath() {
        runTest {
            val o = options(
                projects = listOf(
                    project("a", device = "d1", deviceName = "mac studio", groupKey = "repo:x", path = "/b"),
                    project("b", device = "d2", deviceName = "MacBook", groupKey = "repo:x", path = "/a"),
                    project("c", device = "d2", deviceName = "MacBook", groupKey = "repo:x", path = "/0"),
                    project("d", device = "d3", deviceName = "MacBook", groupKey = "repo:y", path = "/a"),
                ),
            )
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "a")
            val page = newPage(f, MutableStateFlow(o))
            assertEquals(listOf("a", "c", "b"), page.model.checkouts.map { it.id })
        }
    }

    @Test
    fun checkoutsEmptyWithoutAProject() {
        runTest {
            val page = newPage(newStore(), MutableStateFlow(options(hosts = abOptions.hosts)))
            assertTrue(page.model.checkouts.isEmpty())
        }
    }

    @Test
    fun selectedModelFallsBackToTheFirstModelOfTheHarness() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(harness = "codex", model = "nope")
            val page = newPage(f, MutableStateFlow(options(hosts = abOptions.hosts)))
            assertEquals(builtIn[1], page.model.selectedModel) // the first codex model
            assertEquals(builtIn, page.model.models) // empty deviceId → built-in, no fetch

            f.store.lastDraft = NewSessionDraft(harness = "codex", model = "x2")
            val page2 = newPage(f, MutableStateFlow(options(hosts = abOptions.hosts)))
            assertEquals(builtIn[2], page2.model.selectedModel)

            f.store.lastDraft = NewSessionDraft(harness = "x")
            val page3 = newPage(f, MutableStateFlow(options(hosts = abOptions.hosts)))
            assertNull(page3.model.selectedModel)
        }
    }

    // ── text and the prompt rule ────────────────────────────────────────

    @Test
    fun emptyPromptWinsOverTheSavedTextNullFallsBack() {
        runTest {
            val f = newStore()
            f.store.text = "saved"
            assertEquals("", newPage(f, MutableStateFlow(abOptions), prompt = "").model.initialText)
            assertEquals("saved", newPage(f, MutableStateFlow(abOptions), prompt = null).model.initialText)
            assertEquals(listOf<StagedImage>(), newPage(f, MutableStateFlow(abOptions)).model.initialImages)
        }
    }

    @Test
    fun initialImagesComeFromTheStore() {
        runTest {
            val f = newStore()
            val images = listOf(image("i1"), image("i2"))
            f.store.images = images
            assertEquals(images, newPage(f, MutableStateFlow(abOptions)).model.initialImages)
        }
    }

    // ── update and the save moments ─────────────────────────────────────

    @Test
    fun everyUpdatePersistsAtOnceAndOnDisk() {
        runTest {
            val f = newStore()
            val page = newPage(f, MutableStateFlow(abOptions))
            page.model.update { it.copy(effort = "low", model = "c1") }
            // The store's mirror equals the page draft right after the update…
            assertEquals(page.model.draft, f.store.lastDraft)
            // …and the disk after the write job joins.
            advanceUntilIdle()
            assertEquals(page.model.draft, f.codec.decode(f.data.data.first()[NewSessionDraftStore.KEY]))
        }
    }

    @Test
    fun rememberDraftWritesOptionsTextAndImages() {
        runTest {
            val f = newStore()
            val page = newPage(f, MutableStateFlow(abOptions))
            page.model.update { it.copy(effort = "low") }
            val images = listOf(image("i1"))
            page.model.rememberDraft("Half-written idea", images)
            assertEquals(page.model.draft, f.store.lastDraft)
            assertEquals("Half-written idea", f.store.text)
            assertEquals(images, f.store.images)
        }
    }

    @Test
    fun rememberDraftAfterMarkCreatedWritesNothing() {
        runTest {
            val f = newStore()
            val page = newPage(f, MutableStateFlow(abOptions))
            page.model.update { it.copy(effort = "low") }
            page.model.rememberDraft("Half-written idea", listOf(image("i1")))
            val before = f.store.lastDraft
            page.model.markCreated()
            // markCreated clears the text and the images; the options stay.
            assertEquals("", f.store.text)
            assertEquals(emptyList<StagedImage>(), f.store.images)
            assertEquals(before, f.store.lastDraft)
            // A later rememberDraft (the sheet's disposal, ON_STOP) writes nothing.
            page.model.rememberDraft("typed more", listOf(image("i2")))
            assertEquals("", f.store.text)
            assertEquals(emptyList<StagedImage>(), f.store.images)
            assertEquals(before, f.store.lastDraft)
        }
    }

    @Test
    fun markCreatedLeavesTheStoreDraftEqualToThePageDraft() {
        runTest {
            val f = newStore()
            val page = newPage(f, MutableStateFlow(abOptions))
            page.model.update { it.copy(effort = "low") }
            page.model.markCreated()
            assertEquals(page.model.draft, f.store.lastDraft)
        }
    }

    @Test
    fun persistBeforeCreateSavesTheDraftUnconditionally() {
        runTest {
            val f = newStore()
            val page = newPage(f, MutableStateFlow(abOptions))
            page.model.update { it.copy(effort = "low") }
            page.model.persistBeforeCreate()
            assertEquals(page.model.draft, f.store.lastDraft)
        }
    }

    // ── the late store load ─────────────────────────────────────────────

    @Test
    fun lateLoadReplacesTheLaunchDefaultsWithTheStoredDraft() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val codec = FakeCodec()
            val stored = NewSessionDraft(projectId = "B", effort = "low")
            val f = newStore(disk = codec.tokenOf(stored), gate = gate, codec = codec)
            val page = newPage(f, MutableStateFlow(abOptions), warm = false)
            // The page opens before the load: the launch defaults show first.
            assertEquals("A", page.model.draft.projectId)
            assertNull(page.model.draft.effort)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("B", page.model.draft.projectId)
            assertEquals("low", page.model.draft.effort)
            // The launch defaults never overwrote the disk value.
            assertEquals(stored, f.codec.decode(f.data.data.first()[NewSessionDraftStore.KEY]))
        }
    }

    @Test
    fun lateLoadKeepsTheUsersPickWhenTheyTouchedThePage() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val codec = FakeCodec()
            val stored = NewSessionDraft(projectId = "B", effort = "low")
            val f = newStore(disk = codec.tokenOf(stored), gate = gate, codec = codec)
            val page = newPage(f, MutableStateFlow(abOptions), warm = false)
            assertEquals("A", page.model.draft.projectId)
            page.model.update { it.copy(hostId = "H1") } // the user picks before the load
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("H1", page.model.draft.hostId) // the pick is kept
            assertEquals("A", page.model.draft.projectId) // not the disk's "B"
            assertNull(page.model.draft.effort)
            // The disk value is still the stored one.
            assertEquals(stored, f.codec.decode(f.data.data.first()[NewSessionDraftStore.KEY]))
        }
    }

    // ── model loading ───────────────────────────────────────────────────

    @Test
    fun openShowsTheBuiltInCatalogBeforeTheFetchReturns() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", online = true))))
            val page = newPage(newStore(), options)
            assertEquals(builtIn, page.model.models) // the same call, before the fetch runs
            advanceUntilIdle() // the fetch is out, still pending on its gate
            assertEquals(listOf("dev1"), page.catalog.fetches)
            assertEquals(builtIn, page.model.models) // and the page still shows the built-in list
        }
    }

    @Test
    fun fetchResultAppliesToThePageAndFillsTheCache() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", online = true))))
            val page = newPage(newStore(), options)
            val m1 = listOf(ModelChoice("claude-code", "Claude Code", "m1", "M1", listOf("low")))
            page.catalog.complete("dev1", m1)
            advanceUntilIdle()
            assertEquals(m1, page.model.models)
            assertEquals(m1, ModelCatalogCache["dev1"])
        }
    }

    @Test
    fun reopeningShowsTheCachedListAtOnce() {
        runTest {
            val f = newStore()
            val first = newPage(f, MutableStateFlow(options(hosts = listOf(host("dev1", online = true)))))
            val m1 = listOf(ModelChoice("claude-code", "Claude Code", "m1", "M1", listOf("low")))
            first.catalog.complete("dev1", m1)
            advanceUntilIdle()
            val second = newPage(f, MutableStateFlow(options(hosts = listOf(host("dev1", online = true)))))
            assertEquals(m1, second.model.models) // from the cache, in the same call
        }
    }

    @Test
    fun switchingDevicesKeepsTheCurrentListButStillFillsTheCache() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", true), host("dev2", true))))
            val page = newPage(newStore(), options)
            assertEquals("dev1", page.model.deviceId)
            page.model.update { it.copy(hostId = "dev2") }
            advanceUntilIdle()
            assertEquals("dev2", page.model.deviceId)
            assertEquals(builtIn, page.model.models) // dev2's list (a cache miss → built-in)
            assertTrue(page.catalog.fetches.contains("dev2"))
            // dev1's slow fetch lands late: the cache fills, the page does not take it.
            val m1 = listOf(ModelChoice("claude-code", "Claude Code", "m1", "M1", listOf("low")))
            page.catalog.complete("dev1", m1)
            advanceUntilIdle()
            assertEquals(m1, ModelCatalogCache["dev1"])
            assertEquals(builtIn, page.model.models)
        }
    }

    @Test
    fun emptyFetchResultChangesNothingAndIsNotCached() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", online = true))))
            val page = newPage(newStore(), options)
            page.catalog.complete("dev1", emptyList())
            advanceUntilIdle()
            assertEquals(builtIn, page.model.models)
            assertNull(ModelCatalogCache["dev1"])
        }
    }

    @Test
    fun updateWithoutADeviceChangeFetchesNothingNew() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", online = true))))
            val page = newPage(newStore(), options)
            page.model.update { it.copy(effort = "low") } // deviceId unchanged
            advanceUntilIdle()
            assertEquals(listOf("dev1"), page.catalog.fetches)
        }
    }

    @Test
    fun emptyDeviceIdFetchesNothingAndShowsTheBuiltInCatalog() {
        runTest {
            val page = newPage(newStore(), MutableStateFlow(options()))
            assertEquals("", page.model.deviceId)
            assertEquals(builtIn, page.model.models)
            advanceUntilIdle()
            assertTrue(page.catalog.fetches.isEmpty())
        }
    }

    @Test
    fun disposingThePageStillFillsTheCache() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(options(hosts = listOf(host("dev1", online = true))))
            val page = newPage(newStore(), options)
            page.model.dispose()
            val m1 = listOf(ModelChoice("claude-code", "Claude Code", "m1", "M1", listOf("low")))
            page.catalog.complete("dev1", m1)
            advanceUntilIdle()
            assertEquals(m1, ModelCatalogCache["dev1"]) // the fetch completes anyway
            assertEquals(builtIn, page.model.models) // but is not applied
        }
    }

    // ── engine forget while the page is open ────────────────────────────

    @Test
    fun fullResetFreezesTheOpenPage() {
        runTest {
            val f = newStore()
            f.store.lastDraft = NewSessionDraft(projectId = "A")
            f.store.text = "typed"
            advanceUntilIdle()
            val page = newPage(f, MutableStateFlow(abOptions))
            f.store.forgetEngine(setOf("A"), setOf("H1", "H2"), enginesLeft = 0)
            assertEquals(NewSessionDraft(), f.store.lastDraft)
            assertEquals("", f.store.text)
            // The open page persists nothing from update or rememberDraft.
            page.model.update { it.copy(effort = "high") }
            page.model.rememberDraft("more", listOf(image("i1")))
            assertEquals(NewSessionDraft(), f.store.lastDraft)
            assertEquals("", f.store.text)
            assertEquals(emptyList<StagedImage>(), f.store.images)
        }
    }

    @Test
    fun partialForgetRunsTheStaleRuleOnTheNextOptionsChange() {
        runTest {
            val options = MutableStateFlow<NewSessionOptions?>(abOptions)
            val f = newStore()
            val page = newPage(f, options)
            assertEquals("A", page.model.draft.projectId)
            // A partial forget: the stored draft loses "A"; the page still shows it.
            f.store.forgetEngine(setOf("A"), setOf("dev1"), enginesLeft = 1)
            assertEquals(null, f.store.lastDraft.projectId)
            assertEquals("A", page.model.draft.projectId)
            // The next options change runs normalize: the stale id is nulled and
            // the defaults are re-applied.
            options.value = options(projects = listOf(project("B", "dev2")), hosts = abOptions.hosts)
            advanceUntilIdle()
            assertEquals("B", page.model.draft.projectId)
            // And the change persisted (the store took the stripped + defaulted draft).
            assertEquals("B", f.store.lastDraft.projectId)
        }
    }
}
