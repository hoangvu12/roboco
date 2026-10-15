package sh.roboco.android.transcript

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrollerRegistry] (ticket 116): the per-row map of live scroller offsets
 * — the dp conversion, the unregister identity rule and snapshot
 * observability (so a `snapshotFlow { row.scrollerOffsetDp(i) }` emits
 * while a scroller scrolls). Plain JUnit 4 + the Compose runtime snapshot:
 * constructing a [ScrollState] and writing snapshot state touches no JNA
 * and no `android.os`; the apply notification is sent by hand, as
 * `TranscriptStateTest` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollerRegistryTest {

    @Test
    fun registerExposesTheStateValueInDp() {
        val registry = ScrollerRegistry()

        // Not composed: 0.
        assertEquals(0f, registry.offsetDp(0), 0f)

        // The ticket's numbers: value 263 at density 2.625 → 100.19 dp.
        val state = ScrollState(263)
        registry.register(0, state, 2.625f)
        assertEquals(263f / 2.625f, registry.offsetDp(0), 1e-3f)
        assertEquals(100.1905f, registry.offsetDp(0), 0.01f)

        // A re-registration with the same state and density writes nothing
        // observable (the same answer), and each index is independent.
        registry.register(0, state, 2.625f)
        assertEquals(263f / 2.625f, registry.offsetDp(0), 1e-3f)
        assertEquals(0f, registry.offsetDp(1), 0f)
    }

    @Test
    fun unregisterIgnoresADifferentStateInstance() {
        val registry = ScrollerRegistry()
        val state = ScrollState(263)
        registry.register(0, state, 2.625f)

        // The old effect's dispose may run after the new SideEffect when the
        // scroller's identity moves: only the registered instance is removed.
        registry.unregister(0, ScrollState(0))
        assertEquals(263f / 2.625f, registry.offsetDp(0), 1e-3f)

        registry.unregister(0, state)
        assertEquals(0f, registry.offsetDp(0), 0f)
    }

    @Test
    fun offsetDpIsSnapshotObservable() = runTest {
        val registry = ScrollerRegistry()
        val state = ScrollState(0)
        registry.register(0, state, 2f)

        val emissions = mutableListOf<Float>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow { registry.offsetDp(0) }.collect { emissions.add(it) }
        }
        advanceUntilIdle() // the collector starts with the current value: 0

        // 20 px at density 2 → 10 dp, without recomposing anything.
        state.dispatchRawDelta(20f)
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceUntilIdle()
        job.cancel()

        assertTrue("no 0 dp emission: $emissions", 0f in emissions)
        assertTrue("no 10 dp emission: $emissions", 10f in emissions)
    }
}
