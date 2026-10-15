package sh.roboco.android.newsession

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.roboco.android.composer.DEFAULT_HARNESS
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.composer.foregroundAppScope
import uniffi.roboco_core.NewSession
import uniffi.roboco_core.SendRequest
import uniffi.roboco_core.SessionTarget

/**
 * A side chat's draft (android-next #06, upstream #899's
 * `NewSessionViewController(sideOf:)`): the page model in side mode — it
 * starts on the parent's model, picks no project or host, loads models
 * for the parent's host and never touches the sticky new-session store —
 * and the create it sends: `parentChatId`, the parent's host as the
 * (ignored) target, no branch and never a worktree, whatever the draft
 * says.
 *
 * Plain JVM with the same fakes as `NewSessionModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SideChatDraftTest {

    @Before
    fun clearCatalogCache() {
        ModelCatalogCache.clear()
    }

    private val parent = SideChatParent(
        chatId = "engine:v1:k:chat",
        title = "Streaming veil on transcript rows",
        deviceId = "engine:v1:k:dev",
        projectId = "engine:v1:k:space",
        harness = "codex",
        model = "x1",
        effort = "low",
    )

    private class FakeCodec : NewSessionDraftCodec {
        private val byToken = HashMap<String, NewSessionDraft>()
        private var next = 0
        override fun encode(d: NewSessionDraft): String {
            val token = "d${next++}"
            byToken[token] = d
            return token
        }

        override fun decode(json: String?): NewSessionDraft = json?.let { byToken[it] } ?: NewSessionDraft()
    }

    private class FakeCatalog(private val builtInModels: List<ModelChoice>) : ModelCatalogSource {
        val fetches = mutableListOf<String>()
        override fun builtIn(): List<ModelChoice> = builtInModels
        override suspend fun fetch(deviceId: String): List<ModelChoice> {
            fetches += deviceId
            return emptyList()
        }
    }

    private val builtIn = listOf(
        ModelChoice("claude-code", "Claude Code", "c1", "Claude 1", listOf("low", "medium", "high")),
        ModelChoice("codex", "Codex", "x1", "Codex 1", listOf("low", "medium")),
    )

    private fun project(id: String) = ProjectOption(
        id = id,
        name = id,
        path = "/$id",
        device = "dev-$id",
        deviceName = "dev-$id",
        online = true,
        git = true,
        groupKey = "repo:$id",
        groupName = id,
        colorIndex = 0,
    )

    private fun TestScope.store(): NewSessionDraftStore {
        val drafts = Drafts(FakePrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
        return NewSessionDraftStore(
            FakePrefsStore(),
            drafts,
            foregroundAppScope(),
            StandardTestDispatcher(testScheduler),
            launchHarness = { null },
            codec = FakeCodec(),
        )
    }

    private fun TestScope.page(
        store: NewSessionDraftStore,
        catalog: FakeCatalog,
        side: SideChatParent?,
    ): NewSessionModel {
        store.warm()
        advanceUntilIdle()
        val appScope = foregroundAppScope()
        val pageScope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext.job))
        return NewSessionModel(
            prompt = null,
            store = store,
            options = MutableStateFlow(NewSessionOptions(listOf(project("A")), emptyList())),
            catalog = catalog,
            pageScope = pageScope,
            appScope = appScope,
            io = StandardTestDispatcher(testScheduler),
            main = StandardTestDispatcher(testScheduler),
            side = side,
        )
    }

    // ------------------------------------------------------------ the page

    @Test
    fun aSideDraftStartsOnTheParentsModelAndPicksNoCheckout() = runTest {
        val store = store()
        store.lastDraft = NewSessionDraft(projectId = "A", harness = "claude-code", model = "c1")
        store.text = "a half-typed new session"
        val catalog = FakeCatalog(builtIn)
        val model = page(store, catalog, parent)
        advanceUntilIdle()

        assertEquals(NewSessionDraft(harness = "codex", model = "x1", effort = "low"), model.draft)
        assertNull("no project: the parent's checkout", model.project)
        assertEquals("the parent's host", parent.deviceId, model.deviceId)
        assertEquals("never the new-session text", "", model.initialText)
        assertEquals(listOf(parent.deviceId), catalog.fetches)
        assertEquals("x1", model.selectedModel?.id)
    }

    @Test
    fun aSideDraftNeverWritesTheStickyStore() = runTest {
        val store = store()
        store.lastDraft = NewSessionDraft(projectId = "A")
        store.text = "kept"
        val model = page(store, FakeCatalog(builtIn), parent)
        advanceUntilIdle()

        model.update { it.copy(harness = "claude-code", model = "c1") }
        model.persistBeforeCreate()
        model.rememberDraft("side text", emptyList())
        assertEquals(NewSessionDraft(projectId = "A"), store.lastDraft)
        assertEquals("kept", store.text)
        model.markCreated()
        assertEquals("a side chat's create leaves the new-session text alone", "kept", store.text)
    }

    @Test
    fun aParentWithoutAHarnessStartsOnTheDefault() {
        assertEquals(
            NewSessionDraft(harness = DEFAULT_HARNESS),
            sideChatDraft(parent.copy(harness = null, model = null, effort = null)),
        )
    }

    // ------------------------------------------------------------ the create

    @Test
    fun theCreateNamesTheParentAndIgnoresTheDraftsCheckout() {
        val draft = NewSessionDraft(projectId = "A", branch = "topic", worktree = true, harness = "codex", model = "x1")
        val target = NewSessionRequests.target(draft, parent)
        assertEquals(SessionTarget.Projectless(deviceId = parent.deviceId), target)
        val create: NewSession = NewSessionRequests.newSession(draft, target!!, parent)
        assertEquals(parent.chatId, create.parentChatId)
        assertNull("the parent's checkout: no branch", create.branch)
        assertEquals("codex", create.config?.harness)
        assertEquals("x1", create.config?.model)
        val send: SendRequest = NewSessionRequests.sendRequest(draft, "ask", emptyList(), listOf(project("A")), parent)
        assertNull("a side chat never makes a worktree", send.worktree)
        assertEquals("ask", send.text)
    }

    @Test
    fun aNewSessionStillCreatesTopLevel() {
        val draft = NewSessionDraft(projectId = "A", branch = "main")
        val target = NewSessionRequests.target(draft)!!
        assertEquals(SessionTarget.Project(spaceId = "A"), target)
        val create = NewSessionRequests.newSession(draft, target)
        assertNull(create.parentChatId)
        assertEquals("main", create.branch)
    }

    @Test
    fun theCreatorSendsTheSideCreateThenTheFirstMessage() = runTest {
        val created = mutableListOf<NewSession>()
        val sent = mutableListOf<Pair<String, SendRequest>>()
        val creator = NewSessionCreator(
            core = object : NewSessionCore {
                override suspend fun createSession(newSession: NewSession): String {
                    created += newSession
                    return "engine:v1:k:side"
                }

                override suspend fun sendFirst(chatId: String, request: SendRequest) {
                    sent += chatId to request
                }
            },
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        val id = creator.createAndSend(NewSessionDraft(harness = "codex"), "ask", emptyList(), emptyList(), parent)
        assertEquals("engine:v1:k:side", id)
        assertEquals(parent.chatId, created.single().parentChatId)
        assertEquals("engine:v1:k:side", sent.single().first)
    }
}
