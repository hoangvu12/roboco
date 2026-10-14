package sh.roboco.android.composer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * The app-scope stand-in of the draft-store tests (`RobocoApplication.appScope`):
 * a **foreground** scope on the test scheduler.
 *
 * Not `backgroundScope`: `TestCoroutineScheduler.advanceUntilIdle()`
 * returns as soon as no *foreground* event is queued, so a load or write
 * launched on `backgroundScope` never runs under `advanceUntilIdle()`
 * (only a `join`, a suspending read or `runCurrent()` drive it) — the
 * stores' `warm()` and disk writes stayed queued and the assertions read
 * an unloaded mirror and an empty disk. Not a child of the test job
 * either, so a collector left suspended at the end cannot hold `runTest`.
 */
internal fun TestScope.foregroundAppScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

/**
 * The plain-JVM DataStore stand-in shared by the draft tests
 * (android-ui-parity #84): an in-memory `Preferences` map whose first
 * `data` emission waits on [gate] — pre-completed by default, so an
 * eager store reads immediately; a held gate lets a test exercise the
 * pending-load rules. [stored] and [names] expose the "disk" for
 * assertions. No file, no scope to cancel.
 */
internal class FakePrefsStore(
    initial: Map<String, String> = emptyMap(),
    private val gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) },
) : DataStore<Preferences> {
    private val state = MutableStateFlow(
        preferencesOf(*initial.map { (name, value) -> stringPreferencesKey(name) to value }.toTypedArray()),
    )

    /** The first collection waits on the gate, then emits the current value. */
    override val data: Flow<Preferences> = flow {
        gate.await()
        emit(state.value)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }

    /** The value currently "on disk" for [key], or null (absent). */
    fun stored(key: DraftKey): String? = state.value[stringPreferencesKey(key.storageName)]

    /** Every storage name currently "on disk". */
    val names: Set<String>
        get() = state.value.asMap().keys.map { it.name }.toSet()
}
