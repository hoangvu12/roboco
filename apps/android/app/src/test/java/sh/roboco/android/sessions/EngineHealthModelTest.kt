package sh.roboco.android.sessions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.ConnectivityState
import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView

/**
 * [EngineHealthModel] (android-ui-parity #50) — the re-check loop:
 * the graced line appears 4 s after the last event with **no new event**
 * (the `delay(nextCheckAtMs − now)` path), recovery hides it at once,
 * and a state change that keeps the engine degraded does not restart the
 * timer (the pull-to-refresh redial case: `Reconnecting → Connecting`).
 *
 * `kotlinx-coroutines-test` on virtual time: the model is built over
 * `MutableStateFlow` inputs on `runTest`'s `backgroundScope` (the
 * StandardTestDispatcher), with `clock = { testScheduler.currentTime }`
 * so a grace never waits real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineHealthModelTest {

    // -------------------------------------------------------------- helpers

    /** One paired engine; every field the ticket exercises set apart. */
    private fun engine(
        id: String,
        name: String = id,
        state: EngineState = EngineState.Connected,
    ): EngineView = EngineView(
        engineId = id,
        name = name,
        host = "host-$id",
        baseUrl = "http://127.0.0.1:1",
        deviceId = null,
        platform = null,
        version = null,
        state = state,
        lastError = null,
        retryAtMs = null,
        lastConnectedMs = null,
        capabilities = emptyList(),
        notice = null,
        fromCache = false,
        diagnostics = EngineDiagnostics(
            repairedRows = 0u,
            skippedRows = 0u,
            lastDecodeError = null,
            missingStreams = emptyList(),
            generation = 0uL,
        ),
    )

    private fun connectivity(state: ConnectivityState): Connectivity =
        Connectivity(state = state, retryAtMs = null, lastFailure = null, degradedChats = emptyList())

    // The model's scope is `runTest`'s backgroundScope, so each test
    // builds its model inline — the shape is identical every time, which
    // keeps the virtual-clock reads (`testScheduler.currentTime`) honest.

    // ------------------------------------------------------------- the loop

    @Test
    fun theLineAppearsAtExactlyTheGraceWithNoNewEvent() = runTest {
        val engines = MutableStateFlow(listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting)))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent() // the eager stateIn collector's first pass, at t = 0

        assertNull(health.ui.value.line)
        advanceTimeBy(3999)
        runCurrent()
        assertNull("3999 ms: still inside the grace window", health.ui.value.line)
        advanceTimeBy(1)
        runCurrent()
        val line = health.ui.value.line
        assertNotNull("4000 ms: the line appears with no new event", line)
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Reconnecting\u2026")),
            line!!.entries,
        )
        assertEquals(setOf("mbp"), health.ui.value.offlineEngineIds)
    }

    @Test
    fun recoveryHidesTheLineAtOnce() = runTest {
        val engines = MutableStateFlow(listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting)))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        advanceTimeBy(4000)
        runCurrent()
        assertNotNull(health.ui.value.line)

        // The engine heals at t = 5000: the new pair cancels the loop
        // (nothing is left to delay on), and the fresh compute sees a
        // healthy source in the same pass — no grace on the hide.
        advanceTimeBy(1000)
        engines.value = listOf(engine("mbp", "MacBook Pro", EngineState.Connected))
        runCurrent()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)
        advanceUntilIdle()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)
    }

    @Test
    fun aDegradedStateChangeDoesNotRestartTheTimer() = runTest {
        val engines = MutableStateFlow(listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting)))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()

        // Pull-to-refresh at t = 2000: `refresh()` re-dials a
        // non-Connected engine, so it may go Reconnecting → Connecting —
        // raw-degraded the whole time. The grace window keeps its
        // original epoch.
        advanceTimeBy(2000)
        runCurrent()
        engines.value = listOf(engine("mbp", "MacBook Pro", EngineState.Connecting))
        runCurrent()
        assertNull("2000 ms: still inside the original grace window", health.ui.value.line)
        advanceTimeBy(1999)
        runCurrent()
        assertNull("3999 ms: still inside the original grace window", health.ui.value.line)
        advanceTimeBy(1)
        runCurrent()
        // 4000 ms after the FIRST degraded sample, not after the flip.
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Connecting\u2026")),
            health.ui.value.line!!.entries,
        )
    }

    @Test
    fun aOneSecondBlipNeverProducesALine() = runTest {
        val engines = MutableStateFlow(listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting)))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        engines.value = listOf(engine("mbp", "MacBook Pro", EngineState.Connected))
        runCurrent()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)
        advanceUntilIdle()
        assertNull("a 1 s blip never shows the line", health.ui.value.line)
    }

    @Test
    fun aHealthyEngineNeverEmitsAnythingButHealthy() = runTest {
        val engines = MutableStateFlow(listOf(engine("a", "Studio")))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)
    }

    // --------------------------------------------------------- the OS source

    @Test
    fun theOsOfflinePostureGracesLikeAnEngine() = runTest {
        val engines = MutableStateFlow(listOf(engine("a", "Studio")))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.CONNECTED))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        conn.value = connectivity(ConnectivityState.OFFLINE)
        runCurrent()
        assertNull(health.ui.value.line)
        advanceTimeBy(3999)
        runCurrent()
        assertNull(health.ui.value.line)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline")),
            health.ui.value.line!!.entries,
        )
    }

    @Test
    fun theDisabledPostureClearsAnInProgressGrace() = runTest {
        val engines = MutableStateFlow(listOf(engine("a", "Studio", EngineState.Reconnecting)))
        val conn = MutableStateFlow<Connectivity?>(connectivity(ConnectivityState.RECONNECTING))
        val health = EngineHealthModel(
            engines = engines,
            connectivity = conn,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertNull(health.ui.value.line)

        // The engines list empties (unpaired): the Disabled posture —
        // healthy now, and the in-flight timer is gone.
        engines.value = emptyList()
        conn.value = connectivity(ConnectivityState.DISABLED)
        runCurrent()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)
        advanceUntilIdle()
        assertEquals(EngineHealthUi.Healthy, health.ui.value)

        // A later pairing starts its grace from zero.
        engines.value = listOf(engine("a", "Studio", EngineState.Reconnecting))
        conn.value = connectivity(ConnectivityState.RECONNECTING)
        runCurrent()
        advanceTimeBy(3999)
        runCurrent()
        assertNull("the timer restarted from the new pairing", health.ui.value.line)
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(health.ui.value.line)
    }
}
