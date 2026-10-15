package sh.roboco.android.sidechats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.session.SessionEventHub
import sh.roboco.android.sessions.SessionRowVM
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.SubagentInfo
import uniffi.roboco_core.SubagentState

/**
 * The side chats state holder (android-next #06): the sections a pull
 * yields ([SideChatsUi.of]), the header badge, the FFI lifecycle mapping,
 * the engine-version gate, and [SideChatsModel]'s refresh shape — a pull
 * on start, one per hub event for the chat (a `SessionChanged` moves the
 * spawn chips, a `WorkspaceChanged` the children), the previous rows kept
 * on a failed pull, nothing after stop.
 *
 * Plain JUnit 4 on a [StandardTestDispatcher]: the hub posts inline and
 * the uniffi records/enums touch no JNA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SideChatsModelTest {

    private fun agent(id: String, state: AgentState, at: Long = 0L) =
        SideChatsAgent(docId = "engine:v1:k:chat--sub--$id", title = "Agent $id", state = state, spawnedAtMs = at)

    private fun chat(id: String) = SessionRowVM(
        id = id,
        title = "Side $id",
        projectName = "roboco",
        hasProject = true,
        colorIndex = 6,
        harness = "claude-code",
        branch = "main",
        pr = null,
        prNumber = null,
        status = SessionRowVM.Status.Idle,
        timeLabel = "5m",
        unseen = false,
        pinned = false,
        sendFailed = false,
    )

    /** A scripted source: the next pull's rows, a failure switch and a pull counter. */
    private class FakeSource : SideChatsSource {
        var agents: List<SideChatsAgent> = emptyList()
        var children: List<SessionRowVM> = emptyList()
        var fail = false
        var pulls = 0
        val parents = mutableListOf<String>()

        override fun agents(): List<SideChatsAgent> {
            pulls++
            if (fail) error("the session closed")
            return agents
        }

        override fun children(parentChatId: String): List<SessionRowVM> {
            parents += parentChatId
            return children
        }
    }

    // ------------------------------------------------------------ the sections

    @Test
    fun runningAndFinishedSplitNewestSpawnFirst() {
        val ui = SideChatsUi.of(
            listOf(
                agent("a", AgentState.Done),
                agent("b", AgentState.Running),
                agent("c", AgentState.Failed),
                agent("d", AgentState.Running),
            ),
            listOf(chat("x"), chat("y")),
        )
        assertEquals(listOf("d", "b"), ui.running.map { it.title.removePrefix("Agent ") })
        assertEquals(listOf("c", "a"), ui.finished.map { it.title.removePrefix("Agent ") })
        assertEquals(listOf("x", "y"), ui.chats.map { it.id })
        assertEquals(6, ui.total)
    }

    @Test
    fun theBadgeIsTheRunningPillOrAQuietCountAndHidesWhenEmpty() {
        assertNull("nothing hangs off the chat: no button", SideChatsUi().badge)

        val running = SideChatsUi.of(listOf(agent("a", AgentState.Running), agent("b", AgentState.Running)), listOf(chat("x")))
        val badge = running.badge!!
        assertEquals("● 2", badge.text)
        assertTrue(badge.accent)
        assertEquals("2 running", badge.a11yValue)
        assertEquals("Side chats and agents, 2 running", sideChatsButtonLabel(badge))

        val quiet = SideChatsUi.of(listOf(agent("a", AgentState.Done)), listOf(chat("x"), chat("y"))).badge!!
        assertEquals("3", quiet.text)
        assertFalse(quiet.accent)
        assertNull(quiet.a11yValue)
        assertEquals("Side chats and agents", sideChatsButtonLabel(quiet))
    }

    @Test
    fun theFfiLifecycleMapsOneToOne() {
        assertEquals(AgentState.Running, agentState(SubagentState.RUNNING))
        assertEquals(AgentState.Done, agentState(SubagentState.DONE))
        assertEquals(AgentState.Failed, agentState(SubagentState.FAILED))
        val mirrored = SideChatsAgent.from(
            SubagentInfo(
                docId = "engine:v1:k:chat--sub--t1",
                title = "Audit the repo",
                state = SubagentState.RUNNING,
                tail = null,
                model = "haiku",
                spawnedAtMs = 1_000L,
            ),
        )
        assertEquals(
            SideChatsAgent("engine:v1:k:chat--sub--t1", "Audit the repo", AgentState.Running, null, "haiku", 1_000L),
            mirrored,
        )
        assertTrue(mirrored.running)
    }

    @Test
    fun sideChatsNeedAnEngineFromHalfAYearAgoOrAnUnknownOne() {
        assertFalse("0.4.x predates ForkSideChat", sideChatsSupported("0.4.3"))
        assertTrue(sideChatsSupported("0.5.0"))
        assertTrue(sideChatsSupported("v0.9.2"))
        assertTrue(sideChatsSupported("1.0.0"))
        assertTrue("unknown version: offered (the engine answers)", sideChatsSupported(null))
        assertTrue(sideChatsSupported("dev"))
    }

    // ------------------------------------------------------------ the model

    @Test
    fun pullsOnStartOnTheChatsEventsAndStopsAfterStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = SessionEventHub(post = { it.run() })
        val source = FakeSource().apply {
            agents = listOf(agent("a", AgentState.Running))
            children = listOf(chat("x"))
        }
        val model = SideChatsModel(
            parentChatId = "engine:v1:k:chat",
            source = source,
            hub = hub,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
        assertNull("no pull before start", model.ui.value.badge)

        model.start()
        advanceUntilIdle()
        assertEquals(1, source.pulls)
        assertEquals(listOf("engine:v1:k:chat"), source.parents)
        assertEquals(1, model.ui.value.running.size)
        assertEquals(1, model.ui.value.chats.size)

        // The chip settles: a SessionChanged for this chat re-pulls.
        source.agents = listOf(agent("a", AgentState.Done))
        hub.onClientEvent(ClientEvent.SessionChanged(chatId = "engine:v1:k:chat", revision = 2uL))
        advanceUntilIdle()
        assertEquals(2, source.pulls)
        assertTrue(model.ui.value.running.isEmpty())
        assertEquals(1, model.ui.value.finished.size)

        // Another chat's transcript changes nothing here.
        hub.onClientEvent(ClientEvent.SessionChanged(chatId = "engine:v1:k:other", revision = 3uL))
        advanceUntilIdle()
        assertEquals(2, source.pulls)

        // A new side chat lands with the workspace.
        source.children = listOf(chat("x"), chat("y"))
        hub.onClientEvent(ClientEvent.WorkspaceChanged(revision = 4uL))
        advanceUntilIdle()
        assertEquals(3, source.pulls)
        assertEquals(2, model.ui.value.chats.size)

        model.stop()
        hub.onClientEvent(ClientEvent.WorkspaceChanged(revision = 5uL))
        model.invalidate()
        advanceUntilIdle()
        assertEquals("stopped: no more pulls", 3, source.pulls)
    }

    @Test
    fun aFailedPullKeepsThePreviousRows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource().apply { agents = listOf(agent("a", AgentState.Running)) }
        val errors = mutableListOf<String>()
        val model = SideChatsModel(
            parentChatId = "engine:v1:k:chat",
            source = source,
            hub = SessionEventHub(post = { it.run() }),
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
            logW = { message, _ -> errors += message },
        )
        model.start()
        advanceUntilIdle()
        assertEquals(1, model.ui.value.running.size)

        source.fail = true
        model.invalidate()
        advanceUntilIdle()
        assertEquals(2, source.pulls)
        assertEquals("the rows stay", 1, model.ui.value.running.size)
        assertEquals(listOf("side chats refresh failed"), errors)
        model.stop()
    }
}
