package sh.roboco.android.newsession

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.roboco.android.composer.DEFAULT_HARNESS
import sh.roboco.android.composer.DraftKey
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.shell.LaunchOptions
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The New Session page's sticky state, app-wide (android-ui-parity #92) —
 * the port of `AppModel`'s `lastDraft` / `newSessionText` /
 * `newSessionImages` (`ios:App/AppModel.swift:60-91`, verbatim in the
 * ticket):
 *
 * - **[lastDraft]** — the options record, persisted as JSON under the
 *   DataStore key `newSessionDraft` (the iOS `UserDefaults` key) on
 *   **every assignment** (the Roboco adaptation of `didSet`: every draft
 *   change is persisted, not only the close paths — same stored state at
 *   every observable moment, and it survives an abnormal process death
 *   while the page is open).
 * - **[text]** — the typed text, in ticket 84's [Drafts] under the iOS
 *   literal `"new-session"` key, with its blank-removes / verbatim-keeps
 *   save rule.
 * - **[images]** — the staged images, **memory only** (this launch, as
 *   on iOS).
 *
 * Threading (ticket 84's write mechanics, identical shape): the mirror is
 * updated synchronously on the main thread (a read right after an
 * assignment sees the new value), and the disk write runs on [scope]
 * (which must outlive every page — `RobocoApplication.appScope`) +
 * [io], writing the mirror's **current** JSON, so writes are
 * order-insensitive and the last state wins.
 *
 * Launch override: the debug `-harness`-style extra (ticket 36's
 * [LaunchOptions.current.harness]) is applied **once per process**, on
 * the first [lastDraft] read after the load has finished, and persisted
 * like any assignment. A later pick can change the harness.
 *
 * Engine forget (no iOS equivalent): every later assignment is stripped
 * of the forgotten engine's ids first ([stripping]); forgetting the last
 * engine is the sign-out analogue — everything resets and [resetEpoch]
 * is bumped, so a page that was open during the reset persists nothing.
 */
class NewSessionDraftStore(
    private val store: DataStore<Preferences>,
    /** Ticket 84's store: the typed text lives under its `"new-session"` key. */
    private val drafts: Drafts,
    /** The process-lifetime scope (`RobocoApplication.appScope`) — outlives every page. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Ticket 36's debug-only harness extra; a seam so tests can pin it. */
    private val launchHarness: () -> String? = { LaunchOptions.current.harness },
    /** The JSON codec; a seam so the plain-JVM model test can fake `org.json`. */
    private val codec: NewSessionDraftCodec = NewSessionDraftJson,
) {

    private val lock = Any()

    /** The synchronous mirror behind [lastDraft] (main thread). */
    private var mirror: NewSessionDraft = NewSessionDraft()

    /** True once the first disk read landed (an assignment before that wins over disk). */
    private var loaded = false

    /** True once a [lastDraft] assignment happened before the load completed. */
    private var assignedBeforeLoad = false

    /** The launch override: applied once, on the first read **after** the load. */
    private var overrideApplied = false

    /** Forgotten project/device ids (process lifetime): stripped from every assignment. */
    private val forgottenIds = HashSet<String>()

    /** Bumped by the full reset; pages compare it with the value they opened with. */
    private var resetEpochValue = 0

    private val loadedSignal = CompletableDeferred<Unit>()

    private val warmStarted = AtomicBoolean(false)

    /** True once the first disk read finished (the mirror then holds the stored draft). */
    val isLoaded: Boolean
        get() = synchronized(lock) { loaded }

    /** Bumped by a full reset (the last engine forgotten); see [forgetEngine]. */
    val resetEpoch: Int
        get() = synchronized(lock) { resetEpochValue }

    /**
     * Starts the first disk read (idempotent; called once from
     * `RobocoApplication.onCreate`, which precedes every activity's
     * `setContent`). A read failure is logged content-free and treated as
     * absent; the store is marked loaded either way.
     */
    fun warm() {
        if (!warmStarted.compareAndSet(false, true)) return
        scope.launch(io) {
            val value: String? = try {
                store.data.first()[KEY]
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // IOException and anything else the first read can throw
                // (the corruption handler already reset the file): absent,
                // marked loaded — never a crash from the app's scope.
                Log.w("Roboco", "new-session load failed")
                null
            }
            synchronized(lock) {
                // An assignment that happened before the load is newer
                // than the disk: keep it.
                if (!assignedBeforeLoad) mirror = codec.decode(value)
                loaded = true
            }
            loadedSignal.complete(Unit)
        }
    }

    /** Suspends until the first disk read finished (starts it if nobody has). */
    suspend fun awaitLoaded() {
        warm()
        loadedSignal.await()
    }

    /**
     * The sticky options. Get: the mirror, plus the **one-time** launch
     * harness override on the first read after the load (not before — a
     * pre-load read returns the default mirror). Set: strip the forgotten
     * ids, mirror now, disk on [scope]. Main thread, like iOS's `didSet`.
     */
    var lastDraft: NewSessionDraft
        get() {
            var persistOverride = false
            val out = synchronized(lock) {
                if (loaded && !overrideApplied) {
                    overrideApplied = true
                    val harness = launchHarness()
                    if (harness != null) {
                        mirror = mirror.copy(harness = harness)
                        persistOverride = true
                    }
                }
                mirror
            }
            if (persistOverride) persist() // "persisted like any assignment"
            return out
        }
        set(value) {
            synchronized(lock) {
                mirror = value.stripping(forgottenIds)
                assignedBeforeLoad = true
            }
            persist()
        }

    /** The typed text, in ticket 84's `Drafts` under [DraftKey.NewSession]. */
    var text: String
        get() = drafts.peek(DraftKey.NewSession)
        set(value) {
            drafts.save(DraftKey.NewSession, value)
        }

    /** Images staged on the page — memory only, this launch, as on iOS. */
    var images: List<StagedImage> = emptyList()

    /**
     * A partial engine forget (≥1 engine remains): the engine's project
     * and device ids are recorded for the rest of the process (every
     * later assignment strips them) and stripped from the stored draft; a
     * forgotten `projectId` also drops its `branch`. The text and images
     * are kept. Ticket 140's forget sequence calls this (on the main
     * thread) with the ids taken from the snapshot read **before** the
     * forget.
     */
    fun forgetEngine(projectIds: Set<String>, deviceIds: Set<String>, enginesLeft: Int) {
        if (enginesLeft <= 0) {
            // The last engine: the iOS sign-out analogue — a plain reset
            // (the launch harness is re-applied only on debug builds, so a
            // live test that forgets and re-pairs never starts a real
            // agent; in release this is exactly iOS's `NewSessionDraft()`).
            synchronized(lock) {
                forgottenIds.addAll(projectIds)
                forgottenIds.addAll(deviceIds)
                resetEpochValue++
            }
            lastDraft = NewSessionDraft(harness = launchHarness() ?: DEFAULT_HARNESS)
            text = ""
            images = emptyList()
            ModelCatalogCache.clear()
            return
        }
        synchronized(lock) { forgottenIds.addAll(projectIds); forgottenIds.addAll(deviceIds) }
        ModelCatalogCache.remove(deviceIds)
        // Re-assign so the stripped draft lands on the mirror and the disk.
        lastDraft = synchronized(lock) { mirror }
    }

    /**
     * Convenience for ticket 140: the ids of [engineId] taken from
     * [before] — the snapshot read **before** forgetting (android-app#07
     * adds `engineId` to both records, so the scoped ids are never parsed
     * in Kotlin).
     */
    fun forgetEngine(engineId: String, before: WorkspaceSnapshot?, enginesLeft: Int) {
        val projectIds = before?.projects
            ?.filter { it.engineId == engineId }
            ?.map { it.id }
            ?.toSet()
            ?: emptySet()
        val deviceIds = before?.devices
            ?.filter { it.engineId == engineId }
            ?.map { it.id }
            ?.toSet()
            ?: emptySet()
        forgetEngine(projectIds, deviceIds, enginesLeft)
    }

    /**
     * The disk write: `awaitLoaded` first, then `store.edit` writes the
     * mirror's **current** JSON (order-insensitive, last state wins). A
     * failed write is logged content-free; the coroutine never throws
     * into the app's scope.
     */
    private fun persist() {
        scope.launch(io) {
            awaitLoaded()
            try {
                store.edit { p -> p[KEY] = codec.encode(synchronized(lock) { mirror }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // IOException from DataStore and anything else a write can
                // throw — content-free on purpose: never the JSON or ids.
                Log.w("Roboco", "new-session write failed")
            }
        }
    }

    companion object {
        /** The DataStore file (`noBackupFilesDir` — engine-scoped ids). */
        const val FILE_NAME = "new_session.preferences_pb"

        /** The iOS `UserDefaults` key, verbatim. */
        val KEY = stringPreferencesKey("newSessionDraft")

        /**
         * The process's single DataStore for [FILE_NAME] (a second active
         * instance on the same file throws). Create once, in
         * `RobocoApplication` — the same shape as ticket 84's
         * `drafts.preferences_pb`.
         */
        fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler {
                    Log.w("Roboco", "new-session store corrupt; reset")
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { File(context.noBackupFilesDir, FILE_NAME) },
            )
    }
}
