package sh.roboco.android.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.PreviewPanelListener
import uniffi.roboco_core.PreviewPanelPhase
import uniffi.roboco_core.PreviewPanelState
import uniffi.roboco_core.PreviewServiceRow

/**
 * The preview panel's behaviour (agent-previews ticket 05) on the plain
 * JVM: [PreviewPanelModel] over a recording [PreviewsCore] fake — the
 * state callback's hop into the model's state flow, the start/stop
 * lifecycle (the watch closes exactly once, the Rust watcher going with
 * it), the unpaired-engine degradation, and the panel state machine's
 * passthrough (the phases arrive as the core sends them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPanelModelTest {

    // ------------------------------------------------- the state builders
    // (file-level: the nested fakes are non-inner classes)


    // ------------------------------------------------------------ the fakes

    /** A panel watch the fake core hands out; records its stop. */
    private class FakePanelWatch(var answer: PreviewPanelState) : PanelWatch {
        var closed = 0
        override fun state(): PreviewPanelState = answer
        override fun stop() {
            closed++
        }
    }

    /** Records every call; hands the panel out and captures the listener. */
    private class FakePreviewsCore : PreviewsCore {
        val calls = mutableListOf<String>()
        val watches = mutableListOf<FakePanelWatch>()
        var watchThrows: CoreException? = null
        var listener: PreviewPanelListener? = null
        var proxyWatching = mutableListOf<Pair<String, Boolean>>()

        override suspend fun watch(chatId: String, listener: PreviewPanelListener): PanelWatch {
            calls += "watch:$chatId"
            this.listener = listener
            watchThrows?.let { throw it }
            return FakePanelWatch(loadingState()).also { watches += it }
        }

        override suspend fun proxyStatus() =
            uniffi.roboco_core.PreviewProxyStatus(port = null, error = null)

        override fun setProxyWatching(engineId: String, watching: Boolean) {
            proxyWatching += engineId to watching
        }
    }

    // ------------------------------------------------------------ the tests

    @Test
    fun theWatchStartsOnceAndItsStatesFlowThroughTheHop() = runTest {
        val core = FakePreviewsCore()
        val model = PreviewPanelModel("engine:v1:chat-1", core = { core }, scope = this, io = StandardTestDispatcher(testScheduler))
        model.start()
        model.start() // idempotent: one watch, one listener
        advanceUntilIdle()
        assertEquals(listOf("watch:engine:v1:chat-1"), core.calls)
        assertNull(model.state.value) // loading until the first callback

        // The Rust callback thread hops through the scope: the current
        // state lands first (the FFI delivers it immediately), then every
        // change.
        val listener = core.listener!!
        listener.onState(liveState(service()))
        advanceUntilIdle()
        assertEquals(PreviewPanelPhase.LIVE, model.state.value?.phase)
        assertEquals(1, model.state.value?.services?.size)

        listener.onState(loadingState())
        advanceUntilIdle()
        assertEquals(PreviewPanelPhase.LOADING, model.state.value?.phase)
    }

    @Test
    fun stopClosesTheWatchExactlyOnceAndIsIdempotent() = runTest {
        val core = FakePreviewsCore()
        val model = PreviewPanelModel("engine:v1:chat-1", core = { core }, scope = this, io = StandardTestDispatcher(testScheduler))
        model.start()
        advanceUntilIdle()
        model.stop()
        model.stop() // idempotent: the landed watch closed exactly once
        assertEquals(listOf(1), core.watches.map { it.closed })
        // A start after stop opens a fresh watch (a re-entry after a tab
        // host disposal), and it too closes exactly once.
        model.start()
        advanceUntilIdle()
        assertEquals(2, core.calls.size)
        model.stop()
        assertEquals(listOf(1, 1), core.watches.map { it.closed })
    }

    @Test
    fun anUnpairedEngineDegradesOnceToTheHonestNote() = runTest {
        val core = FakePreviewsCore()
        core.watchThrows = CoreException.NotFound("Unknown engine for chat: engine-9")
        val model = PreviewPanelModel("engine:v1:chat-1", core = { core }, scope = this, io = StandardTestDispatcher(testScheduler))
        model.start()
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(PreviewPanelPhase.RECONNECTING, state?.phase)
        assertTrue(state?.services.isNullOrEmpty())
        assertEquals("Unknown engine for chat: engine-9", state?.error)
        // Never retried: the degradation is final until the screen reopens.
        assertEquals(listOf("watch:engine:v1:chat-1"), core.calls)
    }

    @Test
    fun noCoreMeansNoWatchAndNoState() = runTest {
        val model = PreviewPanelModel("engine:v1:chat-1", core = { null }, scope = this, io = StandardTestDispatcher(testScheduler))
        model.start()
        advanceUntilIdle()
        assertNull(model.state.value)
        model.stop() // inert
    }

    @Test
    fun identicalStatesDoNotRepublish() = runTest {
        val core = FakePreviewsCore()
        val model = PreviewPanelModel("engine:v1:chat-1", core = { core }, scope = this, io = StandardTestDispatcher(testScheduler))
        model.start()
        advanceUntilIdle()
        val listener = core.listener!!
        listener.onState(liveState(service()))
        advanceUntilIdle()
        val first = model.state.value
        var publications = 0
        val job = launch {
            model.state.collect { publications++ }
        }
        advanceUntilIdle()
        val before = publications
        listener.onState(liveState(service()))
        advanceUntilIdle()
        assertEquals(before, publications) // StateFlow dedups by equality
        job.cancel()
    }

    private companion object {
        fun service(id: String = "srv-1", name: String = "Vite", port: UShort = 5173u) =
            PreviewServiceRow(
                id = id,
                name = name,
                hostname = "studio.$id.localhost",
                port = port,
                deviceName = "Studio",
                url = "http://studio.$id.localhost:7332",
            )

        fun loadingState() = PreviewPanelState(
            phase = PreviewPanelPhase.LOADING,
            engineId = "engine-1",
            services = emptyList(),
            error = null,
        )

        fun liveState(vararg services: PreviewServiceRow) = PreviewPanelState(
            phase = PreviewPanelPhase.LIVE,
            engineId = "engine-1",
            services = services.toList(),
            error = null,
        )
    }
}
