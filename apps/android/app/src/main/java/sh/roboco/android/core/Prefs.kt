package sh.roboco.android.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The keys of the phone-local `roboco_prefs` store (ticket 40). The names
 * are the iOS `UserDefaults` keys, so a user switching platforms finds the
 * same semantics: `"groupByProject"` is the Sessions-tab grouping flag
 * (`ios:App/AppModel.swift:18-31`), `"collapsedSections"` the phone-local
 * folds of Pinned/Recent/groups (`ios:Threads/SessionListController.swift:34-37`),
 * and `"sectionFoldOverrides"` the offline overrides of engine-synced user
 * sections (Roboco's addition). Tickets 96 and 100 extend this object
 * rather than adding a second file.
 */
object PrefKeys {
    /** `AppModel.groupByProject` — phone-local, never sent to an engine. */
    val GROUP_BY_PROJECT = booleanPreferencesKey("groupByProject")

    /** Phone-local fold set: Pinned, Recent and `project:*`/`home:*` groups. */
    val COLLAPSED_SECTIONS = stringSetPreferencesKey("collapsedSections")

    /** `"<1|0>|<engineId>|<sectionId>"` offline overrides (see [sh.roboco.android.sessions.FoldState]). */
    val SECTION_FOLD_OVERRIDES = stringSetPreferencesKey("sectionFoldOverrides")
}

/**
 * The one `roboco_prefs` [DataStore] per process (a second instance on the
 * same file throws). Every phone-local preference of the parity set reads
 * and writes through this single delegate.
 */
val Context.robocoPrefsStore: DataStore<Preferences> by preferencesDataStore(
    name = "roboco_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The phone-local preferences (ticket 40) — the `AppModel.groupByProject`
 * property (`ios:App/AppModel.swift:16-33`) over DataStore instead of
 * `UserDefaults`:
 *
 *  - the absent key reads `false` (iOS `bool(forKey:)`'s default);
 *  - `setGroupByProject` is a no-op on an equal value (the iOS
 *    `guard newValue != groupByProject`), and a real change notifies every
 *    observer at once — here one [StateFlow] whose collectors rebuild the
 *    Sessions list;
 *  - the StateFlow flips immediately and persists in the background, so
 *    the list never waits on the disk;
 *  - DataStore reads asynchronously, so the first read is awaited via
 *    [awaitLoaded] instead of iOS's synchronous lookup.
 */
class Prefs(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _group = MutableStateFlow(false)
    val groupByProject: StateFlow<Boolean> = _group.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(io) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            _group.value = preferences[PrefKeys.GROUP_BY_PROJECT] ?: false
            loaded.complete(Unit)
        }
    }

    /** Suspends until the stored value has been read once. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /**
     * Flip the grouping flag: no-op when unchanged; otherwise the StateFlow
     * is set immediately and the write lands in the background. The edit
     * writes the **current** `_group.value`, so out-of-order writes are
     * harmless (the last value wins whatever the interleaving).
     */
    fun setGroupByProject(on: Boolean) {
        if (on == _group.value) return
        _group.value = on
        scope.launch(io) {
            store.edit { it[PrefKeys.GROUP_BY_PROJECT] = _group.value }
        }
    }

    fun toggleGroupByProject() = setGroupByProject(!_group.value)
}
