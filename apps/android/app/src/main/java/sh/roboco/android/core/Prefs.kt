package sh.roboco.android.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicBoolean
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
import sh.roboco.android.design.Appearance

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
    /**
     * `UserDefaults` "appearance" — 0 = System, 1 = Light, 2 = Dark
     * (`SceneDelegate.swift:12-16`). Phone-local, never sent to an engine.
     */
    val APPEARANCE = intPreferencesKey("appearance")

    /** `AppModel.groupByProject` — phone-local, never sent to an engine. */
    val GROUP_BY_PROJECT = booleanPreferencesKey("groupByProject")

    /** Phone-local fold set: Pinned, Recent and `project:*`/`home:*` groups. */
    val COLLAPSED_SECTIONS = stringSetPreferencesKey("collapsedSections")

    /** `"<1|0>|<engineId>|<sectionId>"` offline overrides (see [sh.roboco.android.sessions.FoldState]). */
    val SECTION_FOLD_OVERRIDES = stringSetPreferencesKey("sectionFoldOverrides")

    /**
     * Settings → Background → "Stay connected in background" (ADR 0010):
     * keep the run-watching foreground service, and so the process and its
     * engine connections, up while the app is backgrounded. Absent = off.
     * Phone-local, never sent to an engine.
     */
    val STAY_CONNECTED = booleanPreferencesKey("stayConnectedInBackground")
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
 *
 * Ticket 96 adds the appearance preference — the `UserDefaults` integer
 * `"appearance"` of `SceneDelegate.swift:12-16` — on the same store: the
 * absent key reads `Appearance.System` (iOS `integer(forKey:)` returns 0),
 * an unknown stored value normalises to `System` (`?? .unspecified`), and
 * [setAppearance] mirrors `setGroupByProject`'s no-op/flip/persist shape.
 */
class Prefs(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _group = MutableStateFlow(false)
    val groupByProject: StateFlow<Boolean> = _group.asStateFlow()

    private val _appearance = MutableStateFlow(Appearance.System)

    /** The user's appearance choice — `System` until the disk read lands. */
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _stayConnected = MutableStateFlow(false)

    /**
     * "Stay connected in background" (ADR 0010) — `false` until the disk
     * read lands and whenever the key is absent (opt-in).
     */
    val stayConnected: StateFlow<Boolean> = _stayConnected.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Set by the first [setGroupByProject]: the user's choice beats a later-arriving first read. */
    private val written = AtomicBoolean(false)

    /** Same rule for [setAppearance] (ticket 96): a tap before the load wins. */
    @Volatile
    private var appearanceTouched = false

    /** Same rule for [setStayConnected]: a flip before the load wins. */
    @Volatile
    private var stayConnectedTouched = false

    init {
        scope.launch(io) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            if (!written.get()) _group.value = preferences[PrefKeys.GROUP_BY_PROJECT] ?: false
            // Only when untouched: a setAppearance before the read finishes
            // wins over whatever the disk holds.
            if (!appearanceTouched) {
                _appearance.value = Appearance.fromStored(preferences[PrefKeys.APPEARANCE] ?: 0)
            }
            if (!stayConnectedTouched) {
                _stayConnected.value = preferences[PrefKeys.STAY_CONNECTED] ?: false
            }
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
        written.set(true)
        _group.value = on
        scope.launch(io) {
            store.edit { it[PrefKeys.GROUP_BY_PROJECT] = _group.value }
        }
    }

    fun toggleGroupByProject() = setGroupByProject(!_group.value)

    /**
     * Pick the appearance (ticket 96): no-op when unchanged (iOS rewrites
     * the same `UserDefaults` value, which also changes nothing); otherwise
     * the StateFlow is set immediately — the whole app re-themes on the
     * next frame — and the write lands in the background. As in
     * [setGroupByProject], the edit writes the **current**
     * `_appearance.value`, so out-of-order writes are harmless.
     */
    fun setAppearance(a: Appearance) {
        if (a == _appearance.value) return
        appearanceTouched = true
        _appearance.value = a
        scope.launch(io) {
            store.edit { it[PrefKeys.APPEARANCE] = _appearance.value.storedValue }
        }
    }

    /**
     * Flip "Stay connected in background" (ADR 0010), the
     * [setGroupByProject] shape: no-op when unchanged, the StateFlow flips
     * at once (the run-watch controller starts or stops the service on
     * it), the write lands in the background with the current value.
     */
    fun setStayConnected(on: Boolean) {
        if (on == _stayConnected.value) return
        stayConnectedTouched = true
        _stayConnected.value = on
        scope.launch(io) {
            store.edit { it[PrefKeys.STAY_CONNECTED] = _stayConnected.value }
        }
    }
}
