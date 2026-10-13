package sh.roboco.android.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.roboco.android.core.PrefKeys
import sh.roboco.android.core.WorkspaceCore
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * One offline fold override, persisted as
 * `"<1|0>|<engineId>|<sectionId>"` under `sectionFoldOverrides` (1 =
 * collapsed). The desired value — not an XOR against the engine's — so a
 * desktop-side flip while the phone was offline cannot silently invert
 * it, and the engine id lets the push wait for **that** engine's
 * `Connected` state without parsing scoped ids. `|` never occurs in a
 * UUID or in a base64url scoped id.
 */
data class SectionFoldOverride(
    val sectionId: String,
    val engineId: String,
    val collapsed: Boolean,
) {
    fun encode(): String = "${if (collapsed) "1" else "0"}|$engineId|$sectionId"

    companion object {
        /** `null` on malformed input — the loader drops it. */
        fun decode(s: String): SectionFoldOverride? {
            val parts = s.split('|', limit = 3)
            if (parts.size != 3) return null
            val collapsed = when (parts[0]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            val engineId = parts[1]
            val sectionId = parts[2]
            if (engineId.isEmpty() || sectionId.isEmpty()) return null
            return SectionFoldOverride(sectionId = sectionId, engineId = engineId, collapsed = collapsed)
        }
    }
}

/** A user-section fold write in flight: the desired value and, once the FFI returns, the revision it landed at. */
data class Pending(val desired: Boolean, val sinceRevision: ULong?)

/**
 * The whole fold state (ticket 40):
 *
 *  - [local] — the phone-local folds of Pinned, Recent and groups under
 *    the iOS key `collapsedSections` (`SessionListController.swift:34-37`);
 *    never pruned, so a fold survives its group reappearing;
 *  - [offline] — the engine-synced user-section folds the engine refused
 *    (offline), to push on reconnect;
 *  - [pending] — the in-flight engine writes (memory only).
 */
data class Fold(
    val local: Set<String> = emptySet(),
    val offline: Map<String, SectionFoldOverride> = emptyMap(),
    val pending: Map<String, Pending> = emptyMap(),
)

/**
 * The warning sink for failed fold writes: production logs
 * `Log.w("RobocoFold", …)`. Plain-JVM tests swap this — the stub
 * `android.util.Log` throws "not mocked" outside Robolectric (the same
 * seam as `sessionLogW`).
 */
internal var foldLogW: (String, Throwable?) -> Unit = { message, error ->
    if (error == null) {
        android.util.Log.w(TAG, message)
    } else {
        android.util.Log.w(TAG, message, error)
    }
}

private const val TAG = "RobocoFold"

/**
 * The one fold-state owner per process (ticket 40; iOS loads `collapsed`
 * per list controller — the phone and the tablet sidebar (105) share this
 * instance). The fold of a section is
 * `pending[id]?.desired ?: offline[id]?.collapsed ?: section.collapsed`
 * for a user section (engine-synced, shared with desktop and web), and
 * `id in local` for every other id.
 *
 * Taps are optimistic and synchronous: the state flips and the haptic
 * fires on the calling thread before any IO is launched, so the chevron
 * (39) turns in the same frame. The engine write then runs on a serial
 * lane (in call order, never on main); a refusal while the engine is
 * offline becomes a persisted override pushed by [reconcile] once that
 * engine is `Connected` again — the phone's value wins (last write wins
 * in the engine store).
 */
class FoldState(
    private val store: DataStore<Preferences>,
    private val core: WorkspaceCore,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Serial: every FFI call and every store edit, in launch order. */
    private val serial = io.limitedParallelism(1)

    private val _state = MutableStateFlow(Fold())
    val state: StateFlow<Fold> = _state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Section ids with a `setSectionCollapsed` in flight (touched only on [serial]). */
    private val inFlight = HashSet<String>()

    init {
        scope.launch(serial) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            val local = preferences[PrefKeys.COLLAPSED_SECTIONS] ?: emptySet()
            val offline = (preferences[PrefKeys.SECTION_FOLD_OVERRIDES] ?: emptySet())
                .mapNotNull { SectionFoldOverride.decode(it) }
                .associateBy { it.sectionId }
            // pending is memory-only; a load never loses a just-tapped one.
            _state.update { it.copy(local = local, offline = offline) }
            loaded.complete(Unit)
        }
    }

    /** Suspends until `local` and `offline` have been read once. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /** The effective fold of [id] right now (see [isCollapsedIn]). */
    fun isCollapsed(id: String, section: SectionView?): Boolean = isCollapsedIn(state.value, id, section)

    /**
     * The effective fold, pure over [fold]: `pending ?: offline ?:
     * section.collapsed` for a user section, `id in local` for a phone-local
     * id (`null` section). The model reads this on the combined `Fold`, not
     * `state.value`, so its rebuild is race-free.
     */
    fun isCollapsedIn(fold: Fold, id: String, section: SectionView?): Boolean {
        if (section != null) {
            fold.pending[id]?.let { return it.desired }
            fold.offline[id]?.let { return it.collapsed }
            return section.collapsed
        }
        return id in fold.local
    }

    /**
     * A fold tap (`SessionListController.toggleSection`, `:193-198`, in the
     * same order: flip, persist, haptic, rebuild — the rebuild is the
     * StateFlow). A phone-local id (a `null` [section]) flips [Fold.local]
     * and persists the whole set. A user section sets [Fold.pending]
     * synchronously, then writes to the engine on the serial lane; see
     * the class docs for the failure paths.
     */
    fun toggle(id: String, section: SectionView?, feedback: Feedback) {
        if (section == null) {
            _state.update { fold ->
                fold.copy(local = if (id in fold.local) fold.local - id else fold.local + id)
            }
            persist()
            feedback.haptic(Haptic.Select)
            return
        }
        val desired = !isCollapsed(id, section)
        // Synchronous, before any IO: the chevron flips in the tap's frame.
        _state.update { fold ->
            fold.copy(pending = fold.pending + (id to Pending(desired = desired, sinceRevision = null)))
        }
        feedback.haptic(Haptic.Select)
        scope.launch(serial) { writeUserSectionFold(id, section, desired) }
    }

    /**
     * Runs on every new snapshot revision and every engines-list change:
     * drops stale pendings, then pushes offline overrides whose engine is
     * `Connected`. Everything (the state reads, the FFI, the persists)
     * happens on the serial lane, so it can never interleave with a tap's
     * write.
     */
    fun reconcile(snapshot: WorkspaceSnapshot, engines: List<EngineView>) {
        scope.launch(serial) {
            val sectionsById = snapshot.front.sections.associateBy { it.id }

            // 1. Pending cleanup: drop when the section is gone, when the
            //    engine caught up, or when a later revision superseded the
            //    write (an RPC failure that reverted the overlay).
            _state.update { fold ->
                if (fold.pending.isEmpty()) {
                    fold
                } else {
                    fold.copy(pending = fold.pending.filter { (id, pending) ->
                        val section = sectionsById[id]
                        if (section == null) return@filter false
                        if (section.collapsed == pending.desired) return@filter false
                        val since = pending.sinceRevision
                        since == null || snapshot.revision <= since
                    })
                }
            }

            // 2. Offline overrides: push once the engine can take them.
            val engineById = engines.associateBy { it.engineId }
            var droppedAny = false
            for ((id, override) in _state.value.offline.entries.toList()) {
                val engine = engineById[override.engineId]
                if (engine == null) { // the engine was forgotten
                    dropOverride(id)
                    droppedAny = true
                    continue
                }
                if (engine.state !is EngineState.Connected) continue // keep it, skip
                val section = sectionsById[id]
                if (section != null && section.collapsed == override.collapsed) {
                    dropOverride(id) // already there, no call
                    droppedAny = true
                    continue
                }
                if (id in inFlight) continue
                inFlight.add(id)
                try {
                    core.setSectionCollapsed(id, override.collapsed)
                    val revision = core.workspaceRevision()
                    _state.update { fold ->
                        fold.copy(
                            offline = fold.offline - id,
                            pending = fold.pending + (id to Pending(override.collapsed, revision)),
                        )
                    }
                    droppedAny = true
                } catch (refused: CoreException.HostUnavailable) {
                    // Keep it for the next trigger.
                } catch (refused: CoreException.Unsupported) {
                    // Keep it for the next trigger.
                } catch (error: CoreException.NotFound) {
                    dropOverride(id)
                    droppedAny = true
                } catch (error: CoreException) {
                    dropOverride(id)
                    droppedAny = true
                    foldLogW("could not push the offline fold for $id", error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    dropOverride(id)
                    droppedAny = true
                    foldLogW("could not push the offline fold for $id", error)
                } finally {
                    inFlight.remove(id)
                }
            }
            if (droppedAny) persist()
        }
    }

    // ------------------------------------------------------------------ internals

    /** The engine write of one tap, on the serial lane. */
    private suspend fun writeUserSectionFold(id: String, section: SectionView, desired: Boolean) {
        inFlight.add(id)
        try {
            core.setSectionCollapsed(id, desired)
            val revision = core.workspaceRevision()
            _state.update { fold ->
                // A newer tap owns the pending entry otherwise.
                if (fold.pending[id]?.desired == desired) {
                    fold.copy(pending = fold.pending + (id to Pending(desired, revision)))
                } else {
                    fold
                }
            }
            if (_state.value.offline.containsKey(id)) {
                _state.update { fold -> fold.copy(offline = fold.offline - id) }
                persist()
            }
        } catch (refused: CoreException.HostUnavailable) {
            writeRefused(id, section, desired)
        } catch (refused: CoreException.Unsupported) {
            writeRefused(id, section, desired)
        } catch (error: CoreException.NotFound) {
            _state.update { fold -> fold.copy(pending = fold.pending - id) }
        } catch (error: CoreException) {
            _state.update { fold -> fold.copy(pending = fold.pending - id) }
            foldLogW("setSectionCollapsed failed for $id", error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update { fold -> fold.copy(pending = fold.pending - id) }
            foldLogW("setSectionCollapsed failed for $id", error)
        } finally {
            inFlight.remove(id)
        }
    }

    /**
     * `HostUnavailable`/`Unsupported`: the engine refused the write while
     * (probably) offline. A tap that lands back on the engine's own value
     * just removes any override; one that keeps the phone's value persists
     * an override to push on reconnect. No toast — a later background
     * failure arrives as `ClientEvent.Notice` and is toasted by the shell
     * (42), not here.
     */
    private fun writeRefused(id: String, section: SectionView, desired: Boolean) {
        _state.update { fold ->
            val offline =
                if (desired == section.collapsed) {
                    fold.offline - id
                } else {
                    fold.offline +
                        (id to SectionFoldOverride(sectionId = id, engineId = section.engineId, collapsed = desired))
                }
            fold.copy(pending = fold.pending - id, offline = offline)
        }
        persist()
    }

    private fun dropOverride(id: String) {
        _state.update { fold -> fold.copy(offline = fold.offline - id) }
    }

    /** Writes both sets from the current state inside one edit, on the serial lane. */
    private fun persist() {
        scope.launch(serial) {
            store.edit { preferences ->
                preferences[PrefKeys.COLLAPSED_SECTIONS] = _state.value.local
                preferences[PrefKeys.SECTION_FOLD_OVERRIDES] =
                    _state.value.offline.values.map { it.encode() }.toSet()
            }
        }
    }
}
