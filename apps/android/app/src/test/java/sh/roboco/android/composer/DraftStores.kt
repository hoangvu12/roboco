package sh.roboco.android.composer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

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
